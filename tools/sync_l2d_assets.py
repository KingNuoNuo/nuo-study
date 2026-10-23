"""把 Live2D 页面从 dist/live2d-poc 同步进孩子端 App 的 assets。

为什么要「同步」而不是直接把 dist 当 assets 源：
    dist/live2d-poc 是**开发调试台**，里面还有 Haru / HiyoriFree 两个模型、
    一堆对比截图、serve.js。整个目录塞进 APK 会白胖十几兆。
    这里按**白名单**只搬打包需要的那几样，模型也只带日和 PRO 一个。

白名单里每一项都是有理由的（不要随手加）：
    index.html / app.js      页面本体
    lib/*.js                 运行时（pixi + cubism core + pixi-live2d-display）
    HiyoriPro/**             选定的形象（已转 WebP，见 webp_convert.py）

用法：
    python tools/sync_l2d_assets.py            # 同步
    python tools/sync_l2d_assets.py --dry-run  # 只看会搬什么、多大
"""
import argparse
import os
import shutil
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SRC = os.path.join(ROOT, "dist", "live2d-poc")
DST = os.path.join(ROOT, "android", "feature-assistant", "src", "main", "assets", "live2d")

# 根目录下按名字搬的文件
FILES = ["index.html", "app.js"]
# 整个目录搬（相对 SRC）
DIRS = ["lib", "HiyoriPro"]
# 整目录搬时排除的（原件留档、截图、开发用服务）
EXCLUDE = {"_textures_png_original"}


def human(n):
    return f"{n/1024/1024:.2f} MB" if n >= 1024 * 1024 else f"{n/1024:.0f} KB"


def plan():
    """返回 [(源绝对路径, 目标相对路径, 字节数)]"""
    out = []
    for f in FILES:
        p = os.path.join(SRC, f)
        if os.path.exists(p):
            out.append((p, f, os.path.getsize(p)))
    for d in DIRS:
        base = os.path.join(SRC, d)
        for root, dirs, files in os.walk(base):
            dirs[:] = [x for x in dirs if x not in EXCLUDE]
            for f in files:
                p = os.path.join(root, f)
                rel = os.path.relpath(p, SRC).replace("\\", "/")
                out.append((p, rel, os.path.getsize(p)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if not os.path.isdir(SRC):
        sys.exit(f"源目录不存在：{SRC}")

    items = plan()
    total = sum(i[2] for i in items)
    print(f"源  {SRC}")
    print(f"目标 {DST}")
    for _, rel, size in sorted(items, key=lambda x: -x[2])[:10]:
        print(f"    {human(size):>9}  {rel}")
    if len(items) > 10:
        print(f"    … 另外 {len(items)-10} 个文件")
    print(f"合计 {len(items)} 个文件，{human(total)}")

    if a.dry_run:
        return

    for src, rel, _ in items:
        dst = os.path.join(DST, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)

    # 目标目录里多出来的文件（换模型、删图之后会留下）—— 只说，不删：
    # 本机 D 盘的删除会被安全拦截器挡掉（见项目备忘），留清单给靖哥哥手工处理。
    stale = []
    for root, _dirs, files in os.walk(DST):
        for f in files:
            p = os.path.join(root, f)
            rel = os.path.relpath(p, DST).replace("\\", "/")
            if not any(r == rel for _, r, _ in items):
                stale.append(rel)
    if stale:
        print("\n⚠️ 目标目录里有本次没有同步的文件（可能已废弃，确认后可手工删）：")
        for s in stale[:20]:
            print("    - " + s)
    print("\n✅ 同步完成")


if __name__ == "__main__":
    main()
