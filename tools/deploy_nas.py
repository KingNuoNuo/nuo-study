#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把服务端部署到 NAS：192.168.1.10:/vol2/1000/software/nuo_study

设计约束
--------
· 口令只从环境变量 `NAS_PW` 读，**绝不作为命令行参数**（参数会进 ps 输出被同机用户看到）。
· `.env` 只在远端**不存在**时上传；已存在就跳过，避免把 NAS 上改过的配置冲掉。
· `data/` 是运行时数据（SQLite + 图片），打包时永远不含，解包也不会删。
· 解包是**增量覆盖**，不 `rm -rf` 目标目录 —— 传错了也只是多几个旧文件，不会丢数据。

用法
----
  NAS_PW='xxx' python tools/deploy_nas.py --check      # 远端环境画像，只读
  NAS_PW='xxx' python tools/deploy_nas.py --pack       # 只在本地打包，不连远端
  NAS_PW='xxx' python tools/deploy_nas.py              # 上传 + 解包
  NAS_PW='xxx' python tools/deploy_nas.py --install    # 上面 + 建 venv + 装依赖
  NAS_PW='xxx' python tools/deploy_nas.py --start      # 后台拉起 + 健康检查
  NAS_PW='xxx' python tools/deploy_nas.py --stop       # 停掉
  NAS_PW='xxx' python tools/deploy_nas.py --log        # 看最近日志

本机用的是装了 paramiko 的那个解释器：
  C:\\Users\\yuhao\\.workbuddy\\binaries\\python\\envs\\default\\Scripts\\python.exe
