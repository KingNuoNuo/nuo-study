"""把 Live2D 模型的 PNG 贴图转成 WebP（有损 q90 + 无损 alpha），并改写 model3.json。

为什么值得做：
    日和 PRO 的两张贴图共 4.31MB（2048x2048 RGBA），是整包体积的 90%。
    它们是大面积平涂的动漫色块 + 大块全透明区，正是 WebP 有损最能压的类型。
    实测 q90 能砍掉 ~87% 而肉眼与像素差都看不出（PSNR > 42dB）。

三个必须做对的地方：
  1. **exact=True**：PIL 默认会把「全透明像素」的 RGB 抹成 0，只在解码时保证
     可见区域。Live2D 的贴图在透明区也带颜色（描边外扩），一旦被抹，
     边缘一圈的抗锯齿像素（alpha 很小但 >0）会变黑。exact 保留原始 RGB，
     代价是 alpha 走无损通道，体积略大但换来零风险。
  2. **改 model3.json 而不是改加载器**：Textures 里写的是相对路径，
     换扩展名后不用动 JS 一行代码。
  3. **原件搬到模型目录之外**：留着能随时回滚，但不会被打包进 assets
     （打包脚本按目录 glob，原件在模型目录里就会被带进 APK）。

用法：
    python tools/webp_convert.py            # 转换（默认 q90）
    python tools/webp_convert.py --quality 85
    python tools/webp_convert.py --check    # 只核对现有体积，不转换
"""
import argparse
import json
import os
import shutil
import sys
import time

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：pip install pillow")

POC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist", "live2d-poc")
# 原件收集到 PoC 根目录下的这个文件夹 —— 在模型目录**之外**，不会被 assets 打包带走
ORIG_DIR_NAME = "_textures_png_original"


def human(n):
    return f"{n/1024/1024:.2f} MB" if n >= 1024 * 1024 else f"{n/1024:.1f} KB"


def psnr(a, b):
    """两张同尺寸 RGB 图之间的 PSNR（只看 RGB，alpha 单独比对）。"""
    import math
    from PIL import ImageChops
    diff = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    h = diff.histogram()
    sq = 0
    for band in range(3):
        for v in range(256):
            sq += h[band * 256 + v] * (v ** 2)
    n = a.width * a.height * 3
    mse = sq / n
    return 99.0 if mse == 0 else 10 * math.log10((255.0 ** 2) / mse)


def find_models():
    """返回 [(model3.json 路径, [贴图绝对路径, ...]), ...]"""
    out = []
    for root, _dirs, files in os.walk(POC_DIR):
        if ORIG_DIR_NAME in root:
            continue
        for f in files:
            if not f.endswith(".model3.json"):
                continue
            p = os.path.join(root, f)
            with open(p, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            tex = data.get("FileReferences", {}).get("Textures", [])
            out.append((p, [os.path.join(root, t) for t in tex], data))
    return out


def convert_one(png_path, quality):
    """转一张。返回 (原大小, 新大小, psnr, alpha 差异像素数)。"""
    orig_bytes = os.path.getsize(png_path)
    webp_path = os.path.splitext(png_path)[0] + ".webp"

    with Image.open(png_path) as im:
        src = im.convert("RGBA")
        src_rgb = src.convert("RGB")
        src_a = src.getchannel("A")

        t0 = time.time()
        src.save(webp_path, "WEBP", quality=quality, method=6, exact=True)
        dt = time.time() - t0

    with Image.open(webp_path) as rt:
        rt = rt.convert("RGBA")
        p = psnr(src_rgb, rt)
        # alpha 必须**逐像素完全相等**：贴图边缘的 alpha 决定模型轮廓，差一点就是锯齿/黑边
        from PIL import ImageChops
        alpha_diff = sum(ImageChops.difference(src_a, rt.getchannel("A")).histogram()[1:])

    new_bytes = os.path.getsize(webp_path)
    print(f"    {os.path.basename(png_path)}: {human(orig_bytes)} -> {human(new_bytes)} "
          f"(-{100*(1-new_bytes/orig_bytes):.1f}%)  PSNR {p:.1f}dB  "
          f"alpha 差异像素 {alpha_diff}  用时 {dt:.1f}s")
    return orig_bytes, new_bytes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quality", type=int, default=90)
    ap.add_argument("--check", action="store_true", help="只统计体积，不转换")
    args = ap.parse_args()

    models = find_models()
    if not models:
        sys.exit(f"没找到 model3.json（{POC_DIR}）")

    total_old = total_new = 0
    for json_path, textures, data in models:
        rel = os.path.relpath(os.path.dirname(json_path), POC_DIR)
        print(f"\n[{rel}]")
        changed = False
        new_tex = []
        for t in textures:
            ext = os.path.splitext(t)[1].lower()
            if ext == ".webp":
                n = os.path.getsize(t)
                print(f"    {os.path.basename(t)}: 已是 webp {human(n)}")
                total_old += n
                total_new += n
                new_tex.append(os.path.relpath(t, os.path.dirname(json_path)).replace("\\", "/"))
                continue
            if ext != ".png":
                new_tex.append(os.path.relpath(t, os.path.dirname(json_path)).replace("\\", "/"))
                continue
            if not os.path.exists(t):
                print(f"    !! 缺失 {t}")
                continue

            total_old += os.path.getsize(t)
            if args.check:
                webp_path = os.path.splitext(t)[0] + ".webp"
                if os.path.exists(webp_path):
                    total_new += os.path.getsize(webp_path)
                    print(f"    {os.path.basename(t)}: PNG {human(os.path.getsize(t))} "
                          f"-> webp {human(os.path.getsize(webp_path))}")
                else:
                    total_new += os.path.getsize(t)
                    print(f"    {os.path.basename(t)}: 尚未转换")
            else:
                os_, ns = convert_one(t, args.quality)
                total_new += ns
                # 原件搬到模型目录之外
                dest_dir = os.path.join(POC_DIR, ORIG_DIR_NAME, rel)
                os.makedirs(dest_dir, exist_ok=True)
                shutil.move(t, os.path.join(dest_dir, os.path.basename(t)))
                changed = True

            new_tex.append(
                os.path.relpath(os.path.splitext(t)[0] + ".webp",
                                os.path.dirname(json_path)).replace("\\", "/"))

        if changed and new_tex != data["FileReferences"]["Textures"]:
            data["FileReferences"]["Textures"] = new_tex
            with open(json_path, "w", encoding="utf-8") as fh:
                json.dump(data, fh, indent="\t", ensure_ascii=False)
                fh.write("\n")
            print(f"    model3.json 已改写 -> {new_tex}")

    print(f"\n合计贴图：{human(total_old)} -> {human(total_new)}"
          f"  (-{100*(1-total_new/max(total_old,1)):.1f}%)")


if __name__ == "__main__":
    main()
