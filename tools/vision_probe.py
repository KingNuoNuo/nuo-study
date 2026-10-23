#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
多模态接入探测（只读，不改数据）
目的：在写正式识别链路之前，把三件不确定的事问清楚：
  1. 端点通不通、鉴权对不对（/models）
  2. 模型是否真的接受 image_url（有些模型标 enabled 但纯图请求 400）
  3. 图片是怎么传的 —— base64 data URI 还是需要先上传拿 URL
  4. 返回体里 usage 有没有，决定能不能算成本

用法：
  python vision_probe.py                 # 走系统代理（HTTPS_PROXY）
  python vision_probe.py --no-proxy      # 绕过代理直连
  python vision_probe.py --image <path>  # 指定探测用的图片
"""
import argparse
import base64
import json
import mimetypes
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_env(path: Path) -> dict:
    """极简 dotenv：只支持 KEY=VALUE 和 # 注释，够用就行。"""
    env = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip()
    return env


def build_opener(use_proxy: bool):
    ctx = ssl.create_default_context()
    handlers = [urllib.request.HTTPSHandler(context=ctx)]
    if not use_proxy:
        handlers.append(urllib.request.ProxyHandler({}))
    return urllib.request.build_opener(*handlers)


def call(opener, url, payload, api_key, timeout, method="POST"):
    data = None
    headers = {
        "Authorization": "Bearer " + api_key,
        "User-Agent": "primary-study-probe/1.0",
    }
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    t0 = time.time()
    try:
        with opener.open(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, body, time.time() - t0, None
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        return e.code, body, time.time() - t0, None
    except Exception as e:  # 网络层异常（代理拒绝、DNS、超时）
        return None, "", time.time() - t0, "%s: %s" % (type(e).__name__, e)


def data_uri(path: Path) -> str:
    mime = mimetypes.guess_type(path.name)[0] or "image/jpeg"
    if mime == "image/jpg":
        mime = "image/jpeg"
    b64 = base64.b64encode(path.read_bytes()).decode("ascii")
    return "data:%s;base64,%s" % (mime, b64)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-proxy", action="store_true")
    ap.add_argument("--image", default=None)
    args = ap.parse_args()

    env = load_env(ROOT / ".env")
    base = env.get("VISION_BASE_URL", "").rstrip("/")
    key = env.get("VISION_API_KEY", "")
    model = env.get("VISION_MODEL", "")
    timeout = int(env.get("VISION_TIMEOUT", "120"))

    print("=" * 68)
    print("base_url :", base)
    print("model    :", model)
    print("api_key  : %s…%s (%d chars)" % (key[:6], key[-4:], len(key)))
    proxied = [k for k in ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY") if os.environ.get(k)]
    print("proxy    :", ("绕过" if args.no_proxy else "使用") + " " + str(proxied or "(无环境代理)"))
    print("=" * 68)

    opener = build_opener(use_proxy=not args.no_proxy)

    # ---- 探测 1：模型清单 ------------------------------------------------
    print("\n[1] GET /models")
    st, body, dt, err = call(opener, base + "/models", None, key, timeout, method="GET")
    if err:
        print("    网络失败 %s (%.1fs)" % (err, dt))
    else:
        print("    HTTP %s (%.1fs)" % (st, dt))
        try:
            j = json.loads(body)
            ids = [m.get("id") for m in j.get("data", [])]
            print("    模型数:", len(ids))
            for i in ids:
                mark = "  <== 目标" if i == model else ""
                print("      -", i, mark)
        except Exception:
            print("    原始:", body[:400])

    # ---- 探测 2：纯文本对话 ----------------------------------------------
    print("\n[2] POST /chat/completions  (纯文本，验证鉴权与协议)")
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": "回复两个字：通了"}],
        "max_tokens": 16,
        "temperature": 0,
    }
    st, body, dt, err = call(opener, base + "/chat/completions", payload, key, timeout)
    if err:
        print("    网络失败 %s (%.1fs)" % (err, dt))
    else:
        print("    HTTP %s (%.1fs)" % (st, dt))
        try:
            j = json.loads(body)
            msg = j["choices"][0]["message"]["content"]
            print("    回复:", msg.strip()[:80])
            print("    usage:", j.get("usage"))
        except Exception:
            print("    原始:", body[:600])

    # ---- 探测 3：图片输入（关键） ----------------------------------------
    img = Path(args.image) if args.image else None
    if img is None:
        cands = sorted((Path.home() / ".workbuddy" / "clipboard-images").glob("clipboard-2026-10-09*.jpg"))
        img = cands[0] if cands else None
    if img is None or not img.exists():
        print("\n[3] 跳过图片探测：找不到图片")
        return

    print("\n[3] POST /chat/completions  (图片输入，验证是否真支持视觉)")
    print("    图片:", img.name, "%.0f KB" % (img.stat().st_size / 1024))
    uri = data_uri(img)
    print("    data URI 长度: %.0f KB (base64 膨胀约 33%%)" % (len(uri) / 1024))
    payload = {
        "model": model,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": "这张图里主要是什么内容？用一句话回答。"},
                {"type": "image_url", "image_url": {"url": uri}},
            ],
        }],
        "max_tokens": 200,
        "temperature": 0,
    }
    st, body, dt, err = call(opener, base + "/chat/completions", payload, key, timeout)
    if err:
        print("    网络失败 %s (%.1fs)" % (err, dt))
        return
    print("    HTTP %s (%.1fs)" % (st, dt))
    try:
        j = json.loads(body)
        if "choices" in j:
            print("    ✅ 视觉可用")
            print("    回复:", j["choices"][0]["message"]["content"].strip()[:300])
            print("    usage:", j.get("usage"))
            print("    注: 传入 %.0f KB / 输出 token=%s" % (
                len(uri) / 1024, (j.get("usage") or {}).get("completion_tokens")))
        else:
            print("    ❌ 视觉不可用，服务端返回:")
            print("   ", json.dumps(j, ensure_ascii=False)[:600])
    except Exception:
        print("    原始:", body[:600])


if __name__ == "__main__":
    main()
