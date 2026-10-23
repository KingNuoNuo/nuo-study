# -*- coding: utf-8 -*-
"""安卓骨架的静态自检。

没有 Android SDK 也能查出来的那一类错误：
  1. settings.gradle.kts 里 include 的模块，目录与 build.gradle.kts 是否都在
  2. 各模块 build.gradle.kts 里 project(":xxx") 引用的模块是否已声明
  3. Kotlin 文件的 package 声明与所在目录是否一致（包名写错编译必失败）
  4. XML 是否 well-formed，TOML 是否能解析
  5. build.gradle.kts 里 libs.xxx 访问器在版本目录里是否真有定义
     —— 拼错一个别名，Gradle 会报 "Unresolved reference" 而且定位很不直观

用法：python tools/check_android_skeleton.py
"""
from __future__ import annotations

import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "android"
problems: list[str] = []
notes: list[str] = []


def ok(msg: str) -> None:
    print("  ok   %s" % msg)


def bad(msg: str) -> None:
    problems.append(msg)
    print("  FAIL %s" % msg)


def strip_kts_comments(src: str) -> str:
    """去掉 Kotlin 脚本里的注释，但保留字符串字面量里的 // 与 /*。

    自检关心的是「代码里真的引用了哪个 libs.xxx」。注释里的示例也算引用的话，
    像 buildSrc/build.gradle.kts 里解释归属规则的那段文字
    （『不能再被 alias(libs.plugins.xxx) 带版本请求』）就会变成误报。
    """
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if ch == '"' and src.startswith('"""', i):        # 三引号字符串
            end = src.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(src[i:end])
            i = end
            continue
        if ch in '"\'':                                   # 普通字符串 / 字符字面量
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == ch or src[j] == "\n":
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        if ch == "/" and nxt == "*":                      # 块注释
            end = src.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append("\n")
            continue
        if ch == "/" and nxt == "/":                      # 行注释
            end = src.find("\n", i)
            i = n if end < 0 else end
            continue
        out.append(ch)
        i += 1
    return "".join(out)


# ---------------------------------------------------------------- 1. 模块清单
print("== 1. 模块清单 ==")
settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
included = re.findall(r'"(:\w[\w-]*)"', settings)
mods = [m.lstrip(":") for m in included]
print("     settings 声明 %d 个模块: %s" % (len(mods), ", ".join(mods)))
for m in mods:
    d = ROOT / m
    if not d.is_dir():
        bad("模块目录不存在: %s" % m)
    elif not (d / "build.gradle.kts").exists():
        bad("模块缺少 build.gradle.kts: %s" % m)
if not problems:
    ok("%d 个模块目录与构建脚本齐备" % len(mods))

# 反向检查：目录建了但没 include（容易忘）
for d in sorted(ROOT.iterdir()):
    # buildSrc 是 Gradle 约定的特殊目录，本就不该出现在 settings 的 include 里
    if d.name == "buildSrc":
        continue
    if d.is_dir() and (d / "build.gradle.kts").exists() and d.name not in mods:
        bad("目录 %s 有 build.gradle.kts 但未 include 进 settings" % d.name)

# ---------------------------------------------------------------- 2. 模块间依赖
print("\n== 2. 模块间依赖引用 ==")
declared = set(mods)
for m in mods:
    txt = (ROOT / m / "build.gradle.kts").read_text(encoding="utf-8")
    for dep in re.findall(r'project\(":([\w-]+)"\)', txt):
        if dep not in declared:
            bad("%s 依赖了未声明的模块 :%s" % (m, dep))
if not problems:
    ok("所有 project(...) 引用都已声明")

# ---------------------------------------------------------------- 3. package 与路径
print("\n== 3. Kotlin 包名与目录 ==")
kt_files = sorted(ROOT.glob("*/src/main/kotlin/**/*.kt"))
print("     Kotlin 文件 %d 个" % len(kt_files))
mismatch = 0
for f in kt_files:
    m = re.search(r"^\s*package\s+([\w.]+)", f.read_text(encoding="utf-8"), re.M)
    if not m:
        bad("无 package 声明: %s" % f.relative_to(ROOT))
        mismatch += 1
        continue
    pkg = m.group(1)
    rel = f.parent.relative_to(ROOT)
    parts = list(rel.parts)
    # <module>/src/main/kotlin/a/b/c  -> 包名应为 a.b.c
    idx = parts.index("kotlin")
    expect = ".".join(parts[idx + 1:])
    if pkg != expect:
        bad("包名不匹配 %s\n        声明=%s\n        路径=%s" % (f.relative_to(ROOT), pkg, expect))
        mismatch += 1
if not mismatch:
    ok("全部包名与目录一致")

# ---------------------------------------------------------------- 4. XML / TOML
print("\n== 4. XML 与 TOML 语法 ==")
xml_n = 0
for f in sorted(ROOT.rglob("*.xml")):
    try:
        ET.parse(f)
        xml_n += 1
    except ET.ParseError as e:
        bad("XML 解析失败 %s: %s" % (f.relative_to(ROOT), e))
ok("%d 个 XML 文件格式良好" % xml_n if not problems else "见上方")

catalog_path = ROOT / "gradle" / "libs.versions.toml"
try:
    with catalog_path.open("rb") as fp:
        catalog = tomllib.load(fp)
    ok("libs.versions.toml 可解析（versions=%d libraries=%d plugins=%d）"
       % (len(catalog.get("versions", {})), len(catalog.get("libraries", {})),
          len(catalog.get("plugins", {}))))
except Exception as e:
    catalog = {}
    bad("libs.versions.toml 解析失败: %s" % e)

# ---------------------------------------------------------------- 5. libs 访问器
print("\n== 5. 版本目录访问器引用 ==")
lib_keys = set(catalog.get("libraries", {}))
plugin_keys = set(catalog.get("plugins", {}))
unknown: list[tuple[str, str]] = []
for f in sorted(ROOT.glob("**/build.gradle.kts")) + sorted(ROOT.glob("buildSrc/build.gradle.kts")):
    txt = strip_kts_comments(f.read_text(encoding="utf-8"))
    for ref in set(re.findall(r"libs\.((?:[\w]+\.)*[\w]+)", txt)):
        # 访问器与 toml 段的对应关系（写错这里会误报，Gradle 的规则就是下面三条）：
        #   libs.versions.x    -> [versions]  key = x
        #   libs.plugins.a.b   -> [plugins]   key = a-b      （注意：不带 plugins- 前缀）
        #   libs.a.b.c         -> [libraries] key = a-b-c
        if ref.startswith("versions."):
            key, pool, seg = ref[len("versions."):], set(catalog.get("versions", {})), "versions"
        elif ref.startswith("plugins."):
            key, pool, seg = ref[len("plugins."):].replace(".", "-"), plugin_keys, "plugins"
        else:
            key, pool, seg = ref.replace(".", "-"), lib_keys, "libraries"
        if key not in pool:
            unknown.append((str(f.relative_to(ROOT)), "libs.%s  →  [%s] %s" % (ref, seg, key)))
for where, ref in unknown:
    bad("访问器不存在: libs.%s  （%s）" % (ref, where))
if not unknown:
    ok("全部 libs.* 访问器都能在版本目录里找到")

# ---------------------------------------------------------------- 汇总
print("\n" + "=" * 62)
if problems:
    print("发现 %d 个问题" % len(problems))
    sys.exit(1)
print("静态自检全部通过")
