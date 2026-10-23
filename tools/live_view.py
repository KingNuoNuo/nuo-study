#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""本地「实时看板」：把连着的平板画面搬到浏览器里，每 2 秒一帧。

为什么需要它（和机内镜像的区别）：
  - **机内镜像**（学生端 App 自己抓屏上传到 NAS，见 server/mirror.py）不需要插线，
    手机在外面也能看，但要重新编译安装学生端；
  - **本工具**零改动、马上能用，代价是平板必须一直插着 USB 连在这台电脑上。
  两个都留着：改 UI 的时候用这个（省一次编译），看孩子实际使用情况用那个。

用法：
    python tools/live_view.py                       # 自动挑一台设备，端口 8099
    python tools/live_view.py -s GM260200701        # 两台都插着时必须指定
    python tools/live_view.py --port 9000 --interval 1.5

然后用浏览器打开它打印出来的地址（默认 http://127.0.0.1:8099/）。

⚠️ 只监听 127.0.0.1。这是**没有鉴权的本地调试服务**，
   绑到 0.0.0.0 就意味着同局域网任何人都能看到你设备的屏幕。

⚠️ 抓帧在**后台线程**里按固定间隔做，不是「浏览器每请求一次就 adb 一次」——
   否则多开一个标签页就多一份 adb 开销，画面还会互相踩。
