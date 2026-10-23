# -*- coding: utf-8 -*-
"""教材逐页「读图 → 结构化 JSON」。整个课程数据的第 0 步。

每页一次多模态调用（网关的 qwen3.8-flash 支持图片输入），只要严格 JSON；
失败重试 2 次；一页一个文件，**可断点续跑**（重跑只补缺的页）。

用法（在 NAS 上跑，key 不出 NAS）：
  python3 tools/read_pages.py <图片目录> <输出目录>

⚠️ 两个实测参数别乱动：
  · `"enable_thinking": false` —— 开着思考单页 10.1s，关了 3.7s，而且思考会挤占
    max_tokens 导致 JSON 被截断；
  · 并发 8 —— 再高会撞网关限流（4 并发时反而是 2 分钟/页，因为思考没关）。
"""
import base64
import json
import os
import pathlib
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

IMG_DIR = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/nuo_pages/img")
OUT_DIR = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "/tmp/nuo_pages/out")
ENV_FILE = "/vol2/1000/software/nuo_study/.env"
OUT_DIR.mkdir(parents=True, exist_ok=True)

ENV = {}
for line in open(ENV_FILE, encoding="utf-8"):
    s = line.strip()
    if s and not s.startswith("#") and "=" in s:
        k, v = s.split("=", 1)
        ENV[k.strip()] = v.strip().strip('"').strip("'")

KEY = ENV["QWEN_API_KEY"]
BASE = ENV["QWEN_BASE_URL"].rstrip("/")
MODEL = ENV.get("QWEN_CHAT_MODEL", "qwen-flash")

PROMPT = """你是教材数字化助手。这是江苏译林版（苏教译林）六年级上册英语教材的一页扫描图。

只输出一个 JSON 对象，不要 markdown 代码块、不要任何解释文字。格式：

{"page": 页脚页码数字或null,
 "unit": 该页所属单元号(1-8)或null,
 "section": "板块名，取值之一：Cover/Contents/Preface/Characters/Unit opener/Cartoon time/Story time/Grammar time/Fun time/Sound time/Culture time/Wrap-up time/Assessment time/Project/Appendix/Other",
 "title": "该页最主要的中文或英文标题原文",
 "text": ["逐行英文原文，一行一个元素，保留原有标点；对话写成 Mulan: ... 这种形式"],
 "words": [{"en": "本页出现的生词或新短语", "zh": "中文意思"}],
 "sentences": ["本页值得学的完整英文句子，最多 6 句"]}

硬性要求：
1. text 只放英文原文，不要翻译、不要编造图上没有的内容。
2. words 只列这一页真正出现的生词/短语，最多 10 个；确定不了中文意思就写 ""。
3. 页码看不清就填 null；板块判断不了就填 "Other"。
4. 图片模糊导致某些行看不清时，按能看清的部分写，不要补全猜测。"""


def one(path: pathlib.Path) -> None:
    out = OUT_DIR / (path.stem + ".json")
    if out.exists() and out.stat().st_size > 20:
        return
    img = base64.b64encode(path.read_bytes()).decode()
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": PROMPT},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64," + img}},
        ]}],
        "max_tokens": 2000,
        "temperature": 0,
        # 关思考：实测单页 10.1s → 3.7s，而且 JSON 更干净（思考会挤占输出预算）
        "enable_thinking": False,
    }
    data = json.dumps(body).encode()
    for attempt in range(3):
        req = urllib.request.Request(
            BASE + "/chat/completions", data=data,
            headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"},
            method="POST")
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                j = json.loads(r.read().decode())
            text = j["choices"][0]["message"].get("content") or ""
            # 去掉可能的 ```json ``` 包裹
            t = text.strip()
            if t.startswith("```"):
                t = t.split("```")[1]
                t = t[t.find("{"):]
            start, end = t.find("{"), t.rfind("}")
            parsed = json.loads(t[start:end + 1])
            parsed["_file"] = path.name
            out.write_text(json.dumps(parsed, ensure_ascii=False), encoding="utf-8")
            print("ok   %s p%s %s" % (path.name, parsed.get("page"), parsed.get("section")),
                  flush=True)
            return
        except Exception as e:
            if attempt == 2:
                out.write_text(json.dumps({"_file": path.name, "_error": str(e)[:200]},
                                          ensure_ascii=False), encoding="utf-8")
                print("FAIL %s %s" % (path.name, type(e).__name__), flush=True)
            else:
                time.sleep(2 + attempt * 3)


files = sorted(IMG_DIR.glob("*.png"))
print("共 %d 页，model=%s" % (len(files), MODEL), flush=True)
with ThreadPoolExecutor(max_workers=8) as pool:
    list(pool.map(one, files))
print("完成", flush=True)
