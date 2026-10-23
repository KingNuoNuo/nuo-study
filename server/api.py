# -*- coding: utf-8 -*-
"""HTTP 接口层（FastAPI）。

路径前缀 `/api/v1`，与 docs/api/openapi.yaml 一一对应。
鉴权：`Authorization: Bearer <FAMILY_TOKEN>`（单家庭私有部署，不用 JWT）。
响应信封：成功 `{code:0, message:"ok", data, traceId}`；失败 `{code, message, traceId}`。

⚠️ **英语那组接口是唯一的例外**（`/api/v1/english/*`，见 server/english.py）：
   它们沿用原来那个 Node 服务的裸 JSON —— 成功直接是业务对象、失败是 `{"error":...}`，
   既没有信封也不走家庭令牌。原因是英语网页 `web/english/app.js` 是编译产物，
   读的就是这两个键。别为了「统一」去改它们的形状，改坏的是一整页功能。

本层**只排队、不识别**。上传接口立刻返回 jobId，识别由 worker.py 后台消费——
HTTP 请求里绝不能同步等模型（实测单次 6–60s，还会撞 429）。

⚠️ 数据库连接**在端点数体内**用 `db.thread_conn()` 取，不要写成依赖注入。踩过的坑：
   FastAPI 会把同步依赖和同步端点分别丢进 anyio 线程池，**不保证同一个线程**，
   于是连接跨线程使用直接抛 ProgrammingError。详见 db.thread_conn()。
"""
from __future__ import annotations

import json
import sqlite3
import uuid
from typing import Any

from fastapi import Depends, FastAPI, File, Header, Query, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import config, curriculum, db, english, english_progress, events, mirror, queue, store
from .db import tx

OK = {"code": 0, "message": "ok"}


def ok(data: Any = None) -> dict:
    d = dict(OK)
    d["data"] = data
    d["traceId"] = uuid.uuid4().hex
    return d


class ApiError(Exception):
    def __init__(self, code: int, message: str, http: int = 400):
        self.code, self.message, self.http = code, message, http


# ---------------------------------------------------------------- 鉴权
def auth(request: Request, authorization: str | None = Header(default=None)) -> str:
    """校验 Bearer Token。返回设备标识（请求头 X-Device-Id，缺省 dev-parent-01）。"""
    token = ""
    if authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
    if not config.FAMILY_TOKEN:
        raise ApiError(500001, "服务端未配置 FAMILY_TOKEN", 500)
    if token != config.FAMILY_TOKEN:
        raise ApiError(401001, "家庭令牌无效", 401)
    return request.headers.get("X-Device-Id") or "dev-parent-01"


def con() -> sqlite3.Connection:
    """在**端点数体内**调用，取当前线程独占的连接。"""
    return db.thread_conn()


# ---------------------------------------------------------------- 请求体
class DeviceIn(BaseModel):
    deviceId: str
    label: str | None = None
    role: int = 1
    formFactor: str = "tablet"
    platform: str = "android"
    pushToken: str | None = None


class FamilyIn(BaseModel):
    name: str | None = None
    timezone: str | None = None
    note: str | None = None


class ItemPatch(BaseModel):
    id: int | None = None
    seq: int | None = None
    subject: int | None = None
    title: str | None = None
    requirement: str | None = None
    itemType: int | None = None
    answerMode: int | None = None
    estMinutes: int | None = None


class AssignmentPatch(BaseModel):
    assignDate: str | None = None
    title: str | None = None
    needSubmit: bool | None = None
    items: list[ItemPatch] | None = None


class CorrectionIn(BaseModel):
    fieldPath: str = Field(..., description="如 items[2].subject")
    beforeValue: str | None = None
    afterValue: str | None = None


# ---------------------------------------------------------------- 应用
app = FastAPI(title="小学学习 App · 服务端",
              version="0.2.0",
              description="单家庭私有部署：上传 → 排队 → 识别 → 草稿 → 家长确认 → 发布。"
                          "（M2 作答 / 批改 / 学情 已暂缓，见 docs/server-design.md）")


@app.exception_handler(ApiError)
async def _api_error(request: Request, exc: ApiError):
    return JSONResponse(status_code=exc.http,
                        content={"code": exc.code, "message": exc.message,
                                 "traceId": uuid.uuid4().hex})


