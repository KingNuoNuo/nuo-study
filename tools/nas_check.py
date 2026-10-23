#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""对 NAS 上的服务端做健康检查 / 端到端联调（纯 HTTP，不走 SSH）。

服务端监听 0.0.0.0:8092，所以本机直连 `http://192.168.1.10:8092` 即可。

用法
----
  python tools/nas_check.py                     # 只读检查：ping / health / 列表（不花钱）
  python tools/nas_check.py --upload 图.jpg     # 上传并等识别完成（⚠️ 会真的调模型花钱）
  python tools/nas_check.py --publish 3         # 把 3 号草稿发布
  python tools/nas_check.py --base http://...   # 覆盖服务端地址

接口前缀是 /api/v1，鉴权头是 `Authorization: Bearer <FAMILY_TOKEN>`
（**不是** X-Family-Token —— 记错了会一直吃 401）。
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import sys
import time
import uuid
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PASS, FAIL = [], []


def check(name: str, cond: bool, detail: str = "") -> None:
    (PASS if cond else FAIL).append(name)
    print("  %s %s%s" % ("✅" if cond else "❌", name,
                         ("  → " + str(detail)) if detail else ""))


def read_env(path: Path) -> dict:
    env = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            v = v[1:-1]
        env[k.strip()] = v
    return env


class Client:
    def __init__(self, base: str, token: str):
        self.base = base.rstrip("/")
        self.token = token

    def request(self, method: str, path: str, body: bytes | None = None,
                ctype: str | None = None, timeout: int = 30):
        url = self.base + path
        req = urllib.request.Request(url, data=body, method=method)
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        req.add_header("X-Device-Id", "dev-parent-01")
        if ctype:
            req.add_header("Content-Type", ctype)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read().decode("utf-8", "replace")
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, raw
        except Exception as e:                      # 连不上、超时
            return 0, str(e)


