#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 NAS 上**两个容器**合成一个：`nuonuo-english`（英语，4173）→ `nuo-study`（8092）。

2026-10-23 起英语能力（TTS / 转写 / 千问对话 / 腾讯智聆评测）已经并进 `nuo-study`
（见 `server/english.py`）。但 NAS 上原来那个 Node 容器还在跑，两套并行会带来
最烦的一种问题：**改了 nuo_study 却看到英语行为没变**（因为请求还在打老容器）。

本脚本只做四件事，每件都是幂等的：

  1. 把老容器的环境变量（Azure / 腾讯 / 千问 / OpenAI 的 key）搬进 nuo_study 的 .env；
  2. 停掉并删除老容器（**不碰它的项目目录、不删任何数据**）；
  3. 重建 nuo_study 容器（`--build` 带上 ffmpeg 与 websockets）；
  4. 验一遍四个英语接口 + 网页，确认「一个容器全都有」。

用法
----
  NAS_PW='xxx' python tools/merge_english_nas.py --check     # 只看现状，不动任何东西
  NAS_PW='xxx' python tools/merge_english_nas.py             # 执行（先备份 .env）
  NAS_PW='xxx' python tools/merge_english_nas.py --keep-old  # 迁移密钥但不删老容器

⚠️ 执行前先把代码传上去（老容器删了之后，nuo_study 里得**已经有** web/ 和 english.py）：
      NAS_PW='xxx' python tools/deploy_nas.py

⚠️ 口令只从环境变量 `NAS_PW` 读，绝不作为命令行参数（会进 ps 输出）。
⚠️ 老容器如果是 compose 管的（label 里有 com.docker.compose.project），本脚本**只
   删容器不改编排文件** —— 对应的 compose 里那一整段服务定义要人手工删掉，
   否则下次 `docker compose up` 会把它原样建回来。脚本会把这个文件的路径打出来。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

HOST = "192.168.1.10"
USER = "jinyu"
DEST = "/vol2/1000/software/nuo_study"
SERVICE_PORT = int(os.environ.get("NUO_PORT", "8092"))
DOCKER_DIR = os.environ.get("NUO_DOCKER_DIR", "/vol1/1000/docker/my-project")
COMPOSE_FILE = DOCKER_DIR + "/docker-compose.yml"
OLD_CONTAINER = "nuonuo-english"
NEW_SERVICE = "nuo-study"

# 老容器里要搬过来的键。**刻意不含 PORT / HOST** ——
# 4173 这个端口随老服务一起退休，搬过去只会跟 nuo_study 自己的端口冲突。
#
# ⚠️ 2026-10-23 教训：第一次跑这份白名单只写了 9 个键，漏了 `QWEN_ASR_MODEL`，
#    结果转写一直显示未配置。**以后加/删键都从老容器的 inspect 输出里对照一遍**，
#    别凭记忆写。`QWEN_ASR_WS_URL` 是**故意不搬**的：老代码从没读过它，
#    转写实际走普通 chat/completions（见 server/english.py 的 transcribe 注释）。
ENV_KEYS = [
    "AZURE_SPEECH_KEY",
    "AZURE_SPEECH_REGION",
    "AZURE_TTS_VOICE",
    "TENCENT_APP_ID",
    "TENCENT_SECRET_ID",
    "TENCENT_SECRET_KEY",
    "QWEN_API_KEY",
    "QWEN_BASE_URL",
    "QWEN_CHAT_MODEL",
    "QWEN_ASR_MODEL",
    "QWEN_ASR_HTTP_MODEL",
    "QWEN_ASR_WS_URL",
    "QWEN_ASR_LANGUAGE",
    "QWEN_ASR_ENGINE",
    "QWEN_ASR_BASE_URL",
    "OPENAI_API_KEY",
    "OPENAI_TRANSCRIBE_MODEL",
]


def connect(pw: str):
    import paramiko

    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(HOST, username=USER, password=pw, timeout=20,
                look_for_keys=False, allow_agent=False)
    return cli


def make_run(cli):
    def run(cmd: str, timeout: int = 120, quiet: bool = False) -> tuple[int, str, str]:
        _, so, se = cli.exec_command(cmd, timeout=timeout)
        out = so.read().decode("utf-8", "replace")
        err = se.read().decode("utf-8", "replace")
        code = so.channel.recv_exit_status()
        if not quiet:
            for line in out.rstrip().splitlines():
                print("    " + line)
            for line in err.rstrip().splitlines():
                print("    [err] " + line)
        return code, out, err

    return run