@app.exception_handler(store.NotFound)
async def _not_found(request: Request, exc: store.NotFound):
    return JSONResponse(status_code=404,
                        content={"code": 404001, "message": str(exc),
                                 "traceId": uuid.uuid4().hex})


@app.exception_handler(store.ConflictError)
async def _conflict(request: Request, exc: store.ConflictError):
    return JSONResponse(status_code=409,
                        content={"code": 409001, "message": str(exc),
                                 "traceId": uuid.uuid4().hex})


# 英语能力（TTS / 转写 / 对话 / 评测）的报错形状**必须**是 {"error": ...}：
# 网页前端是编译产物，读的就是这个键（见 english.py 文件头）。
# 不能让它走 FastAPI 默认的 {"detail": ...}，否则前端只会弹一句「请求失败 (400)」。
@app.exception_handler(english.EnglishError)
async def _english_error(request: Request, exc: english.EnglishError):
    return JSONResponse(status_code=exc.http, content={"error": exc.message})


# 课程文件坏了要明确报出来（而不是 500 裸栈）：网页那边会显示「教材数据读取失败」。
@app.exception_handler(curriculum.CurriculumError)
async def _curriculum_error(request: Request, exc: curriculum.CurriculumError):
    return JSONResponse(status_code=500, content={"error": str(exc)})


# ---------------------------------------------------------------- 英语（融合进来的那一半）
# 同一个 router 注册两次，这是**有意**的：
#   · `/api/v1/english/*` 是规范路径，安卓端和以后的新代码用它；
#   · `/api/*`（tts/chat/evaluate/transcribe/health）是网页前端写死的路径，
#     那前端是编译产物、改不动，兼容它比改前端便宜得多。
app.include_router(english.router, prefix="/api/v1/english", tags=["english"])
app.include_router(english.router, prefix="/api", include_in_schema=False)
# 教材内容（单元/单词/课文/练习）。**只挂规范路径** —— 网页是新的，没有历史包袱。
app.include_router(curriculum.router, prefix="/api/v1/english", tags=["english"])

# 英语学习进度（网页 / 平板 / 家长端共用的那一份学情）。
# 只挂规范路径：三端都是我们自己改的代码，没有历史包袱要兼容老路径。
app.include_router(english_progress.router, prefix="/api/v1/english", tags=["english"])
# TTS 缓存文件：老前端的 url 就是这个前缀，**不能**跟着挪到 /api 下面。
app.include_router(english.media_router, include_in_schema=False)


# ---------------------------------------------------------------- 系统
@app.get("/api/v1/system/ping")
def ping():
    """连通性探测。**不需要鉴权** —— App 首次配置服务端地址时调用。"""
    return ok({"service": "primary-study-worker", "version": "0.1.0",
               "model": config.VISION_MODEL, "promptVersion": config.PROMPT_VERSION})


@app.get("/api/v1/system/health")
def health(device: str = Depends(auth)):
    c = con()
    return ok({"db": str(config.DB_FILE), "schemaVersion": db.db_version(c),
               "queue": queue.stats(c),
               "cacheCount": c.execute("SELECT COUNT(*) FROM recognition_cache").fetchone()[0],
               "draftCount": c.execute(
                   "SELECT COUNT(*) FROM assignment WHERE status=0").fetchone()[0],
               "publishedCount": c.execute(
                   "SELECT COUNT(*) FROM assignment WHERE status=1").fetchone()[0]})


# ---------------------------------------------------------------- 家庭与设备
@app.get("/api/v1/family")
def get_family(device: str = Depends(auth)):
    return ok(dict(con().execute("SELECT * FROM family WHERE id=1").fetchone()))


@app.put("/api/v1/family")
def put_family(body: FamilyIn, device: str = Depends(auth)):
    c = con()
    f = dict(c.execute("SELECT * FROM family WHERE id=1").fetchone())
    for k in ("name", "timezone", "note"):
        if getattr(body, k) is not None:
            f[k] = getattr(body, k)
    with tx(c):
        c.execute("UPDATE family SET name=?, timezone=?, note=? WHERE id=1",
                  (f["name"], f["timezone"], f["note"]))
    return ok(dict(c.execute("SELECT * FROM family WHERE id=1").fetchone()))