"""
from __future__ import annotations

import argparse
import os
import sys
import tarfile
import tempfile
import time
from pathlib import Path

# Windows 下 stdout 重定向到文件时编码是 GBK，打不出「✓」「↑」这类字符会直接
# UnicodeEncodeError 崩掉 —— 且崩在**部署成功之后**的打印上，看似部署失败。
# 强制 UTF-8 + 容错替换，一劳永逸（2026-10-12 实测踩坑）。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]

HOST = "192.168.1.10"
USER = "jinyu"
DEST = "/vol2/1000/software/nuo_study"
# ⚠️ 不是 8080：NAS 上 8080 已被一个需要认证的 JSON API 占着（401 + X-Request-Id）。
# 8091 是 Octop、8081 是 WeKnora，所以取 8092。改这里要同步改远端 .env 的 SERVER_PORT
# 以及家长端「设置」页填的服务端地址。
SERVICE_PORT = int(os.environ.get("NUO_PORT", "8092"))

# 上远端的内容。刻意不含 data/（运行时数据）与 .env（单独判断）。
PACK_MEMBERS = [
    "run_server.py",
    "requirements.txt",
    "README.md",
    "server",
    "db",
    "docs/prompts",
    # 英语网页（2026-10-23 融合进来的那一半）：
    # 页面与脚本每次都同步（几十 KB，改了立刻生效）；
    # 40 段课文录音（82 MB）**不在常规打包里** —— 见 ensure_audio()，
    # 那一堆文件装一次就再也不变，每次部署重传纯属浪费带宽和时间。
    "web",
]

# 课文录音相对项目根的路径。82 MB / 40 个文件，部署时按「远端有没有」决定要不要传。
AUDIO_REL = "web/english/audio"


def _audio_files() -> list[Path]:
    d = ROOT / AUDIO_REL
    return sorted(d.glob("*.mp3")) if d.is_dir() else []


PIP_INDEX = "https://mirrors.aliyun.com/pypi/simple/"


# --------------------------------------------------------------- 本地打包
def pack(tar_path: Path) -> Path:
    def skip(ti: tarfile.TarInfo):
        if "__pycache__" in ti.name or ti.name.endswith((".pyc", ".pyo")):
            return None
        # 课文录音单独走 ensure_audio()，不进常规包（见 PACK_MEMBERS 的注释）
        if ti.name.startswith(AUDIO_REL):
            return None
        return ti

    missing = []
    with tarfile.open(tar_path, "w:gz") as tf:
        for m in PACK_MEMBERS:
            p = ROOT / m
            if not p.exists():
                missing.append(m)
                continue
            tf.add(p, arcname=m, filter=skip)

    size = tar_path.stat().st_size
    print("  已打包 %s（%.1f KB）" % (tar_path.name, size / 1024))
    for m in missing:
        print("  !! 本地缺失，已跳过: %s" % m)
    return tar_path


def pack_audio(tar_path: Path) -> Path:
    """只打课文录音。**换了版本才需要重传**，所以单独一个包。"""
    with tarfile.open(tar_path, "w:gz") as tf:
        for f in _audio_files():
            tf.add(f, arcname="%s/%s" % (AUDIO_REL, f.name))
    return tar_path


# --------------------------------------------------------------- 远端连接
def connect(pw: str):
    import paramiko

    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(HOST, username=USER, password=pw, timeout=20,
                look_for_keys=False, allow_agent=False)
    return cli


def make_run(cli):
    def run(cmd: str, timeout: int = 120, quiet: bool = False):
        _, so, se = cli.exec_command(cmd, timeout=timeout)
        out = so.read().decode("utf-8", "replace")
        err = se.read().decode("utf-8", "replace")
        code = so.channel.recv_exit_status()
        if not quiet:
            if out.strip():
                for line in out.rstrip().splitlines():
                    print("    " + line)
            if err.strip():
                for line in err.rstrip().splitlines():
                    print("    [err] " + line)
        return code, out, err

    return run


# --------------------------------------------------------------- 各步骤
ENV_FALLBACK = """\
# NAS 部署配置。模型相关键由本机 .env 带上来；改完要重启服务才生效。
SERVER_HOST=0.0.0.0
SERVER_PORT=8092
FAMILY_TOKEN=change-me-on-nas
VISION_BASE_URL=https://token.sensenova.cn/v1
VISION_MODEL=sensenova-6.8-flash-lite
VISION_THINKING=off
VISION_MAX_TOKENS=32000
VISION_TIMEOUT=180
"""


def build_nas_env() -> tuple[str, list[str]]:
    """生成本次部署要写到远端的 .env。

    以本机 .env 为底，强制覆盖 SERVER_HOST=0.0.0.0 与 SERVER_PORT=<SERVICE_PORT>；
    本机没有 .env 时退回 ENV_FALLBACK 骨架。返回 (内容, 键名列表) 供打印用。
    """
    local = ROOT / ".env"
    raw = (local.read_text(encoding="utf-8").splitlines() if local.exists()
           else ENV_FALLBACK.splitlines())

    out: list[str] = []
    seen_host = seen_port = False
    for line in raw:
        s = line.strip()
        if s.startswith("SERVER_HOST="):
            out.append("SERVER_HOST=0.0.0.0")
            seen_host = True
        elif s.startswith("SERVER_PORT="):
            out.append("SERVER_PORT=%d" % SERVICE_PORT)
            seen_port = True
        else:
            out.append(line)
    if not seen_host:
        out.insert(0, "SERVER_HOST=0.0.0.0")
    if not seen_port:
        out.insert(1, "SERVER_PORT=%d" % SERVICE_PORT)

    keys = [ln.split("=", 1)[0].strip() for ln in out
            if ln.strip() and not ln.strip().startswith("#") and "=" in ln]
    return "\n".join(out) + "\n", keys


def step_check(run) -> None:
    print("== 远端环境画像 ==")
    run("uname -a; echo; cat /etc/os-release 2>/dev/null | head -3")
    run("python3 -V; command -v python3; command -v curl || echo '(无 curl)'")
    run("python3 -c 'import venv; print(\"venv ok\")' 2>&1 | tail -1")
    run("df -h /vol2 | tail -1")
    run("ls -la %s 2>&1 | head -20" % DEST)
    print("  -- 部署方式 --")
    if has_docker_deploy(run):
        run("docker --version 2>&1 | head -1")
        cid = _cid(run)
        if cid:
            run("docker inspect %s --format '    restart={{.HostConfig.RestartPolicy.Name}} "
                "net={{.HostConfig.NetworkMode}} user={{.Config.User}}' 2>/dev/null || true"
                % cid)
        print("    -- 该项目下的全部服务 --")
        run("docker compose -f %s ps -a --format '    {{.Name}} | {{.Status}} | {{.Image}}' "
            "2>/dev/null || docker ps -a --format '    {{.Names}} | {{.Status}} | {{.Image}}'"
            % COMPOSE_FILE)
        print("    => 容器化（%s）" % COMPOSE_FILE)
    else:
        print("    => nohup 裸进程（未发现 %s）" % COMPOSE_FILE)
    print("  -- 端口占用（%d 被谁用了）--" % SERVICE_PORT)
    run("(ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep -E ':%d\\b' || echo '(%d 空闲)'"
        % (SERVICE_PORT, SERVICE_PORT))
    if SIBLING_PORTS:
        print("  -- 同项目兄弟服务的端口 --")
        run("(ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep -E '%s' || echo '(未监听)'"
            % "|".join(":%d\\b" % p for p in SIBLING_PORTS))
        run(_compose("ps -a --format '    {{.Name}} | {{.Status}} | {{.Image}}'"))


def step_upload(cli, run, tar_path: Path) -> None:
    print("== 上传 ==")
    remote_tar = "/tmp/%s" % tar_path.name
    sf = cli.open_sftp()
    t0 = time.time()
    sf.put(str(tar_path), remote_tar)
    sf.close()
    print("    已传到 %s（%.1fs）" % (remote_tar, time.time() - t0))

    print("== 解包到 %s ==" % DEST)
    run("mkdir -p %s" % DEST)
    # 增量覆盖，不删已有文件（data/ 与旧版本残留都会留着）
    run("tar -xzf %s -C %s && echo '  解包完成'" % (remote_tar, DEST))
    run("rm -f %s" % remote_tar)

    # .env 只在远端没有时补一份
    code, out, _ = run("test -f %s/.env && echo EXISTS || echo MISSING" % DEST, quiet=True)
    if "EXISTS" in out:
        local_env = ROOT / ".env"
        print("== .env ==")
        print("    远端已有 .env，未覆盖。本机 .env 的模型键需人工核对是否一致：")
        if local_env.exists():
            for line in local_env.read_text(encoding="utf-8").splitlines():
                s = line.strip()
                if s.startswith(("VISION_BASE_URL", "VISION_MODEL", "VISION_THINKING",
                                 "VISION_MAX_TOKENS", "VISION_TIMEOUT", "SERVER_PORT")):
                    print("      本机 " + s)
    else:
        print("== .env ==")
        # 从本机 .env 带过去：只覆盖 SERVER_HOST / SERVER_PORT，模型 key 与
        # FAMILY_TOKEN 原样搬。⚠️ 别用占位模板 —— 缺 VISION_API_KEY 会让每次识别
        # 都在运行时才失败，而服务本身是能起来的，排查起来很绕。
        content, keys = build_nas_env()
        sf = cli.open_sftp()
        with sf.open("%s/.env" % DEST, "w") as f:
            f.write(content)
        sf.close()
        print("    远端没有 .env，已从本机带过去，写入的键：%s" % ", ".join(keys))
        print("    （只覆盖了 SERVER_HOST / SERVER_PORT，密钥未回显）")

    run("ls -la %s" % DEST)
    ensure_audio(cli, run)
    sync_deploy_files(cli, run)


def ensure_audio(cli, run) -> None:
    """课文录音（40 段 / 约 82 MB）只在远端缺失时补传一次。

    幂等判据用**文件个数**而不是逐字节比对：这批文件装一次就再也不变
    （换教材时文件名本身就变了），而 82 MB 每次部署都重传要等半分多钟。
    个数够了就跳过；不够说明上次传一半断了，按个数补一个完整包更省事。
    """
    local = _audio_files()
    if not local:
        print("    本地没有 %s，跳过" % AUDIO_REL)
        return
    _, out, _ = run("ls %s/%s 2>/dev/null | wc -l" % (DEST, AUDIO_REL), quiet=True)
    have = int((out or "0").strip() or 0)
    if have >= len(local):
        print("== 课文录音 ==")
        print("    远端已有 %d/%d 段，跳过" % (have, len(local)))
        return
    total_mb = sum(f.stat().st_size for f in local) / 1024 / 1024
    print("== 课文录音 ==")
    print("    远端 %d/%d 段，补传（%.0f MB）…" % (have, len(local), total_mb))
    stamp = time.strftime("%Y%m%d-%H%M%S")
    tar_path = Path(tempfile.gettempdir()) / ("nuo-audio-%s.tar.gz" % stamp)
    pack_audio(tar_path)
    remote = "/tmp/%s" % tar_path.name
    t0 = time.time()
    sf = cli.open_sftp()
    sf.put(str(tar_path), remote)
    sf.close()
    run("mkdir -p %s/%s" % (DEST, AUDIO_REL))
    run("tar -xzf %s -C %s && rm -f %s" % (remote, DEST, remote))
    print("    已传 %d 段（%.1fs）" % (len(local), time.time() - t0))


# 本机的编排文件副本，唯一事实来源
LOCAL_DEPLOY = ROOT / "deploy"


def sync_deploy_files(cli, run) -> None:
    """把本机 deploy/ 下的编排文件同步到 NAS 的 DOCKER_DIR。

    容器化之后「代码」其实有两份，别搞混：
      · 业务代码在 DEST —— 走卷挂载，SFTP 覆盖即生效，不需要重建镜像；
      · 编排在 DOCKER_DIR（Dockerfile / compose / requirements）—— 构建镜像要用。
    只在 NAS 上手工改编排，下次部署就跟本机副本不一致了，之后没人说得清哪份是对的。
    所以：**以本机 deploy/ 为准，每次部署顺手推过去**。

    ⚠️ requirements.txt 也得放 DOCKER_DIR —— Dockerfile 的 build context 就是
       那儿，镜像里 `pip install -r` 读的是它，不是 DEST 里那份。
    """
    if not LOCAL_DEPLOY.is_dir():
        return
    print("== 编排文件 ==")
    run("mkdir -p %s" % DOCKER_DIR, quiet=True)
    sf = cli.open_sftp()
    items = [("Dockerfile", LOCAL_DEPLOY / "Dockerfile"),
             ("docker-compose.yml", LOCAL_DEPLOY / "docker-compose.yml"),
             ("requirements.txt", ROOT / "requirements.txt")]
    for name, src in items:
        if not src.exists():
            print("    !! 本机缺 %s，跳过" % src)
            continue
        try:
            sf.put(str(src), "%s/%s" % (DOCKER_DIR, name))
            print("    ↑ %s" % name)
        except OSError as e:                              # noqa: PERF203
            print("    !! %s 上传失败：%s" % (name, e))
    sf.close()


def step_install(run) -> None:
    if has_docker_deploy(run):
        print("== 依赖 ==")
        print("    容器化部署：依赖在镜像里（%s/Dockerfile），远端不需要 venv。"
              % DOCKER_DIR)
        print("    英语那半边还额外要 websockets（requirements.txt）和 ffmpeg（apt），"
              "两者都已写进 Dockerfile。")
        print("    改了 requirements.txt / Dockerfile 的话跑一次 --start，它的 --build 会重装。")
        return
    print("== 建 venv + 装依赖（阿里云源）==")
    code, out, _ = run("test -x {d}/.venv/bin/python && echo HAVE || echo NEED".format(d=DEST),
                       quiet=True)
    if "HAVE" in out:
        print("    .venv 已存在，跳过创建")
    else:
        code, _, _ = run("cd %s && python3 -m venv .venv && echo '  venv 已建'" % DEST,
                         timeout=180)
        if code != 0:
            print("    ✗ 建 venv 失败。fnOS 可能缺 python3-venv，需要先在 NAS 上装。")
            return
    # 长命令走「远端后台跑 + 轮询日志」，避免 SSH 通道干等超时
    run("cd {d} && nohup ./.venv/bin/pip install -r requirements.txt "
        "-i {idx} > /tmp/nuo_pip.log 2>&1 & echo started".format(d=DEST, idx=PIP_INDEX),
        quiet=True)
    print("    已后台启动 pip，轮询中…")
    for i in range(60):
        time.sleep(5)
        _, tail, _ = run("tail -c 300 /tmp/nuo_pip.log; echo; "
                         "grep -cE 'Successfully installed|already satisfied' /tmp/nuo_pip.log "
                         "2>/dev/null || echo 0", quiet=True)
        done = None
        code, o, _ = run("test -x {d}/.venv/bin/uvicorn && echo UVICORN_OK || echo NOT_YET"
                         .format(d=DEST), quiet=True)
        if "UVICORN_OK" in o:
            done = True
        if done:
            print("    ✓ 依赖装好")
            break
        if i % 6 == 5:
            print("    …仍在装（%.0fs）" % ((i + 1) * 5))
    else:
        print("    ✗ 超时。远端日志：")
        run("tail -30 /tmp/nuo_pip.log")
        return

    print("== 依赖自检 ==")
    # ⚠️ 别在 -c 的代码里用 "\n" 换行 —— 这层是 shell，反斜杠不会变成真换行，
    # Python 会把 \n 当成「行继续符后面还有字符」直接 SyntaxError。全程用 ";" 串。
    run("cd {d} && ./.venv/bin/python -c "
        "'import fastapi,uvicorn,pydantic,multipart;"
        "print(\"fastapi\",fastapi.__version__,\"uvicorn\",uvicorn.__version__,"
        "\"pydantic\",pydantic.__version__,"
        "\"multipart\",getattr(__import__(\"multipart\"),\"__version__\",\"?\"));"
        "import PIL;print(\"Pillow\",PIL.__version__)'".format(d=DEST))
    # 英语四件事里的两件要额外依赖：评测要 websockets，评测还要 ffmpeg 转 PCM。
    # 缺了不影响服务启动（english.py 是函数内 import），但功能会静默降级 ——
    # 所以在这里明确报一句，别等孩子点了按钮才发现。
    run("cd {d} && ./.venv/bin/python -c "
        "'import importlib.util as u;print(\"websockets\","
        "\"OK\" if u.find_spec(\"websockets\") else \"缺失！跟读评测不可用\")'".format(d=DEST))
    run("command -v ffmpeg >/dev/null && ffmpeg -version | head -1 "
        "|| echo 'ffmpeg 缺失！跟读评测不可用（容器镜像里已含，裸进程部署要自己装）'")


# ⚠️ 停服务的正确姿势（踩过一次，代价是一整轮「改了代码却不生效」的排查）
#    进程命令行里 `run_server.py` 是**相对路径**（`./.venv/bin/python run_server.py`），
#    而旧写法用的是绝对路径模式 `pkill -f '<DEST>/run_server.py'` → **静默不匹配**，
#    旧进程继续占着 8092，新代码永远加载不上，而 `--start` 还报「✓ ping → 200」。
#    现在三层兜底：① 按端口找 PID（精准，不会误伤）② 按脚本名宽匹配 ③ 等端口真释放。
#    ②里的 `[r]un_server` 是经典的「正则自匹配规避」：我们这条命令自身的命令行里
#    含的是 `[r]un_server.py` 这个字面量，而正则 `[r]un_server.py` 要求那个位置是
#    真正的 `r`，所以不会把自己（正在跑这条命令的 shell）一起杀掉。
def _stop_cmd(port: int) -> str:
    return (
        "for p in $(ss -ltnp 2>/dev/null | grep -E ':%d[^0-9]' "
        "| grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u); do "
        "  kill \"$p\" 2>/dev/null && echo \"  已杀 PID $p\"; done; "
        "pkill -f '[r]un_server.py' 2>/dev/null && echo '  已杀 run_server 进程'; "
        "sleep 2; echo '  旧进程清理完毕'"
    ) % port


def _port_pids(run, port: int) -> list[str]:
    _, out, _ = run("ss -ltnp 2>/dev/null | grep -E ':%d[^0-9]' "
                    "| grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u" % port, quiet=True)
    return [x for x in out.split() if x.isdigit()]


ADB_ROOT = DEST + "/.tools"
ADB_BIN = ADB_ROOT + "/platform-tools/adb"
ADB_URL = "https://dl.google.com/android/repository/platform-tools-latest-linux.zip"


def ensure_adb(run) -> None:
    """保证远端有一份 Linux 版 adb —— 家长端「整屏抓取」通道靠它抓平板整屏。

    ⚠️ 刻意**不写进 PACK_MEMBERS**：那是 ~9 MB 的第三方二进制，每次部署都重传
       纯属浪费；而 `.tools` 既不在打包清单里、解包又是增量覆盖，所以
       「装一次、一直用」是安全的。本函数幂等，已有可执行文件就直接跳过。

    ⚠️ 下载地址要能通。家里 NAS 直连 dl.google.com 实测 200；不通时这一步会
       打印警告但**不阻断部署** —— adb 通道用不了不影响 App 本身，
       家长端切到「App 内截图」照常用。
    """
    _, out, _ = run("test -x %s && echo HAVE || echo MISSING" % ADB_BIN, quiet=True)
    if "HAVE" in out:
        print("    adb 已就位：%s" % ADB_BIN)
        return
    print("    远端没有 adb，下载 platform-tools（约 9 MB）…")
    code, _, _ = run(
        "mkdir -p {r} && cd {r} && curl -sL -o pt.zip {u} && "
        "python3 -c \"import zipfile;zipfile.ZipFile('pt.zip').extractall('.')\" && "
        "chmod +x {b} && rm -f pt.zip".format(r=ADB_ROOT, u=ADB_URL, b=ADB_BIN),
        timeout=600,
    )
    if code != 0:
        print("    !! 下载/解压失败，adb 通道将不可用（不影响其它功能）")
        return
    run("%s version | head -1" % ADB_BIN)


# --------------------------------------------------------------- 容器化部署
# 2026-10-10 起服务端改为 docker 部署（编排在 DOCKER_DIR；service 名 = nuo-study）。
# 代码**仍然**是 SFTP 传到 DEST、容器以卷挂载到 /app —— 所以「传代码」这一步
# 一个字没改，换掉的只是「起服务 / 停服务 / 看日志」。
#
# 原来那套 nohup 裸进程的老毛病，正是这次要解决的：
#   · NAS 重启不会自启（得有人手工再跑一次 --start）
#   · 依赖装在 DEST/.venv，跟宿主机的 Python 版本绑死
# compose 的 restart: unless-stopped 直接解决自启，依赖封在镜像里。
DOCKER_DIR = os.environ.get("NUO_DOCKER_DIR", "/vol1/1000/docker/my-project")
COMPOSE_FILE = DOCKER_DIR + "/docker-compose.yml"
# ⚠️ 这里是 **service 名**，不是容器名，也不是目录名（compose project 名仍取自目录 my-project）。
#    2026-10-10 靖哥哥把 service / container / image 三者统一成了 `nuo-study`。
#    改这个名字必须同步：本文件、deploy/docker-compose.yml、以及 NAS 上的编排副本
#    （deploy_nas.py 每次部署会自动把本机 deploy/ 推过去，所以只改本机就够）。
#    代价见下面 _start_docker：旧 service 的容器会变孤儿 + 同名冲突，必须 --remove-orphans。
SERVICE_NAME = "nuo-study"

# 同一个 compose 项目里还住着别的服务（目前是 WeKnora 上传器，共用 host 网络）。
# 本脚本只管 nuo-study 这个 service —— 但 --check 要把整个项目的状态照出来：
# 排查「端口被谁占了 / 谁把容器重启了」时，最需要知道的就是「另一台服务跟我在同一个项目里」。
#
# ⚠️ 副作用：--start / --stop 的 compose 命令**不带服务名**，所以会连带重启/停掉
#    兄弟服务。这是刻意的（compose 的声明式语义：一条命令把整个项目拉到声明状态），
#    代价是部署 nuo-study 时 8770 会断 1~2 秒（在传的批次不丢，见 .staging 挂载）。
#    只想动 nuo-study 就手打：
#      docker compose -f <COMPOSE_FILE> up -d --build --force-recreate nuo-study
#
# ⚠️ **绝不要用容器名去 docker inspect / logs**：`container_name` 是用户随时会改的
#    （2026-10-10 靖哥哥就把 my-project 改成了 nuo-study），而**服务名是 compose 的契约**、
#    改它要动整个编排。所以一律走 `docker compose ps -q <服务名>` / `compose logs <服务名>`，
#    名字改不改都不受影响。（踩过：改名后 `docker inspect my-project` 静默返回空，
#    「容器 ID 变没变」这道唯一可靠的校验直接失效。）
SIBLING_PORTS = (8770,)


def _compose(inner: str) -> str:
    return "docker compose -f %s %s" % (COMPOSE_FILE, inner)


def _cid(run) -> str:
    """取 nuo_study 那个容器的 ID（用**服务名**查，不用容器名）。

    为什么不用容器名：`container_name` 是给人看的、随时会改（改名后 `docker inspect 旧名`
    静默返回空字符串），而服务名是 compose 的契约。用 compose 查，改不改名都不受影响。
    """
    _, out, _ = run(_compose("ps -q %s" % SERVICE_NAME), quiet=True)
    lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
    return lines[0] if lines else ""


def _clogs(tail: int) -> str:
    """看容器日志 —— 同样走服务名（容器名改过就查不到了）。"""
    return _compose("logs --tail %d %s" % (tail, SERVICE_NAME))


def has_docker_deploy(run) -> bool:
    """编排文件在不在 —— 在就走容器；不在退回老的 nohup（向后兼容）。"""
    _, out, _ = run("test -f %s && echo YES || echo NO" % COMPOSE_FILE, quiet=True)
    return "YES" in out


def _wait_ping(run, tries: int = 30, tail_cmd: str = "") -> bool:
    print("    等待就绪…")
    for _ in range(tries):
        time.sleep(1)
        # ⚠️ `%{http_code}` 必须写成 `%%{http_code}`：这整串还要过一次 Python 的
        #    `%` 格式化（末尾给 SERVICE_PORT 填 %d），不转义的话 curl 的花括号格式符
        #    会被当成 Python 的格式符 → `ValueError: unsupported format character '{'`。
        #    这个坑本项目踩过两次（另一次在识别探针脚本里）。
        _, out, _ = run("curl -s -m 3 -o /dev/null -w '%%{http_code}' "
                        "http://127.0.0.1:%d/api/v1/system/ping" % SERVICE_PORT, quiet=True)
        if out.strip() == "200":
            print("    ✓ /api/v1/system/ping → 200")
            return True
    print("    ✗ 没起来")
    if tail_cmd:
        run(tail_cmd)
    return False


def _start_docker(run) -> None:
    print("    方式：docker compose（编排 %s）" % COMPOSE_FILE)
    before = _cid(run)[:12]
    if before:
        print("    现有容器：%s" % before)
    else:
        print("    现有容器：无（首次创建）")

    # ⚠️ 这里**绝不能**沿用端口杀进程那套（_stop_cmd）：host 网络下容器里的 python
    #    在宿主机 PID 空间里同样显示成 `run_server.py`，`pkill -f` 会把容器进程一起
    #    打死，接着 restart 策略又把它拉回来 —— 看着像"重启过了"，实际编排文件 /
    #    镜像的改动一个字都没进。容器化的启停一律只走 compose。
    #
    # ⚠️ `--force-recreate` 不是可选项：compose 判定"配置没变"时只会打印
    #    `Container <name>  Running` 而**不重启**。我们的新代码是 SFTP 覆盖进
    #    挂载卷的，不重建容器 = 进程里跑的仍是旧代码，可 ping 照样 200。
    #    这与老方案那个「PID 没换却报成功」是同一种假象，只是换了张皮。
    #    `--build` 走层缓存（requirements 未动就是秒级）。
    #
    # ⚠️ `--remove-orphans` 是 2026-10-10 改名（service `my-project` → `nuo-study`）时加的，
    #    也必须一直留着：service 名一变，旧 service 的容器就成了「孤儿」（新编排里没有它），
    #    可它偏偏占着 `container_name: nuo-study` 这个新 service 要用的名字 → 不先删掉，
    #    `up` 会直接报 `container name is already in use` 而失败。
    #    加了这个参数，compose 会先清掉本 project 内不在编排里的容器，再建新的。
    print("    重建容器（--build --force-recreate --remove-orphans）…")
    _, _, _ = run(_compose("up -d --build --force-recreate --remove-orphans"), timeout=900)

    if not _wait_ping(run, tail_cmd=_clogs(40)):
        return

    # ⭐ ping 通 ≠ 跑的是新代码 —— 必须确认容器真的换了（ID 变了）。
    after = _cid(run)[:12]
    if before and after == before:
        print("    !! 容器 ID 未变（%s）—— 没真重建，新代码没生效！" % after)
    else:
        print("    ✓ 新容器 %s（旧：%s）" % (after or "?", before or "无"))
    run(_clogs(10))
    if after:
        run("docker inspect %s --format '    restart={{.HostConfig.RestartPolicy.Name}}"
            " net={{.HostConfig.NetworkMode}} user={{.Config.User}}'" % after, quiet=True)
    # health 需要鉴权，这里只能确认「未授权」，真正带 token 的验证走 tools/nas_e2e.py
    run("curl -s -m 5 http://127.0.0.1:%d/api/v1/system/health" % SERVICE_PORT)
    print("== 对外地址 ==")
    run("hostname -I 2>/dev/null | awk '{print \"    http://\" $1 \"%s/  （局域网，host 网络）\"}'"
        % (":%d" % SERVICE_PORT))


def _start_nohup(run) -> None:
    before = _port_pids(run, SERVICE_PORT)
    if before:
        print("    占用 %d 的 PID：%s" % (SERVICE_PORT, ", ".join(before)))
    run(_stop_cmd(SERVICE_PORT), quiet=True)

    # 确认端口真释放了 —— 不看这一步就会又掉进「旧进程没死透，新进程 bind 失败但
    # ping 仍是 200（因为应答的是老进程）」的坑里。
    for _ in range(10):
        left = _port_pids(run, SERVICE_PORT)
        if not left:
            break
        time.sleep(1)
    else:
        print("    ✗ %d 仍被 %s 占着，硬杀" % (SERVICE_PORT, ", ".join(left)))
        run("kill -9 %s 2>/dev/null; sleep 1" % " ".join(left), quiet=True)

    run("mkdir -p %s/logs" % DEST)
    # ⚠️ 这一步必然会「挂住」paramiko 通道：即便新进程把 fd 全重定向了，
    #    sshd 仍会等整个 session 的进程组结束才关通道（实测 even setsid 也逃不掉）。
    #    命令其实已经下发执行了，所以这里主动放弃读取 —— 起没起来由下面的健康检查判定。
    try:
        run("cd {d} && setsid nohup ./.venv/bin/python run_server.py "
            "> logs/server.log 2>&1 < /dev/null & echo launched".format(d=DEST),
            timeout=8, quiet=True)
    except Exception as exc:      # noqa: BLE001 —— 只可能是通道超时，忽略
        print("    （通道超时 %s，属预期，服务已在后台）" % type(exc).__name__)

    if not _wait_ping(run, tries=25, tail_cmd="tail -40 %s/logs/server.log" % DEST):
        return

    # ⭐ 关键校验：ping 通 ≠ 跑的是新代码。老进程没死透时 ping 照样 200（应答的是老的）。
    #    所以必须比对 PID 是否换了，并把新进程的启动时间打出来。
    after = _port_pids(run, SERVICE_PORT)
    if after and set(after) & set(before):
        print("    !! PID 未变（%s）—— 旧进程没被换掉，新代码没生效！" % ", ".join(after))
    elif after:
        print("    ✓ 新进程 PID %s（旧：%s）" % (", ".join(after),
                                              ", ".join(before) if before else "无"))
    run("ps -o pid,lstart,etime,args -p %s 2>/dev/null | tail -n +2"
        % (",".join(after) if after else "1"))
    run("curl -s -m 5 http://127.0.0.1:%d/api/v1/system/health" % SERVICE_PORT)
    print("== 对外地址 ==")
    run("hostname -I 2>/dev/null | awk '{print \"    http://\" $1 \"%s/  （局域网）\"}'"
        % (":%d" % SERVICE_PORT))


def step_start(run) -> None:
    print("== 起服务 ==")
    ensure_adb(run)
    if has_docker_deploy(run):
        _start_docker(run)
    else:
        print("    （未发现 %s，回退 nohup 裸进程方式）" % COMPOSE_FILE)
        _start_nohup(run)


def step_stop(run) -> None:
    if has_docker_deploy(run):
        print("    方式：docker compose stop")
        run(_compose("stop"), timeout=180)
        run(_compose("ps -a --format '    {{.Name}} | {{.Status}}'"))
        return
    run(_stop_cmd(SERVICE_PORT))
    run("(ss -ltn 2>/dev/null || netstat -ltn) | grep -E ':%d[^0-9]' "
        "|| echo '  端口已释放'" % SERVICE_PORT)


def step_log(run) -> None:
    if has_docker_deploy(run):
        run(_clogs(60))
        return
    run("tail -50 %s/logs/server.log 2>&1" % DEST)


# --------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只做远端环境画像")
    ap.add_argument("--pack", action="store_true", help="只本地打包")
    ap.add_argument("--install", action="store_true", help="建 venv 并装依赖")
    ap.add_argument("--start", action="store_true", help="后台起服务并健康检查")
    ap.add_argument("--stop", action="store_true", help="停止服务")
    ap.add_argument("--log", action="store_true", help="看服务日志")
    ap.add_argument("--tar", default="", help="复用已有的 tar.gz（不重新打包）")
    args = ap.parse_args()

    pw = os.environ.get("NAS_PW")
    if not pw and not args.pack:
        print("ERROR: 需要 NAS_PW 环境变量。用法：NAS_PW='xxx' python %s ..." % sys.argv[0],
              file=sys.stderr)
        return 2

    tmpdir = Path(tempfile.gettempdir())
    # 注意给 "%" 表达式加括号：Path 的 "/" 与 "%" 同优先级且左结合，
    # 不括起来会先算 tmpdir / "…%s…" 得到 Path，再对 Path 取模 → TypeError
    stamp = time.strftime("%Y%m%d-%H%M%S")
    tar_path = Path(args.tar) if args.tar else tmpdir / ("nuo_study-%s.tar.gz" % stamp)

    if not args.tar and not (args.check or args.stop or args.log):
        print("== 本地打包 ==")
        pack(tar_path)
    if args.pack:
        print("\n只打包，未连远端。产物：%s" % tar_path)
        return 0

    cli = connect(pw)
    run = make_run(cli)
    try:
        if args.check:
            step_check(run)
        elif args.stop:
            step_stop(run)
        elif args.log:
            step_log(run)
        elif args.start:
            step_start(run)
        else:
            step_upload(cli, run, tar_path)
            if args.install:
                step_install(run)
    finally:
        cli.close()

    print("\n完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
