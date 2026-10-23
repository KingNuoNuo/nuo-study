# -*- coding: utf-8 -*-
"""学生端装机：安装 → AOT 编译 → **回读校验 AOT 真的生效** → 冷启动计时。

为什么需要这个脚本（两个理由，第二个是 2026-10-10 才补上的）：

 1. `adb install -r` 每次重装都会清掉 AOT 产物，得重跑一次 `cmd package compile`。
    固化成一条命令，装机时不用记。

 2. ⚠️⚠️ **`cmd package compile -m speed -f` 对 debuggable 的包是空转。**
    它会照常打印 `Success`，但 `cmd package dump` 里 Dexopt 状态**永远是 `quicken`**
    —— 只有字节码速化（解释执行 + JIT），一行机器码都没产出。
    所以本脚本装完**必须回读一次 Dexopt 状态**（见 [dexopt_status]），
    把「静默失效」变成「要么 status=speed，要么当场报错」。
    真实代价：纯 Java 的 opus（Concentus）跑在解释/JIT 下会吃掉**全机 21% 的 CPU**
    （simpleperf 实测，除 GPU 驱动外最大的一项），通话中整机只剩 14~37% 空闲，
    Live2D 画面从 50fps 掉到 30~38fps —— 表现就是「动画不连贯」。
    正因如此，本脚本**默认装 perf 档**（`assemblePerf`：同签名、同包名，
    只把 debuggable 关掉，从而让 AOT 真的生效；构型见 buildSrc 的
    AndroidApplicationConventionPlugin）。

用法：
    python tools/install_student.py                      # 装 dist/app-student-perf.apk（推荐）
    python tools/install_student.py --variant debug      # 装 debug 档（要连 adb 调试时用）
    python tools/install_student.py path/to/apk          # 装指定 APK
    python tools/install_student.py --no-aot             # 只安装，跳过 AOT 与校验
    python tools/install_student.py -s GM260200701 ...    # 指定设备（多台时必须给）

⚠️ 与 adb_ui.py 相同：调 adb 必须设 MSYS_NO_PATHCONV=1，否则 Git Bash 会把
   /sdcard/... 转成 Windows 路径。脚本里已处理（直接调 adb.exe，不经 shell）。
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

ADB = Path(r"D:\workbuddy\toolchain\android-sdk\platform-tools\adb.exe")

# 学生端包名（与 app-student/build.gradle.kts 的 applicationId + 各 build type 的
# applicationIdSuffix 一致）。⚠️ perf 档**刻意沿用** `.debug` 后缀 —— 与 debug 档
# 同包名才能覆盖安装到已装好的平板上，否则会装出第二个 App、服务端设置全空。
PKG = "com.jinyu.study.student.debug"

# 各变体的产出名（相对脚本所在目录的上级 dist/）
VARIANT_APK = {
    "perf": "app-student-perf.apk",
    "debug": "app-student-debug.apk",
}

# 各变体对应的 gradle 任务（APK 不存在时给提示用）
VARIANT_TASK = {
    "perf": ":app-student:assemblePerf",
    "debug": ":app-student:assembleDebug",
}

# 孩子端平板串号（见 MEMORY.md：GM260200701 = 孩子端平板）
STUDENT_SERIAL = "GM260200701"


def adb(*args: str, serial: str | None = None, check: bool = False) -> str:
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    if serial:
        env["ANDROID_SERIAL"] = serial
    out = subprocess.run([str(ADB), *args], capture_output=True, env=env, check=check)
    return out.stdout.decode("utf-8", errors="replace")


def dexopt_status(serial: str) -> str:
    """回读 arm64 的 Dexopt 状态，例如 `quicken` / `speed` / `speed-profile`。

    为什么非要回读而不是信 `compile` 的返回值：对 debuggable 的包，
    `cmd package compile -m speed -f` 返回 `Success` 而状态仍是 `quicken` ——
    这是个**静默失效**，只看返回值永远发现不了（踩过一次，靠它误导了好几轮排查）。
    """
    out = adb("shell", "cmd", "package", "dump", PKG, serial=serial)
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("arm64:") and "status=" in line:
            return line.split("status=")[1].split("]")[0].strip()
    return ""


def main() -> int:
    ap = argparse.ArgumentParser(description="学生端装机 + AOT 编译 + 校验")
    ap.add_argument("apk", nargs="?", type=Path, default=None, help="APK 路径（给了就忽略 --variant）")
    ap.add_argument(
        "-v", "--variant", choices=sorted(VARIANT_APK), default="perf",
        help="装哪一档（默认 perf：关掉 debuggable 才能 AOT）",
    )
    ap.add_argument("-s", "--serial", default=STUDENT_SERIAL, help="设备串号（默认孩子端平板）")
    ap.add_argument("--no-aot", action="store_true", help="只安装，跳过 AOT 编译与校验")
    args = ap.parse_args()

    apk = args.apk or (
        Path(__file__).resolve().parent.parent / "dist" / VARIANT_APK[args.variant]
    )
    if not apk.exists():
        print(f"[✗] APK 不存在：{apk}", file=sys.stderr)
        print(f"    先构建：gradle {VARIANT_TASK.get(args.variant, ':app-student:assemble' + args.variant.title())}",
              file=sys.stderr)
        return 1

    print(f"[1/4] 安装 {apk.name} → {args.serial} ...")
    r = adb("install", "-r", "-t", str(apk), serial=args.serial)
    print("      " + (r.strip().splitlines()[-1] if r.strip() else "(无输出)"))
    if "Failure" in r:
        return 1

    if args.no_aot:
        print("[2/4] 跳过 AOT（--no-aot）")
    else:
        print("[2/4] AOT 编译（-m speed）...")
        # -m speed：完整 AOT，牺牲一次编译时间换运行时最快
        # -f：强制重新编译（覆盖上次 install 后残留的旧状态）
        r = adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", PKG, serial=args.serial)
        print("      " + (r.strip() or "(无输出)"))

        print("[3/4] 回读 Dexopt 状态（这一步才是真校验）...")
        st = dexopt_status(args.serial)
        if st == "speed":
            print(f"      arm64 status = {st}  ✓ AOT 生效")
        else:
            print(f"      arm64 status = {st or '(读不到)'}  ✗ AOT 没生效！", file=sys.stderr)
            print(
                "      `quicken` 说明这个包是 **debuggable** 的，ART 拒绝给它做 speed 编译 ——\n"
                "      `compile` 的 Success 是假的。请改装 perf 档：\n"
                "        gradle :app-student:assemblePerf\n"
                "        python tools/install_student.py --variant perf",
                file=sys.stderr,
            )
            return 2

    print("[4/4] 启动并测冷启动耗时 ...")
    adb("shell", "am", "force-stop", PKG, serial=args.serial)
    time.sleep(0.5)
    r = adb(
        "shell", "am", "start", "-W", "-n",
        f"{PKG}/com.jinyu.study.student.MainActivity",
        serial=args.serial,
    )
    for line in r.splitlines():
        if any(k in line for k in ("ThisTime", "TotalTime", "WaitTime")):
            print("      " + line.strip())

    print("\n完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
