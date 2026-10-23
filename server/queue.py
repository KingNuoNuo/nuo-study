# -*- coding: utf-8 -*-
"""任务队列。**这张表就是队列**，不引 Redis / Celery。

单家庭场景下，消息中间件的运维成本远大于它带来的好处。
正确性靠三件事撑住：
  1. `BEGIN IMMEDIATE` + 条件 UPDATE —— 两个 Worker 不会抢到同一条任务
  2. 租约 `leased_until` —— Worker 崩溃后任务不会永远卡在「识别中」
  3. 退避 `next_retry_at` —— 撞 429 限流时不会疯狂重试烧钱

状态：0 排队 / 1 识别中 / 2 成功 / 3 失败 / 4 已放弃
"""
from __future__ import annotations

import json
import sqlite3

from .db import iso_after, now_iso, tx

PENDING, RUNNING, SUCCEEDED, FAILED, GIVEN_UP = 0, 1, 2, 3, 4
JOB_STATUS_NAME = {0: "pending", 1: "running", 2: "succeeded",
                   3: "failed", 4: "given_up"}


def enqueue(con: sqlite3.Connection, capture_id: int, student_id: int,
            max_retry: int = 3) -> int:
    """入队。同一张图若已有未完成的任务，直接复用，避免重复排队。"""
    with tx(con):
        row = con.execute(
            """SELECT id FROM recognition_job
               WHERE capture_id=? AND status IN (0,1) ORDER BY id DESC LIMIT 1""",
            (capture_id,)).fetchone()
        if row:
            return row["id"]
        con.execute(
            """INSERT INTO recognition_job (capture_id, student_id, status, max_retry)
               VALUES (?,?,0,?)""", (capture_id, student_id, max_retry))
        return con.execute("SELECT last_insert_rowid()").fetchone()[0]


def reclaim_expired(con: sqlite3.Connection) -> int:
    """回收租约过期的任务（Worker 被杀 / 机器重启留下的僵尸任务）。"""
    with tx(con):
        cur = con.execute(
            """UPDATE recognition_job
               SET status=0, leased_until=NULL
               WHERE status=1 AND (leased_until IS NULL OR leased_until < ?)""",
            (now_iso(),))
        return cur.rowcount


def claim(con: sqlite3.Connection, lease_seconds: int = 300) -> dict | None:
    """原子领取一条就绪任务。返回 None 表示队列空。"""
    reclaim_expired(con)
    now = now_iso()
    with tx(con):
        row = con.execute(
            """SELECT id, capture_id, student_id, retry_count, max_retry
               FROM recognition_job
               WHERE status=0 AND (next_retry_at IS NULL OR next_retry_at <= ?)
               ORDER BY id LIMIT 1""", (now,)).fetchone()
        if not row:
            return None
        con.execute(
            """UPDATE recognition_job
               SET status=1, started_at=COALESCE(started_at, ?), leased_until=?
               WHERE id=?""",
            (now, iso_after(lease_seconds), row["id"]))
        return dict(row)


def complete(con: sqlite3.Connection, job_id: int, parsed: dict, meta: dict,
             cache_hit: bool) -> None:
    with tx(con):
        con.execute(
            """UPDATE recognition_job
               SET status=2, result=?, cache_hit=?, error_msg=NULL,
                   cost_tokens=?, duration_ms=?, leased_until=NULL, finished_at=?
               WHERE id=?""",
            (json.dumps(parsed, ensure_ascii=False),
             1 if cache_hit else 0,
             (meta.get("usage") or {}).get("total_tokens"),
             int((meta.get("latency_s") or 0) * 1000),
             now_iso(), job_id))


def fail(con: sqlite3.Connection, job_id: int, error_msg: str,
         retryable: bool, retry_after: int | None = None) -> str:
    """失败处理。可重试则退避重排，否则累计重试次数直到放弃。

    ⚠️ 「读 retry_count → 判断 → 写回」必须在**同一个事务**里。
       先读后开事务是典型的 TOCTOU：两个 Worker（或同一条任务的两次失败处理）
       可能都读到 retry_count=N，各自写回 N+1 —— 重试次数少涨一轮；
       更糟的是两个分支（重排 / 放弃）互相覆盖，任务在 pending 与 failed 之间来回跳。
       队列的全部正确性就建立在「读-判-写是原子的」之上。
    """
    with tx(con):
        row = con.execute(
            "SELECT retry_count, max_retry FROM recognition_job WHERE id=?",
            (job_id,)).fetchone()
        if row is None:
            return "missing"
        attempts = row["retry_count"] + 1
        if retryable and attempts < row["max_retry"]:
            delay = retry_after or min(300, 15 * (2 ** row["retry_count"]))
            con.execute(
                """UPDATE recognition_job
                   SET status=0, retry_count=?, error_msg=?, leased_until=NULL,
                       next_retry_at=?
                   WHERE id=?""",
                (attempts, error_msg[:500], iso_after(delay), job_id))
            return "requeued(%ds)" % delay
        con.execute(
            """UPDATE recognition_job
               SET status=?, retry_count=?, error_msg=?, leased_until=NULL, finished_at=?
               WHERE id=?""",
            (FAILED if retryable else GIVEN_UP, attempts, error_msg[:500],
             now_iso(), job_id))
        return "failed" if retryable else "given_up"


def get_job(con: sqlite3.Connection, job_id: int) -> dict | None:
    r = con.execute(
        """SELECT j.*, c.image_hash, c.storage_key, c.status AS capture_status,
                  c.byte_size, c.width, c.height
           FROM recognition_job j JOIN capture c ON c.id = j.capture_id
           WHERE j.id=?""", (job_id,)).fetchone()
    return dict(r) if r else None


def stats(con: sqlite3.Connection) -> dict:
    rows = con.execute(
        "SELECT status, COUNT(*) n FROM recognition_job GROUP BY status").fetchall()
    by = {JOB_STATUS_NAME[r["status"]]: r["n"] for r in rows}
    ready = con.execute(
        """SELECT COUNT(*) FROM recognition_job
           WHERE status=0 AND (next_retry_at IS NULL OR next_retry_at <= ?)""",
        (now_iso(),)).fetchone()[0]
    return {"byStatus": by, "ready": ready,
            "total": sum(by.values())}
