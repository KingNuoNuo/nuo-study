# -*- coding: utf-8 -*-
"""学生端「实时画面」镜像（测试期调试功能）。

有**两条互不干扰的采集通道**，家长端可以随时切换看哪一条：

| 来源 | 谁在截屏 | 能看到什么 | 代价 |
|------|----------|------------|------|
| `app` | 孩子端 App 用 PixelCopy 截**自己窗口** | 只有 App 的界面 | 零权限；退到别的应用就停住 |
| `adb` | **本服务端**用无线 adb 抓整屏 | 整个屏幕，含系统弹窗/桌面 | 要在 NAS 上放一份 adb；平板要开着 `tcpip 5555` |

两条通道都往同一个帧池里塞，靠帧上的 `source` 字段区分。
`GET /api/v1/debug/frames/latest.jpg?source=adb` 取哪条由调用方说了算。

⚠️ 五个刻意为之的取舍，改之前先读：

1. **不用 MediaProjection**。那需要家长在平板上点一次授权、常驻一条通知，
   Android 14+ 还得声明前台服务类型。自己截自己的窗口（PixelCopy）**不需要任何权限**。
   代价就是上面表里那行「只能看到 App 自己的界面」—— 想看全屏时切 `adb` 那条即可。

2. **帧只留在内存里**（外加最新一帧覆盖落盘）。3 秒一帧往盘上写是纯粹的磨损；
   落盘那一帧只是为了 App 关掉之后还能回看最后一眼。
   ⚠️ 所以**服务端一重启，历史帧就没了**，这不是 bug。

3. **`adb` 通道是懒启动的**（[AdbGrabber.ensure]）。它只在有人取 adb 帧时才抓，
   断供 [AdbGrabber.idle_stop] 秒后线程自己退出。
   不这么做的后果很具体：家长看完关掉手机，NAS 上还在 3 秒一次地
   连平板截屏 —— 平板白耗电，家里 WiFi 白占带宽，而且**没有任何人能看出来**。

4. **图片与网页用 URL 查询参数鉴权**（`?token=`）。浏览器打开网页时带不了
   `Authorization` 头。POST 上传那一端仍然走标准的 Bearer 头。

5. **`adb` 抓到的帧由服务端压成 JPEG**（[ADB_MAX_WIDTH] 宽）。平板原图 1280×800
   的 PNG 有 90–200 KB，直接发给手机端是没必要的流量；顺带统一成 `image/jpeg`，
   跟 `app` 通道的格式对齐。Pillow 不可用时退回原 PNG（帧里带 `mime` 字段，
   接口按它回 Content-Type，所以不会串）。
"""
from __future__ import annotations

import io
import json
import re
import subprocess
import threading
import time
from pathlib import Path
from typing import Any

from . import config

# 每台设备在内存里保留的帧数。60 帧 × 3 秒 ≈ 3 分钟，够家长回头看「刚才那一下发生了什么」。
KEEP_PER_DEVICE = 60
# 帧池里最多留几台设备。device_id 来自请求头（可伪造），**只限制「每台留多少帧」
# 而不限制「留多少台」等于没限制**：攒到几千个 key 就是无上限的内存增长。
MAX_DEVICES = 32
# 单帧上限。App 端压到 640×400 / JPEG q60，正常 30–80 KB；4 MB 只是防呆。
MAX_FRAME_BYTES = 4 * 1024 * 1024

# 两条采集通道。别在这里加值 —— 每加一个，家长端与网页都得跟着认。
SOURCE_APP = "app"
SOURCE_ADB = "adb"

_LOCK = threading.Lock()
_frames: dict[str, list[dict[str, Any]]] = {}
_seq = 0


def _safe(name: str) -> str:
    """设备标识 → 能当文件名用的字符串。deviceId 本来就只有 [A-Za-z0-9-]，兜一下底。"""
    return re.sub(r"[^A-Za-z0-9._-]", "_", name)[:64] or "unknown"


