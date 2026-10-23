# 识别 Worker 服务端设计

- **形态**：单进程。FastAPI（HTTP）+ 后台消费线程（队列）——`python run_server.py` 一条命令起全部
- **存储**：SQLite 单文件，**既是主库也是队列**
- **验证**：`python server/smoke_test.py`（起真实服务跑完整链路，35 项断言）

---

## 一、为什么不用 Redis / Celery / RabbitMQ

单家庭私有部署，请求量是个位数 QPS，识别任务一天最多几十条。
引入消息中间件的**运维成本远大于它带来的收益**：多一个要装的容器、多一个会坏的组件、
多一套要盯的监控。SQLite 表 + `BEGIN IMMEDIATE` 的原子领取，在这个量级上完全可靠。

**什么时候该换掉**：出现下列任一情况再迁移，迁移面被刻意压缩在 `server/queue.py` 一个文件里。

- 需要多机部署，或单机多进程并发消费
- 队列日增百万级（SQLite 的写锁会成为瓶颈）
- 需要复杂的延迟/优先级/依赖调度

## 二、目录与职责

```
run_server.py            启动入口：建库 → 起消费线程 → 起 uvicorn
server/
├── config.py            .env 读取，路径全部以项目根为基准（不受 cwd 影响）
├── db.py                连接 + PRAGMA + 事务 + 时间工具
├── recognizer.py        模型调用（提示词、图片编码、请求、解析）—— 无 DB、无 HTTP
├── store.py             所有 SQL 与业务规则（草稿态、幂等、修正留档）
├── queue.py             队列语义：入队 / 领取 / 租约 / 退避 / 回收
├── worker.py            消费线程循环
├── api.py               HTTP 路由 —— 只做鉴权、参数、响应信封，**不放业务规则**
└── smoke_test.py        端到端冒烟测试
```

分成这七块是为了让**每一块都能被单独替换或单独测试**：

- `recognizer.py` 不碰数据库 → 换个模型厂商只改这一个文件
- `queue.py` 不碰模型和 HTTP → 换 Redis 只改这一个文件
- `api.py` 不含业务规则 → 业务规则全在 `store.py`，HTTP 层薄到可以忽略
- `store.py` 是唯一写 SQL 的地方 → 审计「模型输出何时能进库」只需看一个文件

## 三、队列设计（这是本模块的核心）

三张字段撑起可靠性，缺一个都会出问题：

| 字段 | 作用 | 缺了会怎样 |
|---|---|---|
| `next_retry_at` | 退避：此时间之前不可领取 | 撞 429 时疯狂重试，越限越重，直到把额度打空 |
| `leased_until` | 租约：识别中任务的到期时间 | Worker 被杀/机器重启后，任务**永远卡在「识别中」** |
| `max_retry` | 重试上限 | 一张坏图无限烧钱 |

### 原子领取

两个并发消费者不能领到同一条任务。靠 `BEGIN IMMEDIATE`（立即取写锁）+ 条件 UPDATE：

```sql
BEGIN IMMEDIATE;
SELECT id FROM recognition_job
 WHERE status=0 AND (next_retry_at IS NULL OR next_retry_at <= :now)
 ORDER BY id LIMIT 1;
UPDATE recognition_job
   SET status=1, started_at=COALESCE(started_at,:now), leased_until=:now_plus_lease
 WHERE id=:id;
COMMIT;
```

**注意 `started_at=COALESCE(started_at, :now)`** —— 重试时不该把首次开始时间冲掉，
否则「这个任务卡了多久」永远看不到。

### 僵尸任务回收

每次领取前先执行一次：

```sql
UPDATE recognition_job SET status=0, leased_until=NULL
 WHERE status=1 AND (leased_until IS NULL OR leased_until < :now);
```

这就是**不引入外部中间件的代价与补偿**：必须自己写这五行的回收逻辑。
不写的话，进程一崩任务就永久卡死，而且没有任何报警。

### 重试放在队列、不放在调用函数里

`recognizer.call_model()` **故意不做重试**。理由：如果把重试写在函数里，
进程重启就丢掉了重试计数，一个必然失败的任务会在每次重启后重新开始烧钱。
放在队列里，`retry_count` 落盘，重启后继续累计。

退避序列：`min(300, 15 × 2^n)` → 15s / 30s / 60s / 120s / 240s / 300s；
429 限流时改用服务端建议的 30s。