"""
from __future__ import annotations

import argparse
import io
import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import adb_ui  # noqa: E402  （复用它的 ADB 路径与 MSYS_NO_PATHCONV 处理）

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False


# ---------------------------------------------------------------- 抓帧

class Grabber:
    """后台按固定间隔抓帧。线程只有一个，所以不需要锁 —— 用不可变快照交换。"""

    def __init__(self, serial: str | None, interval: float,
                 width: int, quality: int) -> None:
        self.serial = serial
        self.interval = interval
        self.width = width
        self.quality = quality
        self._lock = threading.Lock()
        self._snapshot: dict = {"jpeg": None, "at": 0.0, "error": "还没抓到第一帧", "seq": 0}
        self._stop = threading.Event()

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self._snapshot)

    def _publish(self, **kw) -> None:
        with self._lock:
            self._snapshot.update(kw)

    def start(self) -> None:
        threading.Thread(target=self._loop, daemon=True, name="grabber").start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            t0 = time.time()
            try:
                raw = self._screencap()
                data = self._encode(raw)
                self._publish(jpeg=data, at=time.time(), error=None,
                              seq=self._snapshot["seq"] + 1)
            except Exception as e:                      # noqa: BLE001 —— 顶层循环，什么都不能漏
                self._publish(error=str(e), at=time.time())
            # 减去抓帧本身花的时间，让间隔尽量准
            self._stop.wait(max(0.2, self.interval - (time.time() - t0)))

    def _screencap(self) -> bytes:
        out = self._raw_screencap()
        if not out.stdout.startswith(b"\x89PNG"):
            # 无线调试（adb tcpip + adb connect）会因为路由空闲而断开：设备从
            # `adb devices` 里消失后，所有命令都变成 "device not found"。
            # 这里补一次自动重连 —— 拔掉 USB 之后看板得能自己活过来，
            # 否则就是一屏报错，还得有人手动去敲 adb connect。
            if self._reconnect():
                out = self._raw_screencap()
        # exec-out 拿的是原始字节；一旦经过 `adb shell`，Windows 上会被 CRLF 搞坏 PNG。
        if not out.stdout:
            raise RuntimeError(
                (out.stderr.decode("utf-8", "replace").strip() or "adb 没有输出")
            )
        if not out.stdout.startswith(b"\x89PNG"):
            raise RuntimeError("拿到的不是 PNG（设备可能离线了）")
        return out.stdout

    def _raw_screencap(self) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["MSYS_NO_PATHCONV"] = "1"
        if self.serial:
            env["ANDROID_SERIAL"] = self.serial
        return subprocess.run(
            [str(adb_ui.ADB), "exec-out", "screencap", "-p"],
            capture_output=True, env=env, check=False,
        )

    def _reconnect(self) -> bool:
        """设备掉线时重连一次。只对 `host:port` 形式的无线设备有意义。"""
        if not self.serial or ":" not in self.serial:
            return False
        try:
            out = subprocess.run(
                [str(adb_ui.ADB), "connect", self.serial],
                capture_output=True, text=True, timeout=8, check=False,
            )
            return "connected" in (out.stdout or "")
        except Exception:                              # noqa: BLE001 —— 重连本身不能再抛
            return False

    def _encode(self, raw: bytes) -> bytes:
        if not HAS_PIL:
            return raw
        im = Image.open(io.BytesIO(raw)).convert("RGB")
        if self.width and im.width > self.width:
            h = round(im.height * self.width / im.width)
            im = im.resize((self.width, h), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=self.quality)
        return buf.getvalue()


# ---------------------------------------------------------------- 网页

PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>设备实时画面 · 本地看板</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; background: #14161a; color: #e8eaed;
         font: 14px/1.5 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
         display: flex; flex-direction: column; min-height: 100vh; }
  header { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap;
           padding: 12px 16px; border-bottom: 1px solid #2a2e35; background: #191c21; }
  h1 { font-size: 15px; margin: 0; font-weight: 600; }
  #meta { color: #9aa0a6; font-size: 13px; }
  #meta b { color: #e8eaed; }
  .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%;
         background: #6b7280; margin-right: 6px; vertical-align: 1px; }
  .dot.live { background: #34d399; animation: pulse 1.6s infinite; }
  .dot.stale { background: #f59e0b; }
  .dot.dead { background: #ef4444; }
  @keyframes pulse { to { box-shadow: 0 0 0 8px rgba(52,211,153,0); } }
  main { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; }
  img { display: block; max-width: 100%; max-height: 80vh; border-radius: 10px;
        border: 1px solid #2a2e35; background: #0b0c0e; }
  #err { display: none; max-width: 520px; text-align: center; color: #9aa0a6;
         font-size: 13px; white-space: pre-wrap; }
  footer { padding: 10px 16px; border-top: 1px solid #2a2e35; background: #191c21;
           display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  button { font: inherit; color: #e8eaed; background: #262a31; border: 1px solid #363b43;
           border-radius: 8px; padding: 6px 14px; cursor: pointer; }
  .hint { color: #6b7280; font-size: 12px; margin-left: auto; }
</style>
</head>
<body>
<header>
  <h1>设备实时画面</h1>
  <span id="meta"><span class="dot" id="dot"></span>连接中…</span>
</header>
<main>
  <img id="f" alt="设备画面">
  <div id="err"></div>
</main>
<footer>
  <button id="toggle">暂停</button>
  <button id="snap">另存当前帧</button>
  <span class="hint">通过 USB 直接抓屏，不经任何网络服务。</span>
</footer>
<script>
const img = document.getElementById('f'), err = document.getElementById('err');
const dot = document.getElementById('dot'), meta = document.getElementById('meta');
let paused = false, inflight = false;
async function tick() {
  if (paused || inflight) return;
  inflight = true;
  try {
    const r = await fetch('/frame.jpg?t=' + Date.now(), { cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const blob = await r.blob();
    const old = img.src;
    img.src = URL.createObjectURL(blob);
    if (old.startsWith('blob:')) URL.revokeObjectURL(old);
    img.style.display = 'block'; err.style.display = 'none';
  } catch (e) { /* 状态行会说清楚 */ }
  inflight = false;
}
async function refreshMeta() {
  try {
    const j = await (await fetch('/state?t=' + Date.now(), { cache: 'no-store' })).json();
    if (j.error) {
      dot.className = 'dot dead';
      img.style.display = 'none'; err.style.display = 'block';
      err.textContent = '抓不到画面：\\n' + j.error +
        '\\n\\n检查平板是否还插着 USB、adb 里能不能看到它。';
      meta.innerHTML = '<span class="dot dead"></span>抓帧失败';
      return;
    }
    const cls = j.ageSec < 6 ? 'live' : (j.ageSec < 30 ? 'stale' : 'dead');
    dot.className = 'dot ' + cls;
    meta.innerHTML = '<span class="dot ' + cls + '"></span>' + j.device + ' · 第 <b>' +
      j.seq + '</b> 帧 · <b>' + j.ageSec + 's</b> 前 · ' + j.kb + ' KB';
  } catch (e) {}
}
document.getElementById('toggle').onclick = (e) => {
  paused = !paused; e.target.textContent = paused ? '继续' : '暂停';
};
document.getElementById('snap').onclick = () => {
  const a = document.createElement('a');
  a.href = img.src; a.download = 'device-screen-' + Date.now() + '.jpg'; a.click();
};
setInterval(tick, 1000);
setInterval(refreshMeta, 1000);
tick(); refreshMeta();
</script>
</body>
</html>
"""