def upload_multipart(cli: Client, img: Path, student_id: int = 1):
    """手搓 multipart/form-data —— 只为少一个 requests 依赖。"""
    boundary = "----nuo" + uuid.uuid4().hex
    ctype = mimetypes.guess_type(img.name)[0] or "image/jpeg"
    parts = []
    parts.append(("--%s\r\n" % boundary).encode())
    parts.append(('Content-Disposition: form-data; name="file"; filename="%s"\r\n'
                  % img.name).encode())
    parts.append(("Content-Type: %s\r\n\r\n" % ctype).encode())
    parts.append(img.read_bytes())
    parts.append(("\r\n--%s--\r\n" % boundary).encode())
    body = b"".join(parts)
    return cli.request("POST", "/api/v1/captures?studentId=%d" % student_id, body,
                       "multipart/form-data; boundary=%s" % boundary, timeout=120)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://192.168.1.10:8092")
    ap.add_argument("--token", default="", help="默认从项目 .env 的 FAMILY_TOKEN 读")
    ap.add_argument("--upload", default="", help="要上传识别的图片路径")
    ap.add_argument("--publish", type=int, default=0, help="发布指定草稿 id")
    ap.add_argument("--poll", type=int, default=120, help="识别轮询上限秒数")
    args = ap.parse_args()

    env = read_env(ROOT / ".env")
    token = args.token or env.get("FAMILY_TOKEN", "")
    cli = Client(args.base, token)

    print("服务端: %s" % cli.base)
    print("令牌  : %s" % ("已读取（%d 字符）" % len(token) if token else "**缺失**"))

    print("\n【1】连通性与鉴权")
    st, body = cli.request("GET", "/api/v1/system/ping", timeout=10)
    check("GET /api/v1/system/ping → 200（无需鉴权）", st == 200, "HTTP %s" % st)
    if isinstance(body, dict):
        d = body.get("data") or {}
        print("     service=%s version=%s model=%s prompt=%s"
              % (d.get("service"), d.get("version"), d.get("model"),
                 d.get("promptVersion")))

    anon = Client(cli.base, "")
    st_no, _ = anon.request("GET", "/api/v1/system/health", timeout=10)
    check("不带令牌访问 /system/health 被拒（401）", st_no == 401, "HTTP %s" % st_no)

    st, body = cli.request("GET", "/api/v1/system/health", timeout=15)
    check("带令牌访问 /api/v1/system/health → 200", st == 200, "HTTP %s" % st)
    if isinstance(body, dict) and isinstance(body.get("data"), dict):
        d = body["data"]
        print("     schemaVersion=%s queue=%s cache=%s 草稿=%s 已发布=%s"
              % (d.get("schemaVersion"), d.get("queue"), d.get("cacheCount"),
                 d.get("draftCount"), d.get("publishedCount")))

    print("\n【2】基础数据（只读）")
    for path, label in (("/api/v1/devices", "设备"), ("/api/v1/students", "学生"),
                        ("/api/v1/family", "家庭信息")):
        st, body = cli.request("GET", path, timeout=10)
        check("GET %s → 200（%s）" % (path, label), st == 200, "HTTP %s" % st)

    print("\n【3】作业单列表（只读）")
    for q, label in (("", "全部"), ("?status=0", "草稿"), ("?status=1", "已发布")):
        st, body = cli.request("GET", "/api/v1/assignments" + q, timeout=15)
        n = len(body.get("data") or []) if isinstance(body, dict) else "?"
        check("GET /api/v1/assignments%s → 200（%s）" % (q, label), st == 200,
              "HTTP %s，%s 条" % (st, n))
        if isinstance(body, dict) and isinstance(body.get("data"), list):
            for a in body["data"][:5]:
                print("     #%s %s %s [%s] items=%d"
                      % (a.get("id"), a.get("assignDate"), a.get("title"),
                         a.get("statusName"), len(a.get("items") or [])))

    if args.upload:
        img = Path(args.upload)
        print("\n【4】上传并等识别（会真的调模型）")
        if not img.exists():
            check("图片存在", False, str(img))
        else:
            st, body = upload_multipart(cli, img)
            ok_up = st == 200 and isinstance(body, dict) and body.get("data")
            check("POST /api/v1/captures → 200", ok_up, "HTTP %s" % st)
            if not ok_up:
                print("     响应: %s" % json.dumps(body, ensure_ascii=False)[:400])
            else:
                d = body["data"]
                job = d["jobId"]
                print("     captureId=%s jobId=%s dedup=%s hash=%s"
                      % (d["captureId"], job, d.get("dedup"),
                         str(d.get("imageHash"))[:12]))
                t0 = time.time()
                last = None
                while time.time() - t0 < args.poll:
                    st, jb = cli.request("GET", "/api/v1/recognition-jobs/%d" % job,
                                         timeout=20)
                    if not isinstance(jb, dict) or not jb.get("data"):
                        break
                    jd = jb["data"]
                    last = (jd.get("status"), jd.get("statusName"), jd.get("error"))
                    # 0 pending / 1 running / 2 succeeded / 3 failed / 4 given_up
                    if jd.get("status") in (2, 3, 4):
                        break
                    time.sleep(2)
                print("     耗时 %.1fs，最终 status=%s" % (time.time() - t0, last))
                check("识别任务跑完（status=2 成功）", bool(last and last[0] == 2), last)
                if last and last[0] == 3:
                    print("     ✗ 失败原因: %s" % last[2])

    if args.publish:
        print("\n【5】发布草稿 #%d" % args.publish)
        st, body = cli.request("POST", "/api/v1/assignments/%d/publish" % args.publish,
                               timeout=20)
        check("POST /publish → 200", st == 200, "HTTP %s" % st)
        if isinstance(body, dict):
            d = body.get("data") or {}
            print("     状态=%s 确认设备=%s 确认时间=%s"
                  % (d.get("statusName"), d.get("confirmedByDevice"), d.get("confirmedAt")))

    print("\n" + "=" * 62)
    print("通过 %d 项 / 失败 %d 项" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项: %s" % ", ".join(FAIL))
    print("=" * 62)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    raise SystemExit(main())
