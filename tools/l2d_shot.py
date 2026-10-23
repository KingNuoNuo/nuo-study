"""给 Live2D PoC 页面拍「真实像素尺寸」的截图 —— 用于把取景常数调对。

为什么要专门写一个脚本，而不是随手敲一句 chrome --screenshot：
  1. **无头截图会随机拍到空白**（实测复现率约 1/5）：模型 ready 与首帧渲染之间
     有个竞态，chrome 一到 virtual-time-budget 就把画面存了，偶尔存的是空 canvas。
     所以必须「拍完检查文件大小 → 太小就当失败重拍」。
  2. **必须按目标容器的精确像素尺寸拍**：构图常数是按「人物高 / 容器高」算出来的，
     用别的尺寸拍出来的比例不能直接搬到 App 里。App 面板的角色区是
     480×450 px（320dp×300dp @ density 240），所以这里是默认值。
  3. 拍完顺手用 PIL 量出「人物 bbox 占画面多少」，把「看着差不多」变成数字。

用法：
    python tools/l2d_shot.py --url ".../index.html?model=hiyori_pro&fit=close&zoom=1.8" --out a.png
    python tools/l2d_shot.py --url "..." --out a.png --w 480 --h 450 --measure
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"


def shoot(url, out, w, h, tries=6, min_kb=60, budget_ms=9000):
    """拍一张。返回 (宽, 高, 字节数)。失败时抛异常。"""
    profile = tempfile.mkdtemp(prefix="l2dshot-")
    # ⚠️ Chrome **不会**替你建输出目录：父目录不存在时它一声不吭地不写文件、
    #    退出码还是 0（"没产出文件" 6 次就是这么来的）。自己先建好。
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    try:
        for i in range(1, tries + 1):
            if os.path.exists(out):
                os.remove(out)
            cmd = [
                CHROME,
                "--headless=new",
                "--disable-gpu",
                "--hide-scrollbars",
                "--no-first-run",
                "--no-default-browser-check",
                f"--user-data-dir={profile}",
                f"--window-size={w},{h}",
                f"--virtual-time-budget={budget_ms}",
                f"--screenshot={out}",
                url,
            ]
            subprocess.run(cmd, capture_output=True, timeout=120)
            if not os.path.exists(out):
                print(f"  [{i}/{tries}] 没产出文件，重拍")
                continue
            size = os.path.getsize(out)
            if size < min_kb * 1024:
                print(f"  [{i}/{tries}] 只有 {size/1024:.0f} KB（< {min_kb}KB），"
                      f"多半拍到了空 canvas，重拍")
                continue
            from PIL import Image
            with Image.open(out) as im:
                return im.width, im.height, size
        raise RuntimeError(f"连拍 {tries} 次都没拿到有效画面")
    finally:
        shutil.rmtree(profile, ignore_errors=True)


def measure(path, bg_tolerance=18):
    """量人物在画面里的包围盒。

    做法：把四角像素的中位数当背景色，扫描「与背景色差异超过阈值」的像素。
    ⚠️ 页面的背景是纯色渐变（不是纯色），所以比的是**容差**而不是相等；
       用渐变底时把 bg_tolerance 调大，或把页面切成纯色底（?bg=flat）再量。
    """
    from PIL import Image
    with Image.open(path) as im:
        im = im.convert("RGB")
        w, h = im.size
        px = im.load()
        corners = [px[1, 1], px[w - 2, 1], px[1, h - 2], px[w - 2, h - 2]]
        bg = tuple(sorted(c[i] for c in corners)[len(corners) // 2] for i in range(3))

        minx, miny, maxx, maxy = w, h, -1, -1
        for y in range(h):
            for x in range(w):
                r, g, b = px[x, y]
                if abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2]) > bg_tolerance * 3:
                    if x < minx: minx = x
                    if x > maxx: maxx = x
                    if y < miny: miny = y
                    if y > maxy: maxy = y
        if maxx < 0:
            print("  !! 整张图都是背景色，没量到人物")
            return None
        bw, bh = maxx - minx + 1, maxy - miny + 1
        print(f"  人物 bbox = ({minx},{miny})-({maxx},{maxy})  {bw}x{bh}"
              f"  占画面 {bw/w:.3f} x {bh/h:.3f}")
        print(f"    头顶留白 {miny}px ({miny/h:.3f})  左右留白 {minx}px / {w-1-maxx}px"
              f"  人物中心 x={(minx+maxx)/2/w:.4f}")
        return minx, miny, maxx, maxy


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--w", type=int, default=480)
    ap.add_argument("--h", type=int, default=450)
    ap.add_argument("--tries", type=int, default=6)
    ap.add_argument("--min-kb", type=int, default=60)
    ap.add_argument("--measure", action="store_true")
    ap.add_argument("--tol", type=int, default=18)
    args = ap.parse_args()

    if not os.path.exists(CHROME):
        sys.exit(f"找不到 Chrome：{CHROME}")
    out = os.path.abspath(args.out)
    print(f"拍 {args.url}\n  -> {out}  ({args.w}x{args.h})")
    w, h, size = shoot(args.url, out, args.w, args.h, args.tries, args.min_kb)
    print(f"  实际产出 {w}x{h}  {size/1024:.0f} KB")
    if args.measure:
        measure(out, args.tol)


if __name__ == "__main__":
    main()
