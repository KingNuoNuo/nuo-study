#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""端到端冒烟测试。

**用子进程起一个真实服务**（不是 mock、不是 TestClient），走完整链路：
  上传 → 排队 → Worker 识别 → 草稿 → 家长修正 → 发布 → 再传同一张图（命中缓存）

用临时目录做库和图片，跑完即弃，不污染 data/。

用法:
  python server/smoke_test.py                       # 用内置的 3 张真实截图
  python server/smoke_test.py --image a.jpg --image b.jpg
  python server/smoke_test.py --port 8099
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_IMAGES = sorted(glob.glob(
    str(Path.home() / ".workbuddy" / "clipboard-images" / "clipboard-2026-10-09*.jpg")))

PASS, FAIL = [], []


def check(name: str, cond: bool, detail: str = ""):
    (PASS if cond else FAIL).append(name)
    print("  %s %s%s" % ("✅" if cond else "❌", name,
                         ("  → " + str(detail)) if detail else ""))
    return cond


class Client:
    def __init__(self, base: str, token: str):
        self.base, self.token = base, token

    def req(self, method: str, path: str, body=None, raw=None,
            content_type=None, token: str | None = None):
        url = self.base + path
        headers = {"Authorization": "Bearer " + (self.token if token is None else token)}
        data = None
        if raw is not None:
            data = raw
            headers["Content-Type"] = content_type
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        r = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(r, timeout=180) as resp:
                return resp.status, json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            txt = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(txt)
            except json.JSONDecodeError:
                # 未捕获异常会返回 HTML/纯文本，原样带出来便于定位
                return e.code, {"_raw": txt[:300]}


