#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""单测：assign_date 识别不出时用 capture 上传日期兜底建草稿。

背景：聊天截图常整图无日期（状态栏只有时刻），模型只能交 null assign_date；
旧策略「不建作业单」实测就是家长嘴里的「无法识别」。见 2026-10-12 日志。

跑法：python tests/test_date_fallback.py（用系统 Python 3.11，与 smoke_test 一致）
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from server import db, store  # noqa: E402


def make_db(tmp: Path):
    p = db.init_db(tmp / "t.db")
    con = db.connect(p)
    con.execute("INSERT INTO student (name) VALUES ('测试娃')")
    con.commit()
    return con


def job_for(con, capture_id: int) -> int:
    cur = con.execute(
        "INSERT INTO recognition_job (capture_id, student_id, status) VALUES (?,?,2)",
        (capture_id, 1))
    con.commit()
    return cur.lastrowid


def draft(con, job_id: int) -> list[dict]:
    parsed = {
        "assignments": [{
            "assign_date": None, "subject": "语文", "title": "语文作业",
            "items": [{"seq": 1, "title": "阅读《童年》", "item_type": "听读打卡",
                       "answer_mode": "无", "est_minutes": 20, "confidence": 0.9}],
        }],
        "not_homework": [], "attachments": [],
        "needs_review": ["图中无日期"],
    }
    return store.materialize_draft(con, job_id, 1, parsed)


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="fbtest-"))
    con = make_db(tmp)
    fails = []

    def check(name, cond, detail=""):
        print(("  ✅ " if cond else "  ❌ ") + name + ((" | " + detail) if detail else ""))
        if not cond:
            fails.append(name)

    # --- 1. 正常下午上传（UTC 07:30 = 东八区 15:30，同日）---
    cur = con.execute(
        """INSERT INTO capture (student_id, device_id, image_hash, storage_key, created_at)
           VALUES (1,'d','h1','k1','2026-10-12T07:30:00.000Z')""")
    jid = job_for(con, cur.lastrowid)
    mats = draft(con, jid)
    check("兜底建单成功", mats[0]["materialized"] is True, str(mats[0]))
    check("推定日期=上传日(同日)", mats[0]["assign_date"] == "2026-10-12",
          mats[0].get("assign_date"))
    check("date_basis 写明推定", "推定" in (mats[0].get("date_basis") or ""))
    row = con.execute("SELECT status FROM assignment WHERE id=?",
                      (mats[0]["assignment_id"],)).fetchone()
    check("入库仍是草稿 status=0", row and row["status"] == 0)

    # --- 2. 凌晨上传（UTC 23:30 = 东八区次日 07:30，必须落到次日）---
    cur = con.execute(
        """INSERT INTO capture (student_id, device_id, image_hash, storage_key, created_at)
           VALUES (1,'d','h2','k2','2026-10-12T23:30:00.000Z')""")
    mats = draft(con, job_for(con, cur.lastrowid))
    check("UTC+8h 跨日正确", mats[0]["assign_date"] == "2026-10-13",
          mats[0].get("assign_date"))

    # --- 3. created_at 缺失/异常 → 退回不建单老路 ---
    cur = con.execute(
        """INSERT INTO capture (student_id, device_id, image_hash, storage_key, created_at)
           VALUES (1,'d','h3','k3','not-a-date')""")
    mats = draft(con, job_for(con, cur.lastrowid))
    check("时间异常退回不建单", mats[0]["materialized"] is False
          and "未建作业单" in (mats[0].get("reason") or ""), str(mats[0]))

    # --- 4. job_id 为 None（防御）→ 不崩、不建单 ---
    mats = draft(con, None)
    check("job_id=None 不崩", mats[0]["materialized"] is False, str(mats[0]))

    # --- 5. 幂等：同一天重复识别不翻倍（测试 2 合法建了 10-13 的单，此处只看 10-12）---
    cur = con.execute(
        """INSERT INTO capture (student_id, device_id, image_hash, storage_key, created_at)
           VALUES (1,'d','h1','k1','2026-10-12T07:30:00.000Z')""")
    mats2 = draft(con, job_for(con, cur.lastrowid))
    n = con.execute(
        "SELECT COUNT(*) AS n FROM assignment WHERE student_id=1 AND assign_date='2026-10-12'"
    ).fetchone()
    check("重复识别作业单不翻倍", n["n"] == 1, "10-12 作业单行数=%s" % n["n"])
    n2 = con.execute(
        """SELECT COUNT(*) AS n FROM assignment_item WHERE assignment_id=
           (SELECT id FROM assignment WHERE student_id=1 AND assign_date='2026-10-12')"""
    ).fetchone()
    check("重复识别作业项不翻倍", n2["n"] == 1, "item 行数=%s" % n2["n"])

    con.close()
    print("\n%s（%d 项失败）" % ("全绿 ✅" if not fails else "失败: " + ", ".join(fails),
                                len(fails)))
    return 0 if not fails else 1


if __name__ == "__main__":
    raise SystemExit(main())
