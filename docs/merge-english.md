# 英语服务融合进 nuo_study（2026-10-23）

一句话：**NAS 上原来跑着两个容器，现在只有一个。** 英语的四个能力（TTS / 语音转写 /
千问对话 / 腾讯智聆跟读评测）和那个英语网页，都并进了 `nuo-study`；`nuonuo-english`
这个容器（Node 单文件服务，4173 端口）退休。

## 融合前 / 融合后

| | 融合前 | 融合后 |
|---|---|---|
| 进程 | 两个：`nuo-study`（Python）+ `nuonuo-english`（Node） | 一个：`nuo-study` |
| 端口 | 8092 + 4173 | 只有 8092 |
| 配置 | 两份 `.env`（两份 key、两处改端口） | 一份 `.env` |
| 部署 | `deploy_nas.py` + 另一个项目的 compose | 只有 `deploy_nas.py` |
| 网页 | `http://192.168.1.10:4173/` | `http://192.168.1.10:8092/english/`（根路径 `/` 也跳过去） |
| TTS 缓存 | 老项目 `data/tts` | `data/tts`（**缓存会重新生成一次**，见「行为差异」） |
| 课文录音 | 老项目 `dist/audio` | `web/english/audio`（40 段，随代码同步） |
| App 配置 | 学业 8092、英语 4173（两个地址） | 只有 8092，跟「设置」页填的那个地址一致 |

## 代码在哪

```
server/english.py        四个能力的全部实现（urllib + websockets，没有 Node）
web/english/             英语网页（index.html / app.js / styles.css / audio 40 段）
tools/merge_english_nas.py   NAS 上的一次性迁移（搬 key → 删老容器 → 重建 → 验证）
```

`english.py` 是**照着 `server.mjs` 逐行搬的**，不是重写：提示词、`max_tokens=180`、
签名算法、缓存键、错误文案都保持原样。原因很实际 —— 那套参数是实测调出来的
（见文件头「四个别顺手优化掉的地方」），重写一遍就得再踩一遍坑。

## 路由表

新代码用 `/api/v1/english/*`；老路径是给英语网页用的（`app.js` 是编译产物，
里面写死了 `/api/tts` 这些），两套指向同一份实现。

| 能力 | 规范路径 | 兼容路径（网页在用） |
|---|---|---|
| 配置状态 | `GET /api/v1/english/health` | `GET /api/health` |
| 语音合成 | `POST /api/v1/english/tts` | `POST /api/tts` |
| 语音转写 | `POST /api/v1/english/transcribe` | `POST /api/transcribe` |
| AI 对话 | `POST /api/v1/english/chat` | `POST /api/chat` |
| 跟读评测 | `POST /api/v1/english/evaluate` | `POST /api/evaluate` |
| TTS 缓存文件 | `GET /generated-tts/<sha256>.mp3` | 同一个（路径没动） |
| 教材内容 | `GET /api/v1/english/curriculum`（另有 `/{unit}`、`/units`） | — |

教材内容那三个接口是 2026-10-23 新加的（见 `docs/curriculum.md`），网页靠它们渲染
单元、单词、课文和练习 —— 之前这些全是写死在 `app.js` 里的 6 个词和 3 道题。

**英语这一组不走家庭令牌鉴权**（网页那边拿不到令牌，和老服务一致）。
也就是说局域网里知道 `8092` 的人就能调 TTS 和对话 —— 这个前提是「家里内网 +
自己家的 key」。哪天要挂到公网，第一件事是给这组接口加 `?token=`。

## 环境变量（都写进 `nuo_study/.env`）

```ini
# 微软语音合成
AZURE_SPEECH_KEY=
AZURE_SPEECH_REGION=eastasia
AZURE_TTS_VOICE=en-GB-LibbyNeural

# 腾讯智聆（跟读评测）
TENCENT_APP_ID=
TENCENT_SECRET_ID=
TENCENT_SECRET_KEY=

# 千问（陪练对话文本 + 语音转写共用这一把 key）
QWEN_API_KEY=
QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_CHAT_MODEL=qwen-flash

# 语音转写：两个引擎选一个（默认 http）
QWEN_ASR_ENGINE=http                                  # http | realtime
QWEN_ASR_HTTP_MODEL=qwen3-asr-flash                   # 走 chat/completions
QWEN_ASR_MODEL=qwen3-asr-flash-realtime               # 走 WebSocket
QWEN_ASR_WS_URL=wss://dashscope.aliyuncs.com/api-ws/v1/realtime
QWEN_ASR_LANGUAGE=en                                  # 语种提示，减少中文谐音误识别
QWEN_ASR_MAX_SECONDS=55                               # realtime 单次上限，超了自动转 http

# 语音转写（只在没配千问时才用）
OPENAI_API_KEY=
OPENAI_TRANSCRIBE_MODEL=gpt-transcribe
```

### 转写走哪条：两个引擎，实测差 10 倍

| 引擎 | 协议 | 3.8 秒英文音频 | 什么时候用 |
|---|---|---|---|
| `http`（默认） | `POST {QWEN_BASE_URL}/chat/completions`，`model=qwen3-asr-flash` + `input_audio` | **601 ms** | 一次性请求/响应（我们就是这种） |
| `realtime` | `wss://…/api-ws/v1/realtime?model=qwen3-asr-flash-realtime`，官方 realtime 事件流 | **5945 ms** | 需要「边说边出字幕」时 |