### 状态机

```
0 排队 ──claim──▶ 1 识别中 ──成功──▶ 2 成功（终态）
   ▲                  │
   │                  ├──可重试 & 未超上限──▶ 回 0（带 next_retry_at）
   └──────────────────┤
                      ├──可重试 & 超上限 ──▶ 3 失败（终态）
                      └──不可重试 ────────▶ 4 已放弃（终态）
```

「可重试」的判定在 `recognizer.py` 里，跟着异常一起抛出：

- `429` / `5xx` / 超时 / 连接重置 → 可重试
- 其他 `4xx`（鉴权错、参数错）→ 不可重试，重试只是浪费钱
- **JSON 解析失败 → 不可重试**。同一个提示词重跑大概率还是解析失败，
  正确处理是修提示词并升 `prompt_version`，而不是重试

## 四、作业单的身份键（一个真 bug 的复盘）

`assignment` 的唯一键是 **`(student_id, assign_date, subject)`**，不是 `title`。

第一版实现用的是 `UNIQUE (student_id, assign_date, title)`，看起来挺自然，
然后冒烟测试直接把它打出来了：

```
家长把标题改成「英语作业（已核对）」
  → 重识别时模型输出的 title 仍是「英语作业」
  → 唯一键不匹配，ON CONFLICT 没生效
  → 又插入一条新的作业单：2 条变 3 条
```

根因是**把展示属性当成了身份**。改掉之后，重放同一张图，作业单数量与作业项数量都不变。

顺带定了一条规则：**重识别不覆盖家长改过的标题**。
重识别一次就把人刚改好的标题冲掉，比标题不新鲜糟糕得多。
想彻底重来，先 `DELETE /assignments/{id}` 删掉草稿再重新识别。

## 五、家长确认流（人在环路）

```
        ┌──────────────────────────────────────────────┐
        │  模型输出 = 草稿（status=0）                  │
        │  永远不直接成为孩子的作业                      │
        └──────────────────────────────────────────────┘
                            │
       GET /recognition-jobs/{jobId}  ← 一次请求拿到 draft + assignments
                            │
        ┌───────────────────┴───────────────────┐
        │                                       │
   PATCH /assignments/{id}               DELETE /assignments/{id}
   （改日期/标题/题项，逐处留档）          （老师撤回 / 拍错图）
        │
   POST /assignments/{id}/publish
   （校验：日期非空 + 至少一项 + 无空题干）
        │
        ▼  已发布 status=1 —— 此后**不可改、不可删**
```

三条约束及其理由：

1. **草稿才可改、可删。** 已发布返回 `409`。改掉孩子正在做的作业会造成远端状态与
   孩子平板上的本地副本冲突——那是离线同步最容易出的一类 bug，从源头堵掉。
2. **发布要过校验。** 日期为空就没有「哪一天」的概念；作业项为空或题干为空，
   孩子端会渲染出一张做不了的单子。宁可在这里报错，也不要让它进到孩子端。
3. **发布幂等。** 重复调用返回当前状态而不是报错——手机端网络抖动时重试是常态。

修正记录写进 `recognition_correction`（`field_path` / 改前 / 改后）。
这张表是全项目**唯一能告诉你「提示词哪里不对」的数据**，比任何日志都有用。

## 六、接口清单

前缀 `/api/v1`。鉴权 `Authorization: Bearer <FAMILY_TOKEN>`；
响应信封成功 `{code:0,message:"ok",data,traceId}`，失败 `{code,message,traceId}`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/system/ping` | 连通性探测，**免鉴权**（App 首次配置服务端地址时用） |
| GET | `/system/health` | 队列水位、缓存数、草稿数、已发布数 |
| GET / PUT | `/family` | 家庭配置（名字、时区） |
| GET / POST | `/devices` | 设备登记（家长端可看孩子平板最后同步时间） |
| GET | `/students` | 孩子列表 |
| POST | `/captures` | **上传截图 → 去重 → 入队**，立即返回 `jobId` |
| POST | `/captures/{id}/recognize` | 重新发起识别（`force=true` 顺带清缓存） |
| GET | `/recognition-jobs/{jobId}` | 轮询结果；成功时附带 `draft` 与 `assignments` |
| GET | `/recognition-jobs` | 任务列表（排查用） |
| POST | `/recognition-jobs/{jobId}/corrections` | 单独补一条修正记录 |
| GET | `/assignments` | 按日期/状态查作业单 |
| GET | `/assignments/{id}` | 作业单详情（含作业项） |
| PATCH | `/assignments/{id}` | **家长确认 / 修正草稿** |
| POST | `/assignments/{id}/publish` | 发布（幂等） |
| DELETE | `/assignments/{id}` | 丢弃草稿 |

**HTTP 层绝不等待模型。** 实测单次识别 6–60s 还会撞 429，
同步等模型会把请求线程占满、手机端超时、重试又加倍压到模型端。
上传接口只做「落盘 + 入队 + 返回 jobId」三件事。

## 七、部署

```bash
# 本机开发
python run_server.py --host 127.0.0.1 --port 8080