def save(device_id: str, label: str | None, raw: bytes,
         source: str = SOURCE_APP, mime: str = "image/jpeg") -> dict[str, Any]:
    """收一帧。返回这一帧的元信息（不含像素）。"""
    global _seq
    if not raw:
        raise ValueError("空帧")
    if len(raw) > MAX_FRAME_BYTES:
        raise ValueError("单帧超过 %d KB" % (MAX_FRAME_BYTES // 1024))

    now = time.time()
    with _LOCK:
        _seq += 1
        seq = _seq
        # 新设备入场前先腾位置：淘汰「最后一帧最旧」的那台。
        # 正常只有 2 个 key（App 通道 + adb 通道），这条分支几乎不会走到；
        # 它挡的是「有人拿随机 X-Device-Id 刷帧」把内存堆满。
        if device_id not in _frames and len(_frames) >= MAX_DEVICES:
            oldest = min(
                _frames,
                key=lambda d: _frames[d][-1]["at"] if _frames[d] else 0.0,
            )
            _frames.pop(oldest, None)
        buf = _frames.setdefault(device_id, [])
        buf.append({"seq": seq, "at": now, "bytes": raw, "label": label,
                    "source": source, "mime": mime})
        # 只留最近的：`del buf[:-N]` 比 `buf.pop(0)` 循环省事，也不会负索引出错。
        del buf[: -KEEP_PER_DEVICE]
        kept = len(buf)

    _write_disk(device_id, raw)
    return {"seq": seq, "bytes": len(raw), "at": now, "kept": kept, "source": source}


def _write_disk(device_id: str, raw: bytes) -> None:
    """覆盖写最新一帧。写盘失败不影响内存里的帧 —— 网页照样能看。"""
    try:
        config.FRAME_DIR.mkdir(parents=True, exist_ok=True)
        target = config.FRAME_DIR / ("%s.jpg" % _safe(device_id))
        tmp = target.with_suffix(".jpg.tmp")
        tmp.write_bytes(raw)
        tmp.replace(target)          # 原子替换：网页不会读到写了一半的 jpeg
    except OSError:
        pass


def latest(device_id: str | None = None,
           source: str | None = None) -> tuple[bytes, dict[str, Any]] | None:
    """取最新一帧。

    `device_id` 为空时取**所有设备里最新的一帧**；`source` 再按通道过滤。
    两者可以叠加，但顺序很重要：先按 device 定位、再在它的缓冲里筛 source。
    """
    with _LOCK:
        if device_id:
            buf = _frames.get(device_id) or []
            if source:
                buf = [f for f in buf if f["source"] == source]
            if not buf:
                return None
            f = buf[-1]
        else:
            f = None
            for buf in _frames.values():
                if not buf:
                    continue
                cand = buf[-1]
                if source and cand["source"] != source:
                    continue
                if f is None or cand["at"] > f["at"]:
                    f = cand
            if f is None:
                return None
        return f["bytes"], {"seq": f["seq"], "at": f["at"], "label": f["label"],
                            "source": f["source"], "mime": f["mime"]}


def state() -> dict[str, Any]:
    """给网页/App 用的状态：每台设备最后一帧的时间、大小与通道。"""
    now = time.time()
    with _LOCK:
        items = []
        for dev, buf in _frames.items():
            if not buf:
                continue
            f = buf[-1]
            items.append({
                "deviceId": dev,
                "label": f["label"],
                "source": f["source"],
                "seq": f["seq"],
                "at": f["at"],
                "ageSec": round(now - f["at"], 1),
                "kb": round(len(f["bytes"]) / 1024, 1),
                "kept": len(buf),
            })
    items.sort(key=lambda x: x["at"], reverse=True)
    return {"frames": items, "devices": len(items), "serverTime": now,
            "adb": grabber().status()}


# ---------------------------------------------------------------- adb 抓整屏通道

def _to_jpeg(png: bytes, max_width: int, quality: int) -> tuple[bytes, str]:
    """PNG → JPEG（顺带缩到 max_width）。Pillow 缺失或解码失败就原样退回 PNG。

    ⚠️ 退回时**必须把 mime 一起带出去**。端点那边是按帧里的 mime 回 Content-Type 的，
       写死 image/jpeg 会让浏览器拿到 PNG 却按 JPEG 解 —— 表现是「图不显示」，
       而这台 NAS 上到底有没有 Pillow 是环境问题，不该变成代码里的暗雷。
    """
    try:
        from PIL import Image
    except Exception:
        return png, "image/png"
    try:
        im = Image.open(io.BytesIO(png))
        im.load()
        if im.mode != "RGB":
            im = im.convert("RGB")
        if max_width and im.width > max_width:
            h = max(1, round(im.height * max_width / im.width))
            im = im.resize((max_width, h), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=quality, optimize=True)
        return buf.getvalue(), "image/jpeg"
    except Exception:
        return png, "image/png"


class AdbGrabber:
    """服务端主动连平板抓整屏。

    **它不是一个常驻服务**：没有人在取 adb 帧时它什么也不做。
    调用方（`api.py` 的取图端点）每次请求调一次 [ensure] 就算「点了一次名」，
    线程在 [idle_stop] 秒没人点名后自行退出。

    线程里只做三件事，且**任何异常都不许逃出去**：逃出去线程就死了，
    而家长看到的现象只是「画面一直不更新」，跟平板锁屏长得一模一样。
    """

    def __init__(self, adb_bin: str, target: str, interval: float, idle_stop: float,
                 max_width: int, jpeg_quality: int, timeout: float = 20.0):
        self.adb_bin = adb_bin
        self.target = target
        # 间隔下限 1 秒：再密也没有意义（人眼看不出来），只是让平板多耗电。
        self.interval = max(1.0, interval)
        # 空闲阈值必须大于一个周期，否则线程会在两次抓帧之间就判自己该停。
        self.idle_stop = max(self.interval + 2.0, idle_stop)
        self.max_width = max_width
        self.jpeg_quality = jpeg_quality
        self.timeout = timeout

        # 抓来的帧混进同一个池子，但用独立的 deviceId —— 这样网页上
        # 「孩子端 GM260200701」和「平板整屏」是两个条目，不会互相盖掉。
        self.device_id = "adb-" + _safe(target)
        self.label = "平板整屏"

        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._last_demand = 0.0
        self._server_ready = False
        self._grabs = 0
        self._failures = 0
        self._last_error = ""

    # ---- 配置态
    @property
    def available(self) -> bool:
        """配了目标地址、且 adb 二进制真的在。"""
        return bool(self.target) and bool(self.adb_bin) and Path(self.adb_bin).exists()

    def ensure(self) -> bool:
        """点名。返回 adb 通道是否可用（不可用时调用方应当明确告诉用户为什么）。"""
        if not self.available:
            return False
        with self._lock:
            self._last_demand = time.time()
            if self._thread is None or not self._thread.is_alive():
                t = threading.Thread(target=self._loop, name="adb-grab", daemon=True)
                self._thread = t
                t.start()
        return True

    def status(self) -> dict[str, Any]:
        with self._lock:
            return {
                "enabled": self.available,
                "target": self.target or None,
                "running": bool(self._thread and self._thread.is_alive()),
                "grabs": self._grabs,
                "failures": self._failures,
                "lastError": self._last_error or None,
                "intervalSec": self.interval,
                "idleStopSec": self.idle_stop,
                "adbBin": self.adb_bin,
                "adbBinExists": bool(self.adb_bin) and Path(self.adb_bin).exists(),
            }

    # ---- 干活的
    def _loop(self) -> None:
        try:
            self._prepare()
            while self._wanted():
                t0 = time.time()
                try:
                    raw, mime = self._grab()
                    save(self.device_id, self.label, raw, source=SOURCE_ADB, mime=mime)
                    with self._lock:
                        self._grabs += 1
                        self._failures = 0
                        self._last_error = ""
                except Exception as e:                      # noqa: BLE001 —— 见类注释
                    with self._lock:
                        self._failures += 1
                        self._last_error = "%s: %s" % (type(e).__name__, str(e)[:160])
                        fails = self._failures
                    # 连续失败到第 3 次时重连一次。平板重启、或平板上的 adbd
                    # 被 `adb root` 之类的操作重启过之后，tcpip 端口会掉，
                    # 这时候只有重新 connect 才能恢复，光重试是白试。
                    if fails == 3:
                        self._connect()
                elapsed = time.time() - t0
                time.sleep(max(0.2, self.interval - elapsed))
        except Exception as e:                              # noqa: BLE001
            with self._lock:
                self._last_error = "%s: %s" % (type(e).__name__, str(e)[:160])
        finally:
            # 必须置回 None，否则 ensure() 会以为线程还活着，再也不会重启它。
            with self._lock:
                self._thread = None

    def _wanted(self) -> bool:
        with self._lock:
            return (time.time() - self._last_demand) <= self.idle_stop

    def _prepare(self) -> None:
        """起 adb server + 连目标设备。只在一次线程生命周期里做一次。

        ⚠️ 这里**必须把三个 fd 全重定向到 DEVNULL**，不能用 PIPE。
           原因：adb client 第一次跑时会 fork 出一个常驻的 adb server 进程，
           那个 daemon **会继承 client 的 stdout/stderr**。
           如果这里用的是 `stdout=PIPE`，父进程等管道 EOF 时会一直等下去 ——
           daemon 不退出，管道就永不关闭。表现是服务端启动后卡死 20 秒然后超时。
           用 DEVNULL 就完全没有这个读写端。
        """
        if self._server_ready:
            return
        self._run(["start-server"])
        self._connect()
        self._server_ready = True

    def _connect(self) -> None:
        try:
            self._run(["connect", self.target])
        except Exception:                                   # noqa: BLE001
            pass

    def _run(self, args: list[str]) -> str:
        r = subprocess.run(
            [self.adb_bin, *args],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=self.timeout,
        )
        return (r.stdout or b"").decode(errors="replace")

    def _grab(self) -> tuple[bytes, str]:
        # exec-out 而不是 shell：**`adb shell` 会把 stdout 里的 \n 换成 \r\n**，
        # PNG 里的 0x0a 字节会被改掉 → 得到一个长度都不对的坏文件。
        r = subprocess.run(
            [self.adb_bin, "-s", self.target, "exec-out", "screencap", "-p"],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=self.timeout,
        )
        if r.returncode != 0:
            msg = (r.stderr or b"").decode(errors="replace").strip()[:160]
            raise RuntimeError(msg or ("adb 退出码 %d" % r.returncode))
        png = r.stdout or b""
        # 认 PNG 魔数：设备掉线时 adb 有时会「成功」返回一段 error 文本。
        if not png.startswith(b"\x89PNG"):
            raise RuntimeError("输出不是 PNG（%d 字节）" % len(png))
        return _to_jpeg(png, self.max_width, self.jpeg_quality)


_grabber: AdbGrabber | None = None
_grabber_lock = threading.Lock()


def grabber() -> AdbGrabber:
    """进程内唯一的 adb 采集器。惰性创建 —— 导入本模块时 **不能** 碰 subprocess。"""
    global _grabber
    with _grabber_lock:
        if _grabber is None:
            _grabber = AdbGrabber(
                adb_bin=config.ADB_BIN,
                target=config.ADB_TARGET,
                interval=config.ADB_INTERVAL,
                idle_stop=config.ADB_IDLE_STOP,
                max_width=config.ADB_MAX_WIDTH,
                jpeg_quality=config.ADB_JPEG_QUALITY,
            )
        return _grabber


# ---------------------------------------------------------------- 网页

_PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>学生端实时画面</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: #14161a; color: #e8eaed;
    font: 14px/1.5 -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
    display: flex; flex-direction: column; min-height: 100vh;
  }
  header {
    display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap;
    padding: 12px 16px; border-bottom: 1px solid #2a2e35; background: #191c21;
  }
  h1 { font-size: 15px; margin: 0; font-weight: 600; }
  #meta { color: #9aa0a6; font-size: 13px; }
  #meta b { color: #e8eaed; font-weight: 600; }
  .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%;
         background: #6b7280; margin-right: 6px; vertical-align: 1px; }
  .dot.live { background: #34d399; box-shadow: 0 0 0 0 rgba(52,211,153,.7);
              animation: pulse 1.6s infinite; }
  .dot.stale { background: #f59e0b; }
  .dot.dead { background: #ef4444; }
  @keyframes pulse { to { box-shadow: 0 0 0 8px rgba(52,211,153,0); } }
  main { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; }
  #wrap { position: relative; max-width: 100%; }
  img { display: block; max-width: 100%; max-height: 76vh; border-radius: 10px;
        border: 1px solid #2a2e35; background: #0b0c0e; }
  #err { position: absolute; inset: 0; display: none; align-items: center;
         justify-content: center; text-align: center; padding: 24px;
         color: #9aa0a6; font-size: 13px; white-space: pre-wrap; }
  footer { padding: 10px 16px; border-top: 1px solid #2a2e35; background: #191c21;
           display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  button { font: inherit; color: #e8eaed; background: #262a31; border: 1px solid #363b43;
           border-radius: 8px; padding: 6px 14px; cursor: pointer; }
  button:hover { background: #2f343c; }
  .seg { display: inline-flex; border: 1px solid #363b43; border-radius: 8px; overflow: hidden; }
  .seg button { border: 0; border-radius: 0; padding: 6px 14px; background: #1d2126; }
  .seg button.on { background: #3b82f6; color: #fff; }
  .hint { color: #6b7280; font-size: 12px; margin-left: auto; }
</style>
</head>
<body>
<header>
  <h1>学生端实时画面</h1>
  <span id="meta"><span class="dot" id="dot"></span>正在连接…</span>
</header>
<main>
  <div id="wrap">
    <img id="f" alt="学生端画面">
    <div id="err"></div>
  </div>
</main>
<footer>
  <span class="seg">
    <button id="src-app">App 内截图</button>
    <button id="src-adb">整屏抓取</button>
  </span>
  <button id="toggle">暂停</button>
  <button id="snap">另存当前帧</button>
  <span class="hint" id="hint"></span>
</footer>
<script>
const TOKEN = __TOKEN__;
const DEVICE = __DEVICE__;
let SOURCE = __SOURCE__ || 'app';
const img = document.getElementById('f');
const err = document.getElementById('err');
const dot = document.getElementById('dot');
const meta = document.getElementById('meta');
const hint = document.getElementById('hint');
let paused = false, inflight = false, fail = 0;

const HINTS = {
  app: '由孩子端 App 自己截屏上传，每 3 秒一帧；只能看到 App 自己的界面，看不到系统弹窗。',
  adb: '由服务端用无线 adb 抓整屏，每 3 秒一帧；能看到整个屏幕。只有在有人看的时候才抓。'
};

function url(nonce) {
  let u = '/api/v1/debug/frames/latest.jpg?token=' + encodeURIComponent(TOKEN)
        + '&source=' + encodeURIComponent(SOURCE) + '&t=' + nonce;
  if (DEVICE) u += '&deviceId=' + encodeURIComponent(DEVICE);
  return u;
}

function paintSeg() {
  document.getElementById('src-app').className = SOURCE === 'app' ? 'on' : '';
  document.getElementById('src-adb').className = SOURCE === 'adb' ? 'on' : '';
  hint.textContent = HINTS[SOURCE] || '';
}

function setSource(s) {
  if (SOURCE === s) return;
  SOURCE = s;
  fail = 0;
  const u = new URL(location.href);
  u.searchParams.set('source', s);
  history.replaceState(null, '', u);
  paintSeg();
  tick();
}

async function tick() {
  if (paused || inflight) return;
  inflight = true;
  try {
    const r = await fetch(url(Date.now()), { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const blob = await r.blob();
    const old = img.src;
    img.src = URL.createObjectURL(blob);
    if (old.startsWith('blob:')) URL.revokeObjectURL(old);
    img.style.display = 'block'; err.style.display = 'none';
    fail = 0;
  } catch (e) {
    fail++;
    img.style.display = fail > 1 ? 'none' : 'block';
    err.style.display = fail > 1 ? 'flex' : 'none';
    err.textContent = '还没收到画面。\\n' + e.message + '\\n\\n' + (
      SOURCE === 'adb'
        ? '整屏抓取需要服务端能连上平板：检查 .env 里的 ADB_TARGET，以及平板是否还开着 tcpip 5555。'
        : '检查：孩子端 App 是否在前台、设置页里的「实时画面」是否打开。');
  }
  inflight = false;
  refreshMeta();
}

async function refreshMeta() {
  try {
    const r = await fetch('/api/v1/debug/frames/state?token=' + encodeURIComponent(TOKEN),
                          { cache: 'no-store' });
    const j = await r.json();
    const d = j.data || {};
    const list = d.frames || [];
    if (!list.length) {
      dot.className = 'dot dead';
      meta.innerHTML = '<span class="dot dead"></span>还没有任何设备上传过画面';
      return;
    }
    const f = list.find(x => (!DEVICE || x.deviceId === DEVICE) && x.source === SOURCE)
           || list.find(x => x.source === SOURCE)
           || list[0];
    const age = f.ageSec;
    const cls = age < 8 ? 'live' : (age < 60 ? 'stale' : 'dead');
    dot.className = 'dot ' + cls;
    const label = f.label ? (f.label + ' · ') : '';
    meta.innerHTML = '<span class="dot ' + cls + '"></span>' + label +
      '第 <b>' + f.seq + '</b> 帧 · <b>' + age + 's</b> 前 · ' + f.kb + ' KB';
  } catch (e) { /* 状态拿不到不影响看图 */ }
}

document.getElementById('src-app').onclick = () => setSource('app');
document.getElementById('src-adb').onclick = () => setSource('adb');
document.getElementById('toggle').onclick = (e) => {
  paused = !paused;
  e.target.textContent = paused ? '继续' : '暂停';
};
document.getElementById('snap').onclick = () => {
  const a = document.createElement('a');
  a.href = img.src;
  a.download = 'student-screen-' + Date.now() + '.jpg';
  a.click();
};

paintSeg();
setInterval(tick, 1500);
tick();
setInterval(refreshMeta, 1500);
</script>
</body>
</html>
"""


_PLACEHOLDER_RE = re.compile(r"__TOKEN__|__DEVICE__|__SOURCE__")


def _js_literal(value: str) -> str:
    """Python 字符串 → 能安全嵌进 HTML `<script>` 里的 JS 字面量（**含两侧引号**）。

    ⚠️ 光用 `json.dumps` 不够。它会把 `"` 转义掉，但**不转义 `<`**，
       于是 `</script>` 会原样出现在生成物里 —— 而 HTML 解析器遇到 `</script>`
       就结束脚本块，**哪怕它位于 JS 字符串字面量内部**。
       结果就是：一个 deviceId 参数就能闭合脚本、注入任意 HTML。
       把 `<` 换成 `\\u003c` 在 JS 字符串里完全等价于 `<`，但 HTML 解析器不再认得它。

    （顺带说明为什么不选「HTML 实体转义」：`&lt;` 在 `<script>` 里**不会**被解码，
       会原样进入 JS 字符串，反而把 deviceId 弄坏。）
    """
    return json.dumps(value, ensure_ascii=False).replace("<", "\\u003c")


def page_html(token: str, device_id: str | None = None, source: str | None = None) -> str:
    # 用 replace/正则而不是 % / format：页面里的 CSS 有大括号与百分号，格式化会直接炸。
    src = source if source in (SOURCE_APP, SOURCE_ADB) else SOURCE_APP
    # ⚠️ deviceId / token 都来自 URL 查询参数，必须按 [_js_literal] 的方式转义。
    #    这条 URL 天生就是拿来复制、发到微信里分享的（?token= 就是它的鉴权方式），
    #    所以「参数里被塞东西」是很现实的路径，不是理论风险。
    #
    # ⚠️ 用正则**一次遍历**替换，不要写成链式 .replace()：那样前一次替换的结果
    #    会被后一次当作待替换文本继续处理（token 里恰好含 `__DEVICE__` 就会被改写）。
    values = {
        "__TOKEN__": _js_literal(token),
        "__DEVICE__": _js_literal(device_id or ""),
        "__SOURCE__": _js_literal(src),
    }
    return _PLACEHOLDER_RE.sub(lambda m: values[m.group(0)], _PAGE)