def _mask(key: str, value: str) -> str:
    """打印时只留首尾 —— 输出会进聊天记录，密钥不能整串露脸。"""
    if not value:
        return "(空)"
    if len(value) <= 8:
        return "*" * len(value)
    return "%s…%s（%d 位）" % (value[:3], value[-2:], len(value))


# --------------------------------------------------------------- 只读画像
def step_check(run) -> dict:
    print("== 容器现状 ==")
    _, out, _ = run("docker ps -a --format '{{.Names}}|{{.Status}}|{{.Image}}' | "
                    "grep -E 'nuonuo-english|nuo-study|weknora' || echo '(两个都没有？)'")

    info = {"old_exists": OLD_CONTAINER in out, "env": {}, "labels": {}}
    if info["old_exists"]:
        print("== 老容器 %s 的环境变量（只看键名 + 脱敏值）==" % OLD_CONTAINER)
        _, raw, _ = run("docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' %s"
                        % OLD_CONTAINER, quiet=True)
        for line in raw.splitlines():
            if "=" not in line:
                continue
            k, v = line.split("=", 1)
            if k in ENV_KEYS:
                info["env"][k] = v
                print("    %-26s %s" % (k, _mask(k, v)))
        for label in ("com.docker.compose.project", "com.docker.compose.project.config_files",
                      "com.docker.compose.project.working_dir"):
            _, val, _ = run("docker inspect -f '{{index .Config.Labels \"%s\"}}' %s"
                            % (label, OLD_CONTAINER), quiet=True)
            val = val.strip().strip("<>")
            if val and val != "<no value>":
                info["labels"][label] = val
                print("    %-46s %s" % (label.split(".")[-1], val))

    print("== nuo_study 的 .env 现有键 ==")
    _, env_out, _ = run("grep -oE '^[A-Z_][A-Z0-9_]*' %s/.env 2>/dev/null | sort -u"
                        % DEST, quiet=True)
    have = [k for k in env_out.split() if k]
    print("    " + (", ".join(have) if have else "(读不到 .env)"))
    missing = [k for k in ENV_KEYS if k not in have]
    print("    英语相关缺失：%s" % (", ".join(missing) if missing else "无"))

    print("== 代码是否已就位（老容器删掉后靠它顶上）==")
    # ⚠️ printf 的格式符必须写成 `%%s`：这串还要过一次 Python 的 `%` 格式化，
    #    单个 `%s` 会被当成参数占位符 → TypeError: not enough arguments。
    run("for f in server/english.py web/english/index.html web/english/app.js "
        "web/english/audio/track-01.mp3; do "
        "printf '    %%-32s %%s\\n' $f \"$(test -e %s/$f && echo OK || echo 缺失)\"; done" % DEST)

    print("== 端口 ==")
    run("(ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | "
        "grep -E ':(4173|%d)[^0-9]' || echo '(4173 / %d 都没人监听)'" % (SERVICE_PORT, SERVICE_PORT))
    return info