def make_handler(grabber: Grabber, device_label: str):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):        # 每秒一次的请求，别刷屏
            pass

        def _send(self, code: int, body: bytes, ctype: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store, max-age=0")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):                 # noqa: N802 —— BaseHTTPRequestHandler 的约定
            path = self.path.split("?", 1)[0]
            snap = grabber.snapshot()

            if path in ("/", "/index.html"):
                self._send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
                return

            if path == "/state":
                if snap["error"]:
                    body = {"error": snap["error"]}
                else:
                    body = {
                        "device": device_label,
                        "seq": snap["seq"],
                        "ageSec": round(time.time() - snap["at"], 1),
                        "kb": round(len(snap["jpeg"]) / 1024, 1),
                    }
                self._send(200, json.dumps(body, ensure_ascii=False).encode("utf-8"),
                           "application/json; charset=utf-8")
                return

            if path == "/frame.jpg":
                if snap["error"] or not snap["jpeg"]:
                    # 前端只会看到「取不到图」，真正的错误原因走 /state。
                    self._send(503, snap["error"].encode("utf-8") if snap["error"] else b"no frame",
                               "text/plain; charset=utf-8")
                    return
                ctype = "image/jpeg"
                if snap["jpeg"].startswith(b"\x89PNG"):
                    ctype = "image/png"      # 没装 Pillow 时直出 PNG
                self._send(200, snap["jpeg"], ctype)
                return

            self._send(404, b"not found", "text/plain; charset=utf-8")

    return Handler


# ---------------------------------------------------------------- 入口

def pick_device(explicit: str | None) -> tuple[str | None, str]:
    out = adb_ui.adb("devices", "-l")
    devices = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append((parts[0], line))

    online = [d[0] for d in devices]

    def label_of(line: str, serial: str) -> str:
        # `adb devices -l` 里带 model:XXX。用它比串号好认，尤其两台设备长得差不多时。
        if "model:" in line:
            return "%s（%s）" % (line.split("model:", 1)[1].split()[0], serial)
        return serial

    if explicit:
        if explicit not in online:
            print("⚠️ 指定了 -s %s，但 adb 里没看到它。当前在线：%s"
                  % (explicit, online or "（一台都没有）"), file=sys.stderr)
            return explicit, explicit
        return explicit, label_of(next(l for s, l in devices if s == explicit), explicit)

    if not devices:
        print("⚠️ adb 里一台设备都没有。平板插好了吗？`adb devices` 能看到吗？", file=sys.stderr)
        return None, "（没有设备）"

    if len(devices) > 1:
        print("⚠️ 插了 %d 台设备，默认用第一台 %s。要看另一台请加 -s <串号>。"
              % (len(devices), devices[0][0]), file=sys.stderr)

    serial, line = devices[0]
    return serial, label_of(line, serial)


def main() -> int:
    ap = argparse.ArgumentParser(description="本地实时看板：把设备画面搬到浏览器")
    ap.add_argument("-s", "--serial", default=None, help="设备串号；插了两台以上时必须给")
    ap.add_argument("--port", type=int, default=8099, help="监听端口，默认 8099")
    ap.add_argument("--interval", type=float, default=2.0, help="抓帧间隔秒数，默认 2")
    ap.add_argument("--width", type=int, default=900, help="缩到这么宽再传浏览器，0=不缩")
    ap.add_argument("--quality", type=int, default=80, help="JPEG 质量，默认 80")
    args = ap.parse_args()

    if not adb_ui.ADB.exists():
        print("找不到 adb：%s" % adb_ui.ADB, file=sys.stderr)
        return 2
    if not HAS_PIL:
        print("没有 Pillow，将直出 PNG（页面会慢一些）。装：pip install pillow", file=sys.stderr)

    serial, label = pick_device(args.serial)
    grabber = Grabber(serial, args.interval, args.width, args.quality)
    grabber.start()

    # 先等第一帧，好让「打开浏览器就是画面」而不是先看到 503
    for _ in range(40):
        if grabber.snapshot()["error"] is None:
            break
        time.sleep(0.25)

    url = "http://127.0.0.1:%d/" % args.port
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(grabber, label))
    print("设备：%s" % label)
    print("地址：%s      （Ctrl-C 停止）" % url)
    snap = grabber.snapshot()
    if snap["error"]:
        print("⚠️ 第一帧就失败了：%s" % snap["error"])
    else:
        print("首帧已就绪：%d KB" % (len(snap["jpeg"]) // 1024))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n停止")
    finally:
        grabber.stop()
        srv.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
