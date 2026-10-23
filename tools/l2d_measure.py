"""量出一张 Live2D 截图里「人物」的包围盒，把构图从「看着差不多」变成数字。

做法：背景取四角像素的中位色，扫描所有「与背景差得够远」的像素取包围盒。
⚠️ 只对**纯色背景**可靠（页面用 ?bg 默认值 / ?bg=flat）。用 studio 渐变底量出来
   的是「整块渐变」，没意义 —— 要量就切回纯色底再拍一张。

用法：python tools/l2d_measure.py shot.png [--tol 18]
"""
import argparse
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow")


def measure(path, tol=18):
    with Image.open(path) as im:
        im = im.convert("RGB")
        w, h = im.size
        px = im.load()
        corners = [px[1, 1], px[w - 2, 1], px[1, h - 2], px[w - 2, h - 2]]
        bg = tuple(sorted(c[i] for c in corners)[len(corners) // 2] for i in range(3))

        minx, miny, maxx, maxy = w, h, -1, -1
        step = 1
        for y in range(0, h, step):
            for x in range(0, w, step):
                r, g, b = px[x, y]
                if abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2]) > tol * 3:
                    if x < minx:
                        minx = x
                    if x > maxx:
                        maxx = x
                    if y < miny:
                        miny = y
                    if y > maxy:
                        maxy = y
        if maxx < 0:
            print("  !! 整张图都是背景色（背景 %s），没量到人物" % (bg,))
            return None
        bw, bh = maxx - minx + 1, maxy - miny + 1
        print("  背景 %s  人物 bbox=(%d,%d)-(%d,%d)  %dx%d"
              % (bg, minx, miny, maxx, maxy, bw, bh))
        clipped = [n for n, v in (("左", minx <= 0), ("右", maxx >= w - 1),
                                  ("上", miny <= 0), ("下", maxy >= h - 1)) if v]
        print("    占画面 %.3f x %.3f | 头顶留白 %dpx(%.3f) | 左右留白 %d / %d | 中心x %.4f"
              % (bw / w, bh / h, miny, miny / h, minx, w - 1 - maxx, (minx + maxx) / 2 / w))
        if clipped:
            print("    ⚠️ 触到画面边缘 %s —— 人物被裁掉了，占画面比例只是「可见部分」"
                  % "".join(clipped))

        # 有 sidecar（l2d_shot_cdp.js 写的变换参数）就把画面坐标反推回
        # **包围盒内的比例** —— 那才是按模型（而不是按某个容器）定义的常数。
        side = path + ".json"
        if os.path.exists(side):
            import json
            p = json.load(open(side))
            s = p["s"]
            if p.get("fit") == "raw":
                # raw 模式：整张包围盒完整放进画面，直接反推四个常数
                left = (minx - p["posX"] - p["bx"] * s) / s / p["bw"]
                right = (maxx - p["posX"] - p["bx"] * s) / s / p["bw"]
                top = (miny - p["posY"] - p["by"] * s) / s / p["bh"]
                bottom = (maxy - p["posY"] - p["by"] * s) / s / p["bh"]
                print("    ▶ fit=raw 反推模型常数（直接抄进 app.js 的 MODELS[..].person）：")
                print("        h: %.4f, top: %.4f, cx: %.4f, w: %.4f"
                      % (bottom - top, top, (left + right) / 2, right - left))
            else:
                # 其他模式：反推「人物可见部分相对画面」的位置，用于判断取景是否居中/裁切
                cx_screen = (minx + maxx) / 2
                want = p["w"] / 2
                print("    ▶ 人物中心偏离容器中心 %.1fpx（%.4f 个容器宽）"
                      % (cx_screen - want, (cx_screen - want) / p["w"]))
        return minx, miny, maxx, maxy


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--tol", type=int, default=18)
    a = ap.parse_args()
    measure(a.image, a.tol)