@app.get("/api/v1/devices")
def list_devices(device: str = Depends(auth)):
    # camelize：设备表列名是 snake_case（device_id / form_factor / last_sync_at），
    # 而契约声明 camelCase，必须在出口转换
    return ok(store.camelize([dict(r) for r in con().execute(
        "SELECT * FROM device ORDER BY id")]))


@app.post("/api/v1/devices")
def upsert_device(body: DeviceIn, device: str = Depends(auth)):
    c = con()
    with tx(c):
        c.execute(
            """INSERT INTO device (device_id, label, role, platform, form_factor, push_token)
               VALUES (?,?,?,?,?,?)
               ON CONFLICT (device_id) DO UPDATE SET
                 label=excluded.label, role=excluded.role, platform=excluded.platform,
                 form_factor=excluded.form_factor, push_token=excluded.push_token""",
            (body.deviceId, body.label, body.role, body.platform,
             body.formFactor, body.pushToken))
    return ok(store.camelize(dict(c.execute(
        "SELECT * FROM device WHERE device_id=?", (body.deviceId,)).fetchone())))


@app.get("/api/v1/students")
def list_students(device: str = Depends(auth)):
    # 同上：textbook_ver / avatar_url / daily_limit_min 需要转成契约里的 camelCase
    return ok(store.camelize([dict(r) for r in con().execute(
        "SELECT * FROM student ORDER BY sort_no, id")]))


# ---------------------------------------------------------------- 截图上传与识别

