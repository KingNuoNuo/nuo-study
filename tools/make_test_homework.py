#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成一张「老师发的作业截图」测试图，用于端到端联调。

为什么需要这个
--------------
联调「家长拍图 → 识别 → 发布 → 孩子端看到」这条链路时，手边往往没有真的作业单。
但识别链路的日期解析**依赖图里写的那句「【10.9数学作业】」**，所以测试图的日期
必须是**当天**，否则孩子端「今日作业」页（按 `LocalDate.now()` 过滤）永远空着，
会误判成链路坏了。

用法
----
  python tools/make_test_homework.py                 # 生成今天的，写到 .debug-shots/
  python tools/make_test_homework.py --date 2026-10-08
  python tools/make_test_homework.py -o D:/tmp/x.jpg --subject 语文 --items 4

设计约束（改图时别踩）
----------------------
· 日期必须**同时**出现在自拟标题和正文里：提示词把「星期几」当作日期校验位，
  只写 `10.9` 不写 `周五` 时，模型有可能退回「按今天算」。
· 图不要做得太规整 —— 真实拍照会有倾斜、阴影。这里刻意加了轻微旋转与灰底，
  是为了让识别链路走在和真机相近的难度上。
"""
from __future__ import annotations

import argparse
import datetime as dt
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",      # 微软雅黑
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
]

WEEKDAYS = "一二三四五六日"

BODIES = {
    "数学": [
        "计算下面各题，能简算的要简算。",
        "(1) 3/4 + 1/8 =        (2) 5/6 - 1/3 =",
        "(3) 7/9 × 3/14 =       (4) 5/8 ÷ 5/16 =",
        "解方程。",
        "(1) 2x + 5 = 17        (2) 3(x - 4) = 18",
        "应用题：小明买了 3 本书，每本 12 元，付出 50 元，应找回多少元？",
    ],
    "语文": [
        "一、看拼音写词语。",
        "jing mi    kui wu    zao gao    qi liang",
        "二、按要求写句子。",
        "1. 把下面的句子改写成拟人句。",
        "   小溪哗啦啦地流着。",
        "三、背诵并默写《七律·长征》。",
    ],
    "英语": [
        "I. Read and choose. (选择填空)",
        "1. She ___ to school by bus every day.",
        "   A. go   B. goes   C. going",
        "II. Write the words in the correct order.",
        "1. is / my / this / teacher / English /.",
        "III. Read the passage and answer the questions.",
    ],
}


def pick_font(size: int) -> ImageFont.FreeTypeFont:
    for p in FONT_CANDIDATES:
        if Path(p).exists():
            return ImageFont.truetype(p, size)
    raise SystemExit("!! 找不到任何可用中文字体，请把路径加进 FONT_CANDIDATES")


def build(date: dt.date, subject: str, items: int, out: Path) -> Path:
    # 1080×1920 是主流手机截图的尺寸；识别侧的 ImagePreparer 会把长边压到 1600，
    # 这里刻意留出余量，顺便验证压缩逻辑。
    W, H = 1080, 1920
    img = Image.new("RGB", (W, H), (238, 236, 231))     # 略带暖灰的纸色
    d = ImageDraw.Draw(img)

    f_title = pick_font(64)
    f_head = pick_font(46)
    f_body = pick_font(40)
    f_note = pick_font(34)

    weekday = "周" + WEEKDAYS[date.weekday()]
    stamp = "%d.%d" % (date.month, date.day)
    title = "【%s%s作业】" % (stamp, subject)

    y = 110
    d.text((70, y), title, font=f_title, fill=(20, 20, 25))
    y += 90
    d.text((70, y), "（%s）" % weekday, font=f_head, fill=(90, 90, 100))
    y += 80
    d.line([(70, y), (W - 70, y)], fill=(190, 190, 195), width=3)
    y += 40

    body = BODIES.get(subject, BODIES["数学"])
    n = 0
    for line in body:
        if n >= items + 2:          # 前两行是导语，不算题项
            break
        d.text((70, y), line, font=f_body, fill=(35, 35, 40))
        y += 62
        if line.strip() and not line.startswith(("(", "1.", "2.", "3.", "A.", "一", "二", "三", "I")):
            n += 1
    y += 30
    d.text((70, y), "（请认真完成，明天上交）", font=f_note, fill=(120, 60, 60))

    # 轻微旋转 + 淡阴影，让它看起来像随手拍的
    pad = 60
    canvas = Image.new("RGB", (W + pad * 2, H + pad * 2), (232, 230, 226))
    canvas.paste(img, (pad, pad))
    canvas = canvas.rotate(-1.2, resample=Image.BICUBIC, fillcolor=(232, 230, 226))

    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, "JPEG", quality=88)
    print("已生成 %s（%dx%d，%d KB）"
          % (out, canvas.width, canvas.height, out.stat().st_size // 1024))
    print("  标题=%s  日期=%s  星期=%s" % (title, date.isoformat(), weekday))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default="", help="yyyy-MM-dd，默认今天")
    ap.add_argument("--subject", default="数学", choices=list(BODIES))
    ap.add_argument("--items", type=int, default=4, help="大致题项数")
    ap.add_argument("-o", "--out", default="", help="输出路径")
    args = ap.parse_args()

    date = (dt.date.fromisoformat(args.date) if args.date else dt.date.today())
    out = Path(args.out) if args.out else (
        ROOT / ".debug-shots" / ("test-hw-%s.jpg" % date.isoformat()))
    build(date, args.subject, args.items, out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