def multipart(path: Path) -> tuple[bytes, str]:
    """手搓 multipart/form-data，省掉 requests 依赖。"""
    b = "----smoke%d" % int(time.time() * 1000)
    name = path.name
    body = (("--%s\r\n" % b).encode()
            + ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode()
            + b"Content-Type: image/jpeg\r\n\r\n"
            + path.read_bytes()
            + ("\r\n--%s--\r\n" % b).encode())
    return body, "multipart/form-data; boundary=%s" % b


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--image", action="append", default=None)
    ap.add_argument("--keep", action="store_true", help="保留临时目录便于排查")
    args = ap.parse_args()

    images = [Path(p) for p in (args.image or DEFAULT_IMAGES)]
    images = [p for p in images if p.exists()]
    if not images:
        print("找不到测试图片；用 --image 指定")
        return 2

    tmp = Path(tempfile.mkdtemp(prefix="studyapp-smoke-"))
    env = os.environ.copy()
    env.update({
        "APP_DB": str(tmp / "app.db"),
        "IMAGE_DIR": str(tmp / "images"),
        "FAMILY_TOKEN": "smoke-token",
        "SERVER_PORT": str(args.port),
        "WORKER_POLL_INTERVAL": "0.3",
    })
    log = open(tmp / "server.log", "w", encoding="utf-8")
    print("临时目录:", tmp)
    proc = subprocess.Popen(
        [sys.executable, str(ROOT / "run_server.py"),
         "--host", "127.0.0.1", "--port", str(args.port), "--log-level", "warning"],
        env=env, stdout=log, stderr=subprocess.STDOUT, cwd=str(ROOT))
    base = "http://127.0.0.1:%d/api/v1" % args.port
    cli = Client(base, "smoke-token")

    try:
        # ---------------- 1. 启动与鉴权 ----------------
        print("\n【1】启动与鉴权")
        up = False
        for _ in range(60):
            if proc.poll() is not None:
                break
            try:
                st, _ = cli.req("GET", "/system/ping", token="")
                up = st == 200
                if up:
                    break
            except Exception:
                pass
            time.sleep(0.5)
        if not check("服务启动并响应 /system/ping", up,
                     "" if up else "见 %s/server.log" % tmp):
            return 1
        st, b = cli.req("GET", "/family", token="wrong-token")
        check("错误令牌返回 401", st == 401, "HTTP %s" % st)
        check("错误响应是 {code,message,traceId} 信封",
              set(b) >= {"code", "message", "traceId"}, b)
        st, b = cli.req("GET", "/family")
        check("正确令牌可读家庭配置", st == 200 and b["data"].get("timezone"),
              b.get("data"))

        # ---------------- 2. 上传 → 排队 → 识别 ----------------
        print("\n【2】上传 → 排队 → 识别（真实调用模型，需数十秒）")
        raw, ctype = multipart(images[0])
        st, b = cli.req("POST", "/captures", raw=raw, content_type=ctype)
        check("上传截图返回 captureId + jobId",
              st == 200 and b["data"].get("jobId"), b.get("data"))
        cid, job1 = b["data"]["captureId"], b["data"]["jobId"]
        check("首次上传不是去重命中", b["data"]["dedup"] is False)

        job = None
        t0 = time.time()
        while time.time() - t0 < 300:
            st, b = cli.req("GET", "/recognition-jobs/%d" % job1)
            job = b["data"]
            if job["status"] in (2, 3, 4):
                break
            print("     … 状态 %s（已 %.0fs）" % (job["statusName"], time.time() - t0))
            time.sleep(3)
        check("识别任务成功", job and job["status"] == 2,
              "status=%s error=%s" % (job and job["statusName"], job and job.get("error")))
        if not job or job["status"] != 2:
            return 1
        check("任务回传了结构化草稿", bool(job.get("draft", {}).get("assignments")))
        check("已产出作业单", len(job.get("assignments") or []) > 0,
              "%d 条" % len(job.get("assignments") or []))
        check("全部作业单都是草稿状态",
              all(a["status"] == 0 for a in job["assignments"]),
              [a["statusName"] for a in job["assignments"]])

        # ---------------- 3. 家长确认流 ----------------
        print("\n【3】家长确认流（修正 → 发布）")
        aid = job["assignments"][0]["id"]
        st, b = cli.req("GET", "/assignments?status=0")
        check("草稿列表可查", st == 200 and len(b["data"]) > 0, "%d 条草稿" % len(b["data"]))

        st, b = cli.req("GET", "/assignments/%d" % aid)
        a0 = b["data"]
        old_title = a0["title"]
        check("作业单详情含作业项", len(a0["items"]) > 0, "%d 项" % len(a0["items"]))

        st, b = cli.req("PATCH", "/assignments/%d" % aid,
                        {"title": (old_title or "作业") + "（已核对）"})
        check("家长修正标题成功", st == 200 and b["data"]["title"].endswith("（已核对）"),
              b["data"].get("title"))
        check("修正后仍是草稿", b["data"]["status"] == 0)

        st, b = cli.req("POST", "/assignments/%d/publish" % aid)
        check("发布成功（草稿 → 已发布）", st == 200 and b["data"]["status"] == 1,
              b["data"].get("statusName"))
        check("发布了确认设备与时间",
              bool(b["data"].get("confirmedAt") and b["data"].get("confirmedByDevice")))
        # ⚠️ 回归防护：契约声明的是 camelCase，服务端就必须按契约输出。
        #    之前直接返回数据库行（snake_case），客户端按契约写的 DTO 会静默地
        #    全部取到默认值 —— 不报错，但每个字段都是空的。这条断言守住它。
        check("响应字段是契约声明的 camelCase",
              "assignDate" in b["data"] and "needSubmit" in b["data"]
              and "assign_date" not in b["data"] and "need_submit" not in b["data"],
              sorted(b["data"].keys()))
        st, b = cli.req("POST", "/assignments/%d/publish" % aid)
        check("重复发布幂等（不报错、状态不变）", st == 200 and b["data"]["status"] == 1)

        # ---- 2026-10-09 规则变更：已发布(1) 允许改、允许删 ----
        # 早先这里断言「已发布一律 409」。那条规则被推翻了：家长录错一条已发布的作业
        # （标题打错 / 日期选错 / 少一项），唯一的补救就是改或删，挡在服务端只会把人
        # 逼去 SSH 改库（真发生过）。单家庭私有部署里家长就是唯一管理员，冻结没意义。
        # 误删的防线挪到客户端 —— 删除必须走二次确认弹窗。
        marked = (old_title or "作业") + "（已核对2）"
        st, b = cli.req("PATCH", "/assignments/%d" % aid, {"title": marked})
        check("已发布的作业单可以修改", st == 200 and b["data"]["title"] == marked,
              b["data"].get("title"))
        check("修改不会把它打回草稿", b["data"]["status"] == 1, b["data"].get("statusName"))

        # items 补丁用的是 camelCase（ItemPatch），别让服务端按 snake_case 去读 ——
        # 踩过：itemType / answerMode 被静默忽略（改了看不出来），
        #       estMinutes 取到 None 又被无条件写列，于是改一次就清空一次预估耗时。
        it0 = b["data"]["items"][0]
        st, b = cli.req("PATCH", "/assignments/%d" % aid, {"items": [
            {"id": it0["id"], "itemType": 3, "answerMode": 2, "estMinutes": 25}]})
        it1 = b["data"]["items"][0]
        check("items 补丁认 camelCase（itemType / answerMode）",
              st == 200 and it1["itemType"] == 3 and it1["answerMode"] == 2,
              "itemType=%s answerMode=%s" % (it1["itemType"], it1["answerMode"]))
        check("items 补丁不再误清 estMinutes / requirement",
              it1["estMinutes"] == 25 and it1["requirement"] == it0["requirement"],
              "est=%s req=%r" % (it1["estMinutes"], it1["requirement"]))

        # 已完成(2) / 已归档(3) 仍然拒绝：孩子已经在这条上做过题，
        # 删掉会让作答记录（answer_record.assignment_item_id）失去归属。
        # 正常流程走不到 2/3（M2 作答模块暂缓），所以直接改库构造一次。
        import sqlite3 as _sq

        def _set_status(s):
            cx = _sq.connect(tmp / "app.db")
            cx.execute("UPDATE assignment SET status=? WHERE id=?", (s, aid))
            cx.commit()
            cx.close()

        _set_status(2)
        st, b = cli.req("PATCH", "/assignments/%d" % aid, {"title": "改不动"})
        check("已完成的作业单拒绝修改（409）", st == 409, "HTTP %s" % st)
        st, b = cli.req("DELETE", "/assignments/%d" % aid)
        check("已完成的作业单拒绝删除（409）", st == 409, "HTTP %s" % st)
        _set_status(1)   # 复原：后面「重放不建重复单」的检查还要用它

        # ---------------- 4. 去重与缓存 ----------------
        print("\n【4】重复提交同一张图（去重 + 识别缓存）")
        raw, ctype = multipart(images[0])
        st, b = cli.req("POST", "/captures", raw=raw, content_type=ctype)
        check("同一张图被识别为重复（dedup=true）", b["data"]["dedup"] is True)
        check("复用了同一条 capture", b["data"]["captureId"] == cid)
        job2 = b["data"]["jobId"]
        job = None
        t0 = time.time()
        while time.time() - t0 < 120:
            st, b = cli.req("GET", "/recognition-jobs/%d" % job2)
            job = b["data"]
            if job["status"] in (2, 3, 4):
                break
            time.sleep(2)
        check("重传任务成功", job and job["status"] == 2, job and job["statusName"])
        check("命中了识别缓存（未重复付费）", job and job["cacheHit"] is True,
              "cacheHit=%s tokens=%s" % (job and job.get("cacheHit"), job and job.get("tokens")))
        check("重放没有产生额外 token", (job or {}).get("tokens") in (None, 0),
              (job or {}).get("tokens"))

        # ---------------- 5. 幂等与数据完整性 ----------------
        print("\n【5】幂等与数据完整性（直查 SQLite）")
        import sqlite3
        c = sqlite3.connect(tmp / "app.db")
        c.row_factory = sqlite3.Row
        rows = c.execute("""SELECT a.id, a.status, a.subject, a.title,
                                   (SELECT COUNT(*) FROM assignment_item i
                                    WHERE i.assignment_id=a.id) n
                            FROM assignment a ORDER BY a.id""").fetchall()
        for r in rows:
            print("     #%d status=%d subject=%d items=%d %s"
                  % (r["id"], r["status"], r["subject"], r["n"], r["title"]))

        # 重放的判据：作业单数量与作业项数量都不该变。
        # ⚠️ 第一次实现里身份键是 title，家长改完标题后重识别会新建一条重复作业单
        #    （2 条 → 3 条），所以这里必须严格比数量。
        n_assign = len(rows)
        n_items = sum(r["n"] for r in rows)
        check("重放后作业单数量不变（无重复建单）", n_assign == len(job["assignments"]),
              "%d 条（首次 %d 条）" % (n_assign, len(job["assignments"])))
        check("重放后作业项数量不变（未翻倍）",
              sorted(r["n"] for r in rows) == sorted(
                  len(a["items"]) for a in job["assignments"]),
              "items=%s" % [r["n"] for r in rows])
        check("家长改过的标题被保留（未被重识别冲掉）",
              any("已核对" in (r["title"] or "") for r in rows),
              [r["title"] for r in rows])
        dup = c.execute("""SELECT assignment_id, seq, COUNT(*) n FROM assignment_item
                           GROUP BY assignment_id, seq HAVING n > 1""").fetchall()
        check("作业项无重复 seq", not dup, dup)
        check("作业单身份键是 (student,date,subject)",
              c.execute("""SELECT COUNT(*) FROM (
                             SELECT student_id, assign_date, subject FROM assignment
                             GROUP BY student_id, assign_date, subject
                             HAVING COUNT(*) > 1)""").fetchone()[0] == 0)
        check("job_assignment 关联已建立",
              c.execute("SELECT COUNT(*) FROM job_assignment").fetchone()[0] > 0)
        corr = c.execute("SELECT COUNT(*) FROM recognition_correction").fetchone()[0]
        check("家长修正已留档", corr > 0, "%d 条" % corr)
        check("识别缓存已写入",
              c.execute("SELECT COUNT(*) FROM recognition_cache").fetchone()[0] > 0)
        check("schema 版本为 4", c.execute("PRAGMA user_version").fetchone()[0] == 4)

        # 外键：新连接默认就是 OFF —— 这恰恰是必须每个连接都设 PRAGMA 的原因。
        # （项目文档里反复强调这条，这里顺手把它变成一条可执行的断言）
        check("新连接 foreign_keys 默认为 OFF（所以 PRAGMA 必须每连接设）",
              c.execute("PRAGMA foreign_keys").fetchone()[0] == 0)
        c.execute("PRAGMA foreign_keys = ON")
        try:
            c.execute("INSERT INTO assignment_item (assignment_id, seq, subject,"
                      " item_type, title) VALUES (99999, 1, 1, 1, 'dangling')")
            fk_ok = False
        except sqlite3.IntegrityError:
            fk_ok = True
        check("打开 PRAGMA 后外键真正拦截悬空引用", fk_ok)
        c.close()

        st, b = cli.req("GET", "/system/health")
        print("\n  /system/health →", json.dumps(b["data"], ensure_ascii=False)[:220])

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()
        print("\n--- 服务端日志尾部 ---")
        lines = (tmp / "server.log").read_text(encoding="utf-8", errors="replace").splitlines()
        print("\n".join(lines[-25:]))
        if not args.keep:
            shutil.rmtree(tmp, ignore_errors=True)
        else:
            print("保留临时目录:", tmp)

    print("\n" + "=" * 62)
    print("通过 %d 项 / 失败 %d 项" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：")
        for f in FAIL:
            print("  -", f)
    print("=" * 62)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    raise SystemExit(main())