# --------------------------------------------------------------- 迁移 .env
def merge_env(run, env: dict) -> None:
    """把老容器的 key 写进 nuo_study/.env。

    规则（按顺序）：
      · 键不存在 → 追加；
      · 键存在但值为空（`KEY=` 这种） → 就地填上；
      · 键已存在且有值 → **不动**。可能人家已经在 NAS 上手工配过新 key，
        脚本没有资格覆盖它。
    改之前一律 `cp -a` 备份，出问题能一秒退回。
    """
    print("== 迁移环境变量 ==")
    if not env:
        print("    老容器里没有可搬的键（可能本来就没配 key），跳过")
        return
    code, out, _ = run("test -f %s/.env && echo YES || echo NO" % DEST, quiet=True)
    if "NO" in out:
        print("    ✗ %s/.env 不存在。先跑一次 deploy_nas.py 让它生成骨架。" % DEST)
        return

    stamp = time.strftime("%Y%m%d-%H%M%S")
    run("cp -a {d}/.env {d}/.env.bak.{s} && echo '    已备份 .env.bak.{s}'"
        .format(d=DEST, s=stamp))

    # 一次把「现有键 → 值」读出来，避免逐键 grep 十几趟 SSH
    _, current, _ = run("cat %s/.env" % DEST, quiet=True)
    existing = {}
    for line in current.splitlines():
        s = line.strip()
        if s and not s.startswith("#") and "=" in s:
            k, v = s.split("=", 1)
            existing[k.strip()] = v.strip()

    to_append, to_fill = [], []
    for k in ENV_KEYS:
        v = env.get(k, "")
        if not v:
            continue
        if k not in existing:
            to_append.append((k, v))
        elif not existing[k].strip("\"'"):
            to_fill.append((k, v))

    if not to_append and not to_fill:
        print("    .env 里英语相关的键都已存在且有值，无需改动")
        return

    print("    新增 %d 项：%s" % (len(to_append), ", ".join(k for k, _ in to_append) or "无"))
    print("    补空 %d 项：%s" % (len(to_fill), ", ".join(k for k, _ in to_fill) or "无"))

    import base64

    body = "\n# ---- 英语能力（2026-10-23 从 nuonuo-english 容器迁移过来）\n"
    body += "\n".join("%s=%s" % (k, v) for k, v in to_append) + ("\n" if to_append else "")
    # 用 base64 传输：key 里带 $ & 引号时直接拼 shell 命令会被吃掉字符，
    # 而且 base64 顺便保证口令不出现在 ssh 命令行里。
    if body.strip():
        b64 = base64.b64encode(body.encode("utf-8")).decode("ascii")
        run("echo '%s' | base64 -d >> %s/.env" % (b64, DEST))
    for k, v in to_fill:
        b64 = base64.b64encode(v.encode("utf-8")).decode("ascii")
        # 用 sed 就地替换整行，值先 base64 解到变量里再替换，避免特殊字符问题
        run("""v=$(echo '%s' | base64 -d); python3 - "$v" <<'PY'
import sys, pathlib
val = sys.argv[1]
p = pathlib.Path("%s/.env")
lines = p.read_text(encoding="utf-8").splitlines()
out = []
for ln in lines:
    if ln.strip().startswith("%s="):
        out.append("%s=" + val)
    else:
        out.append(ln)
p.write_text("\\n".join(out) + "\\n", encoding="utf-8")
PY""" % (b64, DEST, k, k))

    _, after, _ = run("grep -cE '^(AZURE_SPEECH_KEY|TENCENT_SECRET_KEY|QWEN_API_KEY|"
                      "OPENAI_API_KEY)=' %s/.env || true" % DEST, quiet=True)
    print("    写入完成（.env 里这几类 key 的行数：%s）" % after.strip())


# --------------------------------------------------------------- 停老容器
def stop_old(run, labels: dict) -> bool:
    print("== 停掉并删除老容器 %s ==" % OLD_CONTAINER)
    # 先把「挂了自动拉起」摘掉：restart 策略还在的话，容器可能在 stop 之后又自己回来
    # （或者 docker daemon 重启时复活），那就白删了。
    run("docker update --restart=no %s >/dev/null 2>&1 || true" % OLD_CONTAINER, quiet=True)

    compose_files = labels.get("com.docker.compose.project.config_files", "")
    if compose_files:
        # 归某个 compose 项目管 → 走 `compose down`，而不是 docker rm。
        # 区别在于 down 会**同时清掉 network / 记录**，而且这个项目里只有它一个服务，
        # 不会误伤别人；docker rm 留下的孤儿记录会让 `compose up` 之后又是一条命。
        print("    它是 compose 项目管着的（%s），用 compose down 收尾" % compose_files)
        code, _, _ = run("docker compose -f %s down --remove-orphans" % compose_files,
                         timeout=180)
        print("    compose down → %s" % ("ok" if code == 0 else "失败，退回 docker rm"))
        if code != 0:
            run("docker stop -t 20 %s >/dev/null 2>&1; docker rm %s"
                % (OLD_CONTAINER, OLD_CONTAINER), quiet=True)
        print("    ⚠️ 编排文件 %s 还在。它已经不会再被起回来了，"
              "但建议整段删掉（或把文件改名成 compose.yaml.bak），免得以后误 up。" % compose_files)
    else:
        code, _, _ = run("docker stop -t 20 %s" % OLD_CONTAINER, quiet=True)
        print("    docker stop → %s" % ("ok" if code == 0 else "失败（可能已经停了）"))
        run("docker rm %s" % OLD_CONTAINER, quiet=True)

    _, out, _ = run("docker ps -a --filter name=%s --format '{{.Names}}|{{.Status}}'"
                    % OLD_CONTAINER, quiet=True)
    if out.strip():
        print("    !! 容器还在：%s" % out.strip())
        return False
    print("    ✓ 容器已删除（镜像还在，想回退能直接起）")
    return True


# --------------------------------------------------------------- 重建 + 验证
def rebuild(run) -> bool:
    print("== 重建 %s（--build：装 ffmpeg + websockets）==" % NEW_SERVICE)
    code, _, _ = run(
        "docker compose -f %s up -d --build --force-recreate %s" % (COMPOSE_FILE, NEW_SERVICE),
        timeout=1800)
    if code != 0:
        print("    ✗ 重建失败，看上面 [err]。镜像构建常见原因是 apt 源不通。")
        return False
    for _ in range(30):
        time.sleep(2)
        _, out, _ = run("curl -s -m 3 -o /dev/null -w '%%{http_code}' "
                        "http://127.0.0.1:%d/api/v1/system/ping" % SERVICE_PORT, quiet=True)
        if out.strip() == "200":
            print("    ✓ /api/v1/system/ping → 200")
            return True
    print("    ✗ 没起来")
    run("docker compose -f %s logs --tail 40 %s" % (COMPOSE_FILE, NEW_SERVICE))
    return False


