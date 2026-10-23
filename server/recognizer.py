# -*- coding: utf-8 -*-
"""多模态识别核心：提示词加载、图片编码、调用模型、解析 JSON。

从最初的离线校验脚本 tools/recognize.py 抽出来，服务端与离线工具共用同一份实现，
避免「线上跑的和验证过的不是同一套代码」。

⚠️ 关于 sensenova-6.8-flash-lite 的三个实测结论（详见 docs/recognition-eval.md）：
   1. 它是推理模型，思考与正文**共享 max_tokens** → 给少了会返回**空正文但 HTTP 200**
   2. 只有 reasoning_effort="none" 关得掉思考；enable_thinking=false 会被静默忽略
   3. 会撞 429 tpm/rpm limit，必须退避重试
"""
from __future__ import annotations

import base64
import json
import mimetypes
import re
import ssl
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path

from . import config

# data URI 的**硬**上限。超过它就不是「稍微大一点」，而是客户端压根没压图 ——
# 这种请求发上去只会拿到一个 413，不如在本地就报清楚原因。
HARD_MAX_BYTES = 4_000_000

# ---------------------------------------------------------------- 枚举映射
# 模型输出语义字符串，代码映射成整数 —— 契约见 db/schema.sql
SUBJECT_MAP = {"语文": 1, "数学": 2, "英语": 3, "其他": 9}
ITEM_TYPE_MAP = {"客观题": 1, "主观题": 2, "听读打卡": 3, "线下实践": 4}
ANSWER_MODE_MAP = {"无": 0, "键盘": 1, "手写": 2, "录音": 3, "拍照": 4}
SUBJECT_NAME = {v: k for k, v in SUBJECT_MAP.items()}
ITEM_TYPE_NAME = {v: k for k, v in ITEM_TYPE_MAP.items()}
ANSWER_MODE_NAME = {v: k for k, v in ANSWER_MODE_MAP.items()}
WEEKDAY_CN = ["一", "二", "三", "四", "五", "六", "日"]


def coerce_enum(value, mapping: dict, default=None):
    """容忍三种写法：整数、中文语义串、带序号的串。"""
    if value is None:
        return default
    if isinstance(value, int):
        return value if value in mapping.values() else default
    v = str(value).strip()
    if v in mapping:
        return mapping[v]
    if v.isdigit():
        n = int(v)
        return n if n in mapping.values() else default
    for k, n in mapping.items():
        if k in v:
            return n
    return default


class RecognizeError(Exception):
    """识别失败。retryable 决定队列是否重排。"""

    def __init__(self, message: str, retryable: bool = False,
                 retry_after: int | None = None):
        super().__init__(message)
        self.retryable = retryable
        self.retry_after = retry_after


def load_prompt(path: Path | None = None) -> tuple[str, str]:
    """从提示词文档切出 System / User 两段，保证文档与代码同源。

    ⚠️ 带缓存：这个方法在**每一次识别**里都会被调（call_model → build_payload），
       每调一次就是一次同步文件 IO + 字符串切分。而提示词文件在一个进程生命周期里
       不会变（要改就重启服务，顺便让 prompt_version 一起生效）。
       `maxsize=4` 是为了照顾离线工具用别的路径调它。
    """
    return _load_prompt_cached(path)


@lru_cache(maxsize=4)
def _load_prompt_cached(path: Path | None) -> tuple[str, str]:
    txt = (path or config.PROMPT_FILE).read_text(encoding="utf-8")
    if "## System" not in txt or "## User" not in txt:
        raise RecognizeError("提示词文件缺少 '## System' 或 '## User' 分节")
    sys_part = txt.split("## System", 1)[1].split("## User", 1)[0].strip()
    user_part = txt.split("## User", 1)[1].strip()
    return sys_part, user_part


def build_opener(use_proxy: bool = True):
    handlers = [urllib.request.HTTPSHandler(context=ssl.create_default_context())]
    if not use_proxy:
        handlers.append(urllib.request.ProxyHandler({}))
    return urllib.request.build_opener(*handlers)


