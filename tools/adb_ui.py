# -*- coding: utf-8 -*-
"""打印当前屏幕的 UI 层级（真机调试用）。

为什么需要它：`uiautomator dump` 出来的 XML 是单行、带一堆转义的长字符串，
直接看基本没法读；而 `dumpsys` 也不给控件坐标。这个脚本把每个可见节点的
**类名 / 文本 / content-desc / 包围盒** 排成表，用来：
  - 量控件位置（比如验证 insets 修复后页签顶边从 12px 变成 48px）
  - 拿到输入框中心的像素坐标，配合 `adb shell input tap X Y` 做自动化

用法：
    python tools/adb_ui.py                     # 全部有文本/描述的节点
    python tools/adb_ui.py --all                # 连没有文本的容器节点也打
    python tools/adb_ui.py -f 令牌              # 只看含「令牌」的行
    python tools/adb_ui.py --edit               # 只看输入框（拿坐标最常用）
    python tools/adb_ui.py -s AX2CUT5910000252  # 指定设备（接了两台以上时必须给）

    # 自动化常用（把踩过的坑固化进去了）：
    python tools/adb_ui.py -s X --tap "刷新" --shot out.png   # 定位→点击→等→截屏→打表
    python tools/adb_ui.py -s X --tap "确定" --hide-kb        # 点击前先收软键盘
    python tools/adb_ui.py -s X --tap "保存" --tap-contains   # 子串匹配

⚠️ `--tap` 是**在同一次 dump 里**定位并点击的，不要自己「先 dump 拿坐标、再发一次
   `input tap`」—— 两步之间界面一变（键盘弹出、toast 消失），坐标就指向别的东西了。

⚠️ 软键盘会盖住屏幕下半部分的控件。要点下面的按钮（尤其是对话框里的「确定」），
   必须加 `--hide-kb`，否则点到的是键盘按键 —— 现象是「密码框凭空多了一个字符」。

⚠️ 调用 adb 必须带 MSYS_NO_PATHCONV=1，否则 Git Bash 会把 /sdcard/... 转成
   Windows 路径。脚本里已经自己处理了（用 shell 参数，不经 Git Bash 转换）。

⚠️ 同时插着平板和手机时，adb 会报 "more than one device/emulator"。
   指定设备用 `-s`（内部设 ANDROID_SERIAL 环境变量 —— adb 原生认这个，
   比在每个子命令上拼 `-s` 更省事，`shell` 和 `pull` 一次全生效）。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ADB = Path(r"D:\workbuddy\toolchain\android-sdk\platform-tools\adb.exe")
REMOTE = "/sdcard/window_dump.xml"

NODE_RE = re.compile(r"<node[^>]*>")
BOUNDS_RE = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
TEXT_RE = re.compile(r'text="([^"]*)"')
DESC_RE = re.compile(r'content-desc="([^"]*)"')
CLASS_RE = re.compile(r'class="([^"]*)"')


def adb(*args: str, serial: str | None = None) -> str:
    # 关键：不要走 shell=True，也不要用 Git Bash 解释参数 ——
    # 直接把参数交给 adb.exe，绕开 MSYS 的路径转换。
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    if serial:
        # adb 原生支持 ANDROID_SERIAL，比自己往每条子命令前拼 `-s` 可靠
        env["ANDROID_SERIAL"] = serial
    out = subprocess.run(
        [str(ADB), *args],
        capture_output=True,
        env=env,
        check=False,
    )
    return out.stdout.decode("utf-8", errors="replace")


def dump(serial: str | None = None) -> str:
    adb("shell", "uiautomator", "dump", REMOTE, serial=serial)
    with tempfile.TemporaryDirectory() as td:
        local = Path(td) / "ui.xml"
        adb("pull", REMOTE, str(local), serial=serial)
        return local.read_text(encoding="utf-8", errors="replace")


def hide_keyboard(serial: str | None = None) -> None:
    """按返回键收起软键盘。

    ⚠️ 为什么每次点击前都要做：软键盘会**盖住屏幕下半部分的控件**，
    此时 `input tap` 点到的是键盘按键 —— 表现为「明明点的是确定，密码框却多了一个字符」，
    极难联想到是键盘。而且键盘弹出会**改变布局**，之前量好的坐标全部失效。
    键盘没弹时按返回是安全的：这里只在有键盘时才调用（由调用方判断）。
    """
    adb("shell", "input", "keyevent", "4", serial=serial)
    time.sleep(0.8)


def screenshot(path: Path, serial: str | None = None) -> Path:
    """截屏到本地文件。走 `exec-out` 拿原始字节，避免 `adb shell` 的 CRLF 损坏 PNG。"""
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    if serial:
        env["ANDROID_SERIAL"] = serial
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        subprocess.run([str(ADB), "exec-out", "screencap", "-p"],
                       stdout=f, env=env, check=False)
    return path


def nodes(xml: str):
    """把 XML 解析成 (y0, x0, x0b, y0b, x1, y1, cls, text, desc) 列表。"""
    out = []
    for m in NODE_RE.finditer(xml):
        tag = m.group(0)
        b = BOUNDS_RE.search(tag)
        if not b:
            continue
        x0, y0, x1, y1 = (int(v) for v in b.groups())
        cls = CLASS_RE.search(tag)
        cls = cls.group(1).split(".")[-1] if cls else ""
        text = TEXT_RE.search(tag)
        text = text.group(1) if text else ""
        desc = DESC_RE.search(tag)
        desc = desc.group(1) if desc else ""
        out.append((y0, x0, x0, y0, x1, y1, cls, text, desc))
    return out


def tap_text(label: str, serial: str | None = None, *, exact: bool = True) -> bool:
    """**在同一条命令里** dump → 按文本定位 → 点击。

    ⚠️ 必须合并成一步，不能「先 dump 拿坐标、再另外发一次 tap」：
    界面在这两步之间一旦变化（比如键盘弹出、toast 消失），坐标就指向别的东西了。
    """
    xml = dump(serial)
    for _y0, _x0, x0, y0, x1, y1, cls, text, desc in nodes(xml):
        hay = text or desc
        hit = (hay.strip() == label) if exact else (label in hay or label in cls)
        if not hit:
            continue
        cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
        print("tap 「%s」 [%s] 中心=(%d,%d)" % (hay.strip()[:24], cls, cx, cy))
        adb("shell", "input", "tap", str(cx), str(cy), serial=serial)
        return True
    print("!! 界面上找不到「%s」" % label, file=sys.stderr)
    return False


def keyboard_up(xml: str) -> bool:
    """UI 树里出现输入法则认为软键盘是弹着的。"""
    return "InputMethod" in xml or 'package="com.android.inputmethod' in xml


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="连无文本的容器也打印")
    ap.add_argument("-f", "--filter", default=None, help="只打印包含该子串的行")
    ap.add_argument("--edit", action="store_true", help="只看输入框（含中心坐标）")
    ap.add_argument("-s", "--serial", default=None,
                    help="设备串号（adb devices 里的第一列）。接了两台以上时必须给")
    ap.add_argument("--tap", default=None, help="按文本/content-desc 定位并点击（同一步完成，坐标不会过期）")
    ap.add_argument("--tap-contains", action="store_true", help="--tap 改成子串匹配")
    ap.add_argument("--shot", default=None, help="截屏保存到该路径")
    ap.add_argument("--hide-kb", action="store_true", help="点击前先按返回键收起软键盘")
    ap.add_argument("--wait", type=float, default=1.5, help="点击后等待秒数，默认 1.5")
    args = ap.parse_args()

    if not ADB.exists():
        print("找不到 adb：%s" % ADB, file=sys.stderr)
        return 2

    if args.tap:
        if args.hide_kb and keyboard_up(dump(args.serial)):
            print("收起软键盘")
            hide_keyboard(args.serial)
        ok = tap_text(args.tap, args.serial, exact=not args.tap_contains)
        time.sleep(args.wait)
    else:
        ok = True

    if args.shot:
        p = screenshot(Path(args.shot), args.serial)
        print("截屏 → %s（%d KB）" % (p, p.stat().st_size // 1024))

    xml = dump(args.serial)
    if not xml.strip():
        print("dump 为空 —— 应用可能正在切换界面（等 1 秒重试）", file=sys.stderr)
        return 1

    rows = nodes(xml)
    if args.edit:
        rows = [r for r in rows if "EditText" in r[6]]
    elif not args.all:
        rows = [r for r in rows if r[7] or r[8]]

    if args.filter:
        rows = [r for r in rows if args.filter in r[7] or args.filter in r[8] or args.filter in r[6]]

    if not rows:
        print("（没有匹配的节点）")
        return 0 if ok else 1

    print("%-6s %-24s %-16s %-30s %s" % ("y0", "bounds", "class", "text", "content-desc"))
    for y0, x0, x0b, y0b, x1, y1, cls, text, desc in sorted(rows):
        centre = "中心=(%d,%d)" % ((x0b + x1) // 2, (y0b + y1) // 2)
        label = (text or desc)
        suffix = centre if (args.edit or "EditText" in cls) else desc
        print("%-6d %-24s %-16s %-30s %s"
              % (y0, "[%d,%d][%d,%d]" % (x0b, y0b, x1, y1), cls, label[:30], suffix[:40]))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