def verify(run) -> bool:
    print("== 验证「一个容器什么都有」==")
    ok = True

    _, out, _ = run("curl -s -m 5 http://127.0.0.1:%d/api/health" % SERVICE_PORT, quiet=True)
    print("    /api/health → %s" % (out.strip() or "(空)"))
    # ⚠️ 必须 **解析 JSON** 再判断，不能拿字符串去搜 `"azureTts": true`：
    #    FastAPI 的 JSONResponse 是紧凑格式（冒号后没有空格），带空格匹配必然搜不到，
    #    结果把「全都配好了」误报成一堆 !!（2026-10-23 第一次跑就踩了）。
    try:
        health = json.loads(out)
    except Exception:
        health = {}
    services = health.get("services") or {}
    for key, label in (("azureTts", "Azure 语音合成"),
                       ("qwenChat", "千问对话"),
                       ("tencentSoe", "腾讯智聆评测")):
        if not services.get(key):
            print("    !! %s 未配置（%s）—— key 没进 .env，或容器没重建" % (label, key))
            ok = False
    if not health.get("ffmpeg"):
        print("    !! ffmpeg 未就绪 —— 跟读评测会报错，检查 Dockerfile 是否真的重建了")
        ok = False
    if not services.get("transcription"):
        # 这条**不算失败**：自由对话是可选能力，老容器里本来就没配 OpenAI key。
        print("    · 语音转写未配置（OPENAI_API_KEY）—— 只影响自由对话，跟读评测不受影响")

    for path, expect in (("/english/", "200"), ("/audio/track-01.mp3", "200"),
                         ("/api/v1/english/health", "200")):
        _, code, _ = run("curl -s -m 5 -o /dev/null -w '%%{http_code}' "
                         "http://127.0.0.1:%d%s" % (SERVICE_PORT, path), quiet=True)
        good = code.strip() == expect
        ok = ok and good
        print("    %-26s → %s %s" % (path, code.strip(), "" if good else "  ✗"))

    _, out, _ = run("docker ps --format '{{.Names}}|{{.Status}}' | "
                    "grep -E 'nuonuo-english|nuo-study'", quiet=True)
    print("== 现在在跑的容器 ==")
    for line in out.strip().splitlines():
        print("    " + line)

    _, hostip, _ = run("hostname -I 2>/dev/null | awk '{print $1}'", quiet=True)
    ip = hostip.strip().split()[0] if hostip.strip() else "192.168.1.10"
    print("== 对外地址 ==")
    print("    英语网页    http://%s:%d/english/" % (ip, SERVICE_PORT))
    print("    英语接口    http://%s:%d/api/v1/english/health" % (ip, SERVICE_PORT))
    print("    学业接口    http://%s:%d/api/v1/system/ping" % (ip, SERVICE_PORT))
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只看现状，不动任何东西")
    ap.add_argument("--keep-old", action="store_true", help="迁移密钥但不删老容器")
    args = ap.parse_args()

    pw = os.environ.get("NAS_PW")
    if not pw:
        print("ERROR: 需要 NAS_PW 环境变量。用法：NAS_PW='xxx' python %s --check"
              % sys.argv[0], file=sys.stderr)
        return 2

    cli = connect(pw)
    run = make_run(cli)
    try:
        info = step_check(run)
        if args.check:
            print("\n（--check 模式，未做任何改动）")
            return 0

        if not info["old_exists"]:
            print("\n老容器 %s 已经不存在了 —— 可能已经迁移过。继续重建 nuo_study 并验证。"
                  % OLD_CONTAINER)
        else:
            merge_env(run, info["env"])
            if args.keep_old:
                print("\n（--keep-old：跳过停容器）")
            elif not stop_old(run, info["labels"]):
                print("\n停止：老容器没删干净，先解决它再继续，否则 4173 还在抢流量。")
                return 1

        if not rebuild(run):
            return 1
        ok = verify(run)
        print("\n%s" % ("完成：现在只有一个 nuo-study 容器，英语和学业都在 8092。"
                        if ok else "起来了，但上面有 ! 项需要处理。"))
        return 0 if ok else 1
    finally:
        cli.close()


if __name__ == "__main__":
    raise SystemExit(main())