def data_uri(raw: bytes, filename: str = "img.jpg",
             max_bytes: int = 1_500_000) -> str:
    """转 base64 data URI。超过阈值尝试等比压缩（需 Pillow，缺则原样上传）。

    ⚠️ 压缩之后必须**重新量一次**。早先只压不量：压完仍然超标的图会被原样拼成
       一个几 MB 的 data URI 发上去 —— 上游回 413，而 413 这种报错根本看不出
       「是这张图太大」。这里补一道硬上限，越线就明确指出该怎么改。

    为什么不是「压完超过 max_bytes 就拒」：max_bytes 是**客户端约定**的目标值
       （长边 1600px），不是协议硬限制。略微超出（1.6 MB 之类）多半还能正常识别，
       为了这点余量把一张图整单拒掉不划算。真正必须拦的是失控的量级。
    """
    if len(raw) > max_bytes:
        try:
            import io

            from PIL import Image  # type: ignore

            im = Image.open(io.BytesIO(raw)).convert("RGB")
            w, h = im.size
            scale = (max_bytes / len(raw)) ** 0.5
            im = im.resize((max(1, int(w * scale)), max(1, int(h * scale))))
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=85, optimize=True)
            raw = buf.getvalue()
        except ImportError:
            # 这台机器上没有 Pillow —— 环境降级，原样上传，**刻意不拦**：
            # 拦下来等于「整台服务端一张图都识别不了」，比发出去被上游拒绝更糟。
            pass
        except Exception as e:
            # 解不开（不是图片 / 文件损坏）→ 明确报出来，不要把一个坏文件发上去。
            raise RecognizeError("图片解码失败（%s: %s）—— 这个文件可能不是有效图片"
                                 % (type(e).__name__, e), retryable=False)

    if len(raw) > HARD_MAX_BYTES:
        raise RecognizeError(
            "图片压不下去：%d KB（硬上限 %d KB）。客户端应先压到长边 1600px 再上传。"
            % (len(raw) // 1024, HARD_MAX_BYTES // 1024), retryable=False)

    mime = mimetypes.guess_type(filename)[0] or "image/jpeg"
    if mime == "image/jpg":
        mime = "image/jpeg"
    return "data:%s;base64,%s" % (mime, base64.b64encode(raw).decode("ascii"))


def build_payload(image_raw: bytes, filename: str, system: str, user_tpl: str,
                  think: str = "auto") -> dict:
    now = datetime.now(timezone(timedelta(hours=8)))
    user = (user_tpl
            .replace("{now}", now.strftime("%Y-%m-%d %H:%M"))
            .replace("{weekday}", WEEKDAY_CN[now.weekday()])
            .replace("{timezone}", "Asia/Shanghai"))
    payload = {
        "model": config.VISION_MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": [
                {"type": "text", "text": user},
                {"type": "image_url",
                 "image_url": {"url": data_uri(image_raw, filename)}},
            ]},
        ],
        "max_tokens": config.VISION_MAX_TOKENS,
        "temperature": 0,
    }
    if think == "off":
        payload["reasoning_effort"] = "none"
    elif think == "on":
        payload["reasoning_effort"] = "high"
    return payload


def call_model(image_raw: bytes, filename: str, think: str | None = None,
               opener=None) -> dict:
    """调用一次模型。**不做重试**——重试由队列负责（这样进程重启也不丢重试次数）。"""
    if not config.VISION_API_KEY:
        raise RecognizeError("缺少 VISION_API_KEY，检查 .env", retryable=False)
    think = think or config.VISION_THINKING
    payload = build_payload(image_raw, filename, *load_prompt(), think=think)
    req = urllib.request.Request(
        config.VISION_BASE_URL + "/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": "Bearer " + config.VISION_API_KEY,
                 "Content-Type": "application/json"},
        method="POST")
    op = opener or build_opener()
    t0 = time.time()
    try:
        with op.open(req, timeout=config.VISION_TIMEOUT) as r:
            j = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        if e.code == 429:
            raise RecognizeError("429 限流: %s" % body[:200], retryable=True, retry_after=30)
        if e.code >= 500:
            raise RecognizeError("HTTP %s: %s" % (e.code, body[:200]), retryable=True)
        raise RecognizeError("HTTP %s: %s" % (e.code, body[:200]), retryable=False)
    except Exception as e:
        # 超时/连接重置 → 可重试
        raise RecognizeError("%s: %s" % (type(e).__name__, e), retryable=True)

    dt = time.time() - t0
    # ⚠️ 别写 j["choices"][0]。网关在 200 里回错误体是真实发生过的
    #    （`{"error": {...}}`，没有 choices 键），那时 KeyError 会一路冒到队列层，
    #    报出来是一句和识别毫无关系的堆栈，看不出「模型这次根本没回内容」。
    choices = j.get("choices") or []
    if not choices:
        err = j.get("error")
        detail = err.get("message") if isinstance(err, dict) else None
        raise RecognizeError(
            "模型响应里没有 choices：%s"
            % (detail or json.dumps(j, ensure_ascii=False)[:200]),
            retryable=True)
    choice = choices[0] or {}
    msg = choice.get("message") or {}
    usage = j.get("usage") or {}
    return {
        "content": msg.get("content") or "",
        "reasoning": msg.get("reasoning") or "",
        "finish_reason": choice.get("finish_reason"),
        "usage": usage,
        "latency_s": round(dt, 2),
        "upload_bytes": len(image_raw),
        "request_id": j.get("request_id"),
    }


def parse_json_block(text: str) -> tuple[dict | None, str | None]:
    """从模型输出里抠出 JSON。容忍 ```json 围栏与前后废话。"""
    if not text or not text.strip():
        return None, "模型 content 为空（常见原因：思考吃光 max_tokens）"
    s = text.strip()
    m = re.search(r"```(?:json)?\s*(.+?)\s*```", s, re.S)
    if m:
        s = m.group(1).strip()
    i, k = s.find("{"), s.rfind("}")
    if i < 0 or k < 0 or k < i:
        return None, "输出里找不到 JSON 对象"
    try:
        return json.loads(s[i:k + 1]), None
    except json.JSONDecodeError as e:
        return None, "JSON 解析失败: %s" % e


def recognize(image_raw: bytes, filename: str, think: str | None = None,
              opener=None) -> tuple[dict, dict]:
    """一次完整识别：调用 + 解析。返回 (parsed, meta)。解析失败抛 RecognizeError。

    解析失败**不重试**——重试同一个提示词大概率还是失败，应该升 prompt_version。
    """
    meta = call_model(image_raw, filename, think=think, opener=opener)
    parsed, err = parse_json_block(meta["content"])
    if not parsed and meta.get("reasoning"):
        parsed, err = parse_json_block(meta["reasoning"])
    if not parsed:
        raise RecognizeError("解析失败: %s (finish=%s)"
                             % (err, meta.get("finish_reason")), retryable=False)
    return parsed, meta
