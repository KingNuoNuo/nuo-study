#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成「发音试听页」：同一句话用不同音色/语速/音调各合成一遍，摆在一起听。

2026-10-23 靖哥哥说「播放示范的发音感觉怪怪的」。怪在哪必须让他自己选，
所以把它做成页面：`http://<NAS>:8092/english/voice-samples/`

在容器里跑（要 Azure key 与 ffmpeg 都在的环境；其实只用 key）：

  docker exec nuo-study python3 /tmp/voice_samples.py "She wanted to try her best."

产物：
  /app/web/english/voice-samples/*.mp3   各组合的音频
  /app/web/english/voice-samples/index.html  试听页（带当前配置标记）
"""
import html
import json
import os
import pathlib
import sys

sys.path.insert(0, "/app")
from server import config, english  # noqa: E402

SENTENCE = (sys.argv[1] if len(sys.argv) > 1
            else "She wanted to try her best. It was her first school play.")
OUT = pathlib.Path("/app/web/english/voice-samples")
OUT.mkdir(parents=True, exist_ok=True)

# 组合：先放「当前线上用的」，再放各种更自然的选择。
# 英式优先 —— 教材是译林版，配套录音是英音；美音只作为对照。
def _desc(voice, rate, pitch):
    """把配置翻译成中文描述。**动态读 config**，不然改了默认值这里就成假话。"""
    bits = [voice]
    bits.append("语速 " + (rate if rate else "原生"))
    bits.append("音调 " + (pitch if pitch else "原生"))
    return " · ".join(bits)


_CUR = (config.AZURE_TTS_VOICE, config.AZURE_TTS_RATE, config.AZURE_TTS_PITCH)
VARIANTS = [
    ("current", "① 现在线上用的：" + _desc(*_CUR), *_CUR),
    ("libby-plain", "② en-GB-LibbyNeural 原生（上一个版本的音色，对照用）",
     "en-GB-LibbyNeural", "", ""),
    ("sonia", "③ en-GB-SoniaNeural（女声，教材朗读常用）", "en-GB-SoniaNeural", "", ""),
    ("ryan", "④ en-GB-RyanNeural（男声，朗读清楚）", "en-GB-RyanNeural", "", ""),
    ("maisie", "⑤ en-GB-MaisieNeural（童声感，女童音色）", "en-GB-MaisieNeural", "", ""),
    ("sonia-slow", "⑥ en-GB-SoniaNeural · 语速 -12%（更慢，跟读用）",
     "en-GB-SoniaNeural", "-12%", ""),
    ("aria-us", "⑦ en-US-AriaNeural（美音，仅作对照）", "en-US-AriaNeural", "", ""),
]

rows = []
for slug, label, voice, rate, pitch in VARIANTS:
    try:
        url = english.azure_tts(SENTENCE, voice=voice, rate=rate, pitch=pitch)
    except Exception as exc:                      # noqa: BLE001
        print("失败 %s: %s" % (slug, exc), flush=True)
        continue
    file_name = url.rsplit("/", 1)[-1]
    target = OUT / ("%s.mp3" % slug)
    src = config.TTS_DIR / file_name
    target.write_bytes(src.read_bytes())
    rows.append((slug, label, voice, rate or "-", pitch or "-",
                 "/english/voice-samples/%s.mp3" % slug))
    print("ok %-12s %s" % (slug, label), flush=True)

page = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>发音试听 · 诺诺英语</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;padding:32px;background:#f7f4ea;color:#24352e;font-family:"Microsoft YaHei",system-ui,sans-serif}
h1{font:800 26px Georgia;margin:0 0 6px}p.lead{color:#6b7a72;margin:0 0 26px}
.item{background:#fff;border-radius:16px;padding:16px 20px;margin-bottom:12px;display:flex;gap:18px;align-items:center;flex-wrap:wrap}
.item b{font-size:15px}
.meta{color:#8a9a92;font-size:12px;margin-top:4px}
audio{flex:1;min-width:260px}
.tag{background:#2d725e;color:#fff;border-radius:99px;padding:3px 10px;font-size:11px}
a{color:#2d725e}
</style></head><body>
<h1>发音试听</h1>
<p class="lead">同一句：《%s》—— 听完告诉我用哪一版，我改 <code>AZURE_TTS_VOICE</code> /
<code>AZURE_TTS_RATE</code> / <code>AZURE_TTS_PITCH</code> 就行。<br>
<a href="/english/">← 回英语主页</a></p>
%s
</body></html>""" % (
    html.escape(SENTENCE),
    "\n".join(
        '<div class="item"><div><b>%s</b>%s<div class="meta">%s · rate=%s · pitch=%s</div></div>'
        '<audio controls preload="metadata" src="%s"></audio></div>'
        % (html.escape(label), ' <span class="tag">在用</span>' if slug == "current" else "",
           html.escape(voice), html.escape(rate), html.escape(pitch), path)
        for slug, label, voice, rate, pitch, path in rows),
)
(OUT / "index.html").write_text(page, encoding="utf-8")

# 自检：页面里引用的音频必须**真的能取到**。
# 上一版就是「页面生成成功、音频 404」，截图看不出来（播放器只显示 0:00）——
# 所以这里直接把每个 src 打出来，部署脚本/人眼一看就知道有没有错。
print("--- 页面引用的音频 ---")
for slug, label, voice, rate, pitch, path in rows:
    print("  %s  %s  %d KB" % (path, "存在" if (OUT / ("%s.mp3" % slug)).exists() else "缺失",
                               (OUT / ("%s.mp3" % slug)).stat().st_size // 1024))
print("试听页：/english/voice-samples/  共 %d 组" % len(rows))