realtime 慢不是实现问题：它按音频时长**逐帧推**，服务端要按时间轴对齐才出终稿，
3.8 秒语音天然就是 4~6 秒。我们的 `/transcribe` 只要最终文本、中间结果全丢掉，
所以默认 `http`。**两个引擎互相兜底**：一方失败会自动试另一方，配置写错不会导致不可用。

官方 realtime 协议要点（实现见 `_qwen_asr_realtime`，字段名很坑，记一下）：

```
session.update{modalities:[text], input_audio_format:pcm, sample_rate:16000,
               input_audio_transcription{language}, turn_detection:None}
→ 等 session.updated（不等就发音频会被丢）
→ input_audio_buffer.append(base64 PCM)，每包 3200 字节 = 0.1 秒
→ input_audio_buffer.commit（turn_detection=None 时必须手动 commit）
→ conversation.item.input_audio_transcription.text      中间结果：文本在 stash，text 字段是空的
→ conversation.item.input_audio_transcription.completed 终稿：文本在 transcript
```

音频统一先转 **16k 单声道 wav/PCM**（浏览器给 webm/opus、安卓大概率 m4a），
两条引擎都靠这一步，也是 ffmpeg 必需的原因。

`config.py` 读的是**挂载进来的 `/app/.env` 文件本身**，不是 compose 的 `environment`。
所以改 `.env` 之后要 `--force-recreate`（`docker start` 不会重读，`docker compose up -d`
在配置没变时也不会重建）。

## NAS 迁移步骤

```bash
# 1) 先把新代码传上去（老容器删掉之后，8092 上得有 web/ 和 english.py 顶上）
NAS_PW='<口令>' python tools/deploy_nas.py

# 2) 看一眼现状：两个容器在不在、老容器有哪些 key、.env 缺什么
NAS_PW='<口令>' python tools/merge_english_nas.py --check

# 3) 执行：搬 key → 停并删老容器 → 重建 nuo-study（带 ffmpeg）→ 四项验证
NAS_PW='<口令>' python tools/merge_english_nas.py
```

脚本是幂等的，跑第二遍只会重建 + 验证。它**不动**老项目的代码目录和数据目录，
只删容器（镜像 `nuonuo-english:latest` 也留着，想回退还能起）。

⚠️ 老容器如果是某个 compose 项目管的，脚本删完容器会把它对应的编排文件路径打出来 ——
**那一整段 service 定义要人手工删掉**，否则下次 `docker compose up` 会把它建回来，
于是 4173 又活了、又开始和新服务抢流量。

## 行为差异（会碰到的只有这四条）

1. **TTS 缓存会重新生成一次。** 缓存键还是 `sha256(voice|text)`，但缓存目录换成了
   `data/tts`，所以老缓存里的 mp3 不会被复用。代价是每句话第一次播多等 300–800ms，
   之后就一样了。要不要迁老缓存都行 —— 文件本身没坏，两个目录里的哈希名是一样的。
2. **`/api/chat` 在 TTS 失败时不再整单报错。** 老服务里没配 `AZURE_SPEECH_KEY` 的话，
   连对话都拿不到（因为它内部一定要合成语音）；现在会返回文本 + 空 `audioUrl`，
   孩子至少能看到英文回复。网页那边的 `new Audio("").play().catch()` 会静默吞掉。
3. **多了两个运行依赖**：`websockets`（连智聆）和 `ffmpeg`（webm→16k PCM/wav）。
   两者都写进了 `deploy/Dockerfile`，改完后必须 `--build` 一次；
   裸进程部署则要自己 `pip install -r requirements.txt` + 装 ffmpeg。
   缺了不会导致服务起不来：`/api/health` 会如实报 `"ffmpeg": false`，
   只有跟读评测和转写报错。
4. **转写引擎换成了千问**（2026-10-23）。老 Node 服务只实现了 OpenAI 那条路，
   而 `.env` 里配的却是 `QWEN_ASR_MODEL` —— 配置和代码各说各话，所以
   `transcription` 长期是 false。现在：**有 `QWEN_API_KEY` 就走千问 ASR**
   （默认 `QWEN_ASR_ENGINE=http`，见上一节的两引擎对比），没有才退回 OpenAI。
   `/api/health` 新增 `transcribeEngine`（如 `qwen-http:qwen3-asr-flash`）
   就是为了一眼看清「到底走的哪条」，不用再猜。

## TTS 延迟：4 秒 → 6 毫秒（2026-10-23）

微软合成的**冷调用**实测 **4.1 秒**（NAS → eastasia），而命中磁盘缓存只要 **6~7ms**。
孩子点到一句没听过的课文就要干等 4 秒 —— 这就是「微软延时很高」的来源。

教材内容是**有限且已知**的，所以不该在线等：

```bash
python tools/warm_tts.py        # 493 条（课文行 / 句型 / 单词 / 例句）一次全部合成
```

实测：首次 180 秒跑完 493 条；**再跑一次只要 6.2 秒**（492 条命中缓存）。
之后孩子点任何一句都是 6ms。

⚠️ 改了音色 / 语速 / 音调 / 输出格式必须**重跑** —— 缓存键把这四项都算进去了
（见 `server/english.py` 的 `azure_tts`）。不重跑就全是冷合成，而且因为没有日志，
看起来像「改了参数没生效」。

顺带把输出格式从 `96kbps` 降到 `48kbps`：24kHz 的语音听不出差别，文件小一半
（同一句 45KB → 18KB），MediaPlayer 起播更快。

## 没做的事（别误会成「漏了」）

- **英语网页没有登录**：跟老服务一样是匿名可访问。
- **没有把两个项目的 git 历史合并**：`D:\nuonuo_study` 那个项目原地不动，
  当成历史存档；新改动只在本项目里。