# NAS 上（内网 192.168.1.10）
nohup python run_server.py --host 0.0.0.0 --port 8080 >> data/server.log 2>&1 &

# 接口与消费分离（识别压力大时）
python run_server.py --no-worker &        # 只有 HTTP
python -c "from server.worker import RecognitionWorker; w=RecognitionWorker(); w.start(); w._threads[0].join()"
```

`WORKER_CONCURRENCY` 默认 **1**。实测该端点会返回 `429 tpm/rpm limit`，
并发只会把限流撞得更频繁，不会提高吞吐。要提并发必须先确认配额。

App 首次配置需要填两样：服务端地址、`FAMILY_TOKEN`。地址填错时可以调
`/system/ping`（免鉴权）先验连通，再验 Token——把两类错误分开报。

## 八、本轮踩到的三个坑（都已在代码里修掉）

1. **FastAPI 依赖注入的连接不能跨线程用。**
   同步依赖和同步端点会被分别丢进 anyio 线程池，**不保证同一个线程**，
   于是 sqlite3 直接抛 `ProgrammingError: SQLite objects created in a thread can only be used in that same thread`。
   → 改成在**端点数体内**调 `db.thread_conn()` 取线程独占连接（连接按线程缓存，顺带省掉重连开销）。
   教训：**SQLite 这东西对线程很敏感，别用框架的魔法管连接。**

2. **作业单身份键不能用 title**（见第四节）。

3. **`job_assignment` 的关联建晚了。**
   原本写成「落库成功才记关联」，结果重识别一张已发布的单子时，
   因为选择不覆盖而跳过了关联，客户端看到的是「识别完成，产出 0 条作业单」。
   → 关联的语义是「这次识别**涉及**了哪些作业单」，应当在解析出 `assignment_id` 之后立刻建立。

## 九、M2 已整体暂缓（2026-10-09 决定）

作答 / 批改 / 错题本 / 学情**暂时不做**，实现保留但未接入主路径。

| 保留物 | 位置 | 状态 |
|---|---|---|
| 作答判定（批改） | `server/grader.py` | 单测 31 项全绿，未被任何路由引用 |
| 作答落库 / 错题本 / 掌握度 / 学情报告 | `server/study.py` | 同上 |
| 数据表 | `practice_session` / `wrong_book` / `mastery` / `question_*` | DDL 里都在 |
| 客户端离线补传 | `core-sync` 模块 + `answer_outbox` 表 | 已建骨架 |

**为什么暂缓**：识别产出的作业项默认没有 `question_id`，也就没有标准答案，
自动批改的**实际覆盖率天然很低** —— 这是 `grader.py` 里「宁可返回待批也不猜」
那条原则推导出来的必然结果。家里自己用，先把识别主干跑顺比把批改做完整更值。

**恢复方式**：在 `server/api.py` 末尾把 8 个端点接回来（见 git 历史），数据层不用改。

## 十、尚未做的部分（后续）

| 缺什么 | 说明 |
|---|---|
| HTTPS | 当前是内网明文。真要出公网必须套反向代理 + 证书 |
| 家长端/孩子端鉴权区分 | 现在两端共用一个 `FAMILY_TOKEN`，孩子端拿到的令牌理论上也能发布作业 |
| 备份 | 记得用 `VACUUM INTO`，**不要直接 cp**（WAL 下会漏数据） |
| 日志落盘 | 现在只走 stdout，NAS 上应配 systemd 收集 |
| 纸质照片识别验证 | 已验的 3 张都是截图；真实拍照条件（歪斜 / 阴影 / 手写批注）尚未覆盖 |