async def _read_capped(file: UploadFile, limit: int) -> bytes:
    """分块读上传体，一旦超限立刻中断。

    ⚠️ 不要写回 `raw = await file.read()`。那是先把**整个请求体**读进内存，
       下面那句 `len(raw) > MAX_UPLOAD_BYTES` 才做判断 —— 也就是说
       大小校验**挡不住内存占用**：一个几 GB 的 POST 足以把服务端 OOM 掉，
       而代码看起来「明明检查过大小」。分块读、边读边累加才是真的生效。
    """
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await file.read(64 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > limit:
            raise ApiError(400001, "文件超过 %d MB" % (limit // 1048576))
        chunks.append(chunk)
    return b"".join(chunks)


@app.post("/api/v1/captures")
async def upload_capture(file: UploadFile = File(...),
                         studentId: int = Query(default=1),
                         device: str = Depends(auth)):
    """上传作业截图 → 落盘去重 → 立即入队 → 返回 jobId（不在这里等识别）。

    客户端需先把图片压到长边 1600px（契约里写死），服务端只做兜底校验。
    """
    c = con()
    raw = await _read_capped(file, config.MAX_UPLOAD_BYTES)
    if not raw:
        raise ApiError(400001, "空文件")

    res = store.upsert_capture(c, studentId, device, raw, file.filename or "upload.jpg")
    job_id = queue.enqueue(c, res["capture_id"], studentId)
    return ok({"captureId": res["capture_id"], "jobId": job_id,
               "imageHash": res["image_hash"], "storageKey": res["storage_key"],
               "dedup": res["dedup"],
               "note": "识别已入队，轮询 GET /recognition-jobs/{jobId}"})


@app.post("/api/v1/captures/{capture_id}/recognize")
def re_recognize(capture_id: int, force: bool = Query(default=False),
                 device: str = Depends(auth)):
    """重新发起识别。force=true 会清掉该图缓存（换提示词后重跑用）。"""
    c = con()
    cap = store.get_capture(c, capture_id)
    if force:
        with tx(c):
            c.execute("DELETE FROM recognition_cache WHERE image_hash=?",
                      (cap["image_hash"],))
    job_id = queue.enqueue(c, capture_id, cap["student_id"])
    return ok({"captureId": capture_id, "jobId": job_id, "cacheCleared": force})


@app.get("/api/v1/recognition-jobs/{job_id}")
def get_job(job_id: int, device: str = Depends(auth)):
    c = con()
    j = queue.get_job(c, job_id)
    if not j:
        raise store.NotFound("识别任务不存在: %s" % job_id)
    d = {"jobId": j["id"], "captureId": j["capture_id"],
         "status": j["status"], "statusName": queue.JOB_STATUS_NAME.get(j["status"]),
         "cacheHit": bool(j["cache_hit"]), "retryCount": j["retry_count"],
         "maxRetry": j["max_retry"], "error": j["error_msg"],
         "nextRetryAt": j["next_retry_at"], "tokens": j["cost_tokens"],
         "durationMs": j["duration_ms"], "createdAt": j["created_at"],
         "finishedAt": j["finished_at"]}
    if j["status"] == queue.SUCCEEDED:
        # 识别成功则把草稿与产出的作业单一并返回，家长端一次请求就能渲染确认界面。
        # ⚠️ 这里只读 job_assignment，不再重复 materialize ——
        #    重放会删除重建作业项，把 item id 搅乱，客户端拿着旧 id 就找不到项了。
        d["draft"] = json.loads(j["result"])
        d["assignments"] = store.assignments_of_job(c, j["id"])
    return ok(d)


@app.get("/api/v1/recognition-jobs")
def list_jobs(limit: int = Query(default=50, le=200), device: str = Depends(auth)):
    return ok(store.list_jobs(con(), limit))


@app.post("/api/v1/recognition-jobs/{job_id}/corrections")
def add_correction(job_id: int, body: CorrectionIn, device: str = Depends(auth)):
    """记录家长对识别结果的修正。这份数据是优化提示词的唯一依据。"""
    c = con()
    if not queue.get_job(c, job_id):
        raise store.NotFound("识别任务不存在: %s" % job_id)
    with tx(c):
        store.add_correction(c, job_id, device, body.fieldPath,
                             body.beforeValue, body.afterValue)
    return ok({"recorded": True})


# ---------------------------------------------------------------- 作业单 / 家长确认流
@app.get("/api/v1/assignments")
def list_assignments(date: str | None = Query(default=None, description="yyyy-MM-dd"),
                     status: int | None = Query(default=None, description="0草稿 1已发布"),
                     studentId: int | None = None,
                     limit: int = Query(default=50, le=200),
                     device: str = Depends(auth)):
    return ok(store.list_assignments(con(), date, status, studentId, limit))


@app.get("/api/v1/assignments/{assignment_id}")
def get_assignment(assignment_id: int, device: str = Depends(auth)):
    """作业单详情。"""
    return ok(store.get_assignment(con(), assignment_id))


@app.patch("/api/v1/assignments/{assignment_id}")
def patch_assignment(assignment_id: int, body: AssignmentPatch,
                     device: str = Depends(auth)):
    """家长修正作业单。**草稿(0) 与已发布(1) 都可改**，每处改动写进 recognition_correction。

    改完会广播一条 SSE 事件（`assignments.changed`），孩子端前台时秒级跟随；
    不在线时由孩子端的周期同步/手动刷新兜底。
    """
    data = store.patch_assignment(con(), assignment_id,
                                  body.model_dump(exclude_unset=True), device)
    events.broadcast_assignment_changed("patch", assignment_id)
    return ok(data)


@app.post("/api/v1/assignments/{assignment_id}/publish")
def publish(assignment_id: int, device: str = Depends(auth)):
    """发布作业：草稿(0) → 已发布(1)。幂等，重复调用返回当前状态。

    发布成功（含「重复调用」的幂等返回）都广播一条 SSE 事件 —— 孩子端前台时
    立刻重新拉取，不用等 6 小时周期任务，也不用孩子手动点刷新。
    """
    data = store.publish_assignment(con(), assignment_id, device)
    events.broadcast_assignment_changed("publish", assignment_id)
    return ok(data)


@app.delete("/api/v1/assignments/{assignment_id}")
def discard(assignment_id: int, device: str = Depends(auth)):
    """删除作业单。草稿与已发布都可删；已完成(2)/已归档(3) 返回 409001。

    ⚠️ 它现在不只是「丢弃草稿」了 —— 家长端的历史作业模块用它删已发布的。
       误删的防线在客户端（二次确认弹窗），服务端这里不做软删除：
       单家庭场景下软删除只会让「删了怎么还在」变成一个更难查的问题。

    删除同样广播 SSE 事件：家长删了作业，孩子端挂着的那条也要跟着消失。
    """
    store.delete_assignment(con(), assignment_id)
    events.broadcast_assignment_changed("discard", assignment_id)
    return ok({"deleted": assignment_id})


# ---------------------------------------------------------------- 作业变更事件（SSE）
@app.get("/api/v1/events")
async def assignment_events(device: str = Depends(auth)):
    """作业变更事件流（SSE）。孩子端前台时订阅，收到事件后走标准接口全量拉取。

    事件体只有 type/action/id，**不捎业务数据**（见 events.py 文件头第 1 条）；
    连接建立先回一条 hello，之后每 20s 一条注释帧保活兼探测死连接。
    长连接对鉴权的要求与普通接口一致：标准 Bearer 头（OkHttp 拦截器自动带上）。
    """
    return events.sse_response()


# ---------------------------------------------------------------- 学生端实时画面（调试）
# ⚠️ 这一组是**测试期调试功能**，不属于产品契约，docs/api/openapi.yaml 里没有它。
#    它存在的理由：家长想确认「孩子平板上到底显示成什么样」，而让孩子念给你听
#    或者让他截图发过来都不现实。
#
#    鉴权分两种，别混：
#      · POST 上传帧 —— 走标准 `Authorization: Bearer`（App 那边有 OkHttp 拦截器）；
#      · 图片与网页 —— 走 `?token=`，因为浏览器打开页面时**带不了自定义头**。
#    所以这一组不依赖 `Depends(auth)`，而是自己比一次令牌。
def _query_auth(token: str | None) -> None:
    if not config.FAMILY_TOKEN:
        raise ApiError(500001, "服务端未配置 FAMILY_TOKEN", 500)
    if (token or "") != config.FAMILY_TOKEN:
        raise ApiError(401001, "家庭令牌无效", 401)


def _check_source(source: str | None) -> str | None:
    """`source` 查询参数 → 通道名。空/auto ＝ 不限通道（谁新看谁）。"""
    if source in (None, "", "auto"):
        return None
    if source not in (mirror.SOURCE_APP, mirror.SOURCE_ADB):
        raise ApiError(400002, "source 只能是 app 或 adb")
    return source


@app.post("/api/v1/debug/frames")
async def upload_frame(file: UploadFile = File(...),
                       label: str | None = Query(default=None),
                       device: str = Depends(auth)):
    """学生端上传一帧自己的画面（`source=app` 通道）。高频调用（3 秒一次），**只写内存**。

    ⚠️ 用 `_read_capped` 而不是 `await file.read()`：后者先把整个请求体读进内存，
       `mirror.save` 里的 `MAX_FRAME_BYTES` 是**读完之后**才判 —— 大小校验挡不住内存占用。
       （与 `upload_capture` 的 P2-7 同一修法，别在这里写回全量 read。）
    """
    raw = await _read_capped(file, mirror.MAX_FRAME_BYTES)
    try:
        info = mirror.save(device, label, raw, source=mirror.SOURCE_APP)
    except ValueError as e:
        raise ApiError(400001, str(e))
    return ok(info)


@app.get("/api/v1/debug/frames/latest.jpg")
def latest_frame(deviceId: str | None = Query(default=None),
                 source: str | None = Query(default=None),
                 token: str | None = Query(default=None)):
    """取最新一帧。`?source=app` 要孩子端上传的那张，`?source=adb` 要服务端抓的整屏。

    ⚠️ adb 这一路是**按需求驱动**的：每次有人来取，就顺手 [mirror.AdbGrabber.ensure]
    点一次名（启动抓帧线程 / 续一次空闲计时）。没有任何取帧请求后线程自己退出。
    所以服务端不需要一个「开始抓屏 / 停止抓屏」的控制接口 —— 谁在看，谁就在驱动它。
    """
    _query_auth(token)
    src = _check_source(source)
    if src == mirror.SOURCE_ADB and not mirror.grabber().ensure():
        raise ApiError(
            503001,
            "服务端没有配置「整屏抓取」通道：需要在 NAS 上放好 adb，"
            "并把 .env 里的 ADB_TARGET 设成平板地址（形如 192.168.1.50:5555）。",
            503,
        )
    got = mirror.latest(deviceId, src)
    if not got:
        raise ApiError(404001, "还没有收到任何画面", 404)
    raw, meta = got
    # no-store 是必须的：浏览器缓存住这一帧，网页上就永远停在第一张图。
    # media_type 取帧自带的 mime：adb 通道在 Pillow 缺失时会退回 PNG。
    return Response(content=raw, media_type=meta.get("mime") or "image/jpeg",
                    headers={"Cache-Control": "no-store, max-age=0"})


@app.get("/api/v1/debug/frames/state")
def frames_state(token: str | None = Query(default=None)):
    """帧池状态 + adb 抓帧通道的实况（在不在跑、抓到第几张、上次错在哪）。"""
    _query_auth(token)
    return ok(mirror.state())


@app.get("/debug/screen")
def screen_page(deviceId: str | None = Query(default=None),
                source: str | None = Query(default=None),
                token: str | None = Query(default=None)):
    """给家长看的网页。手机/电脑浏览器直接打开即可，不需要装任何东西。

    地址形如：`http://192.168.1.10:8092/debug/screen?token=<FAMILY_TOKEN>`
    只看某一台设备时再追加 `&deviceId=<deviceId>`；页面上可就地切换截图来源。
    """
    _query_auth(token)
    return HTMLResponse(mirror.page_html(token or "", deviceId, source))


# ---------------------------------------------------------------- 英语网页（融合进来的那一半）
# 原先是 Node 服务自己 `serveFile` 出去的静态站点，现在由本服务直接挂。
#
# ⚠️ 目录不存在时**不挂**（而不是启动就崩）：`web/english` 是要跟着代码一起部署的，
#    但「没同步上去」属于很常见的部署疏漏，这时候整个学业服务不该陪葬 ——
#    该报的是「英语网页没部署」，不是「服务起不来」。
if config.ENGLISH_WEB_DIR.is_dir():
    app.mount("/english", StaticFiles(directory=str(config.ENGLISH_WEB_DIR), html=True),
              name="english-web")
    # 网页里的音频写的是相对路径 `audio/track-01.mp3`（在 /english/ 下自然成立）；
    # 安卓端历史版本用的是 `$SERVER/audio/track-01.mp3`，所以这里再挂一份到根路径。
    _audio_dir = config.ENGLISH_WEB_DIR / "audio"
    if _audio_dir.is_dir():
        app.mount("/audio", StaticFiles(directory=str(_audio_dir)), name="english-audio")


@app.middleware("http")
async def english_no_cache(request: Request, call_next):
    """英语网页的 html/js/css/json 一律 `no-cache`（要回源验证，但允许 304）。

    ⚠️ 2026-10-23 踩到的真实故障：改了 `app.js`（新增单元切换、课文点读），
       服务端明明是新文件，**家长浏览器里还是旧的** —— 因为 StaticFiles 会给
       `app.js` 发 `ETag/Last-Modified` 且默认不禁止缓存，而 `<script src="app.js">`
       没有版本号，浏览器就直接用本地缓存了。表现是「页面能开，但什么单元内容都没有」，
       看着像后端没数据，实际是新 HTML 配旧 JS：新 HTML 里已经没有旧 JS 要找的元素，
       旧 JS 一报错就整页不再渲染。
    结论：SPA 的入口文件不能有长缓存。这里只对 html/js/css/json 生效，
    课文音频（mp3）保持默认缓存 —— 那批文件不会再变，长缓存反而是对的。
    """
    response = await call_next(request)
    path = request.url.path
    if path == "/" or path.startswith("/english"):
        name = path.rsplit("/", 1)[-1]
        ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        if ext in ("html", "js", "css", "json") or not ext:
            response.headers["Cache-Control"] = "no-cache, must-revalidate"
    return response


@app.get("/", include_in_schema=False)
def home():
    """一个服务的根路径只能有一个落点，这里给英语网页。

    学业部分本来就没有网页界面（家长端／孩子端都是 App），所以根路径空着也是空着。
    """
    if config.ENGLISH_WEB_DIR.is_dir():
        return RedirectResponse("/english/", status_code=307)
    return RedirectResponse("/docs", status_code=307)


# ---------------------------------------------------------------- M2（已暂缓）
# 作答 / 练习 / 错题本 / 学情报告 的 8 个端点已整体摘除，路由不再注册。
# 实现完整保留在 server/study.py（派生逻辑）与 server/grader.py（判定），
# 未接入即可视为「不存在」；将来恢复时把端点函数接回来即可。
#
# 暂缓的原因：识别产出的作业项默认没有 question_id，也就没有标准答案，
# 自动批改的实际覆盖率天然很低。在家里自己用的场景下，先把
# 「作业识别 → 家长确认 → 孩子看到作业」这条主干跑顺，比批改更值。
