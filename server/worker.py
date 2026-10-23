# -*- coding: utf-8 -*-
"""识别 Worker：后台线程消费队列。

每个线程**持有自己的 SQLite 连接**（sqlite3 默认不允许跨线程复用连接）。
默认单并发 —— 实测该端点会返回 429 tpm/rpm limit，并发只会把限流撞得更频繁。
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
import traceback

from . import config, queue, store
from .db import connect, now_iso, tx
from .recognizer import RecognizeError, build_opener, recognize


class RecognitionWorker:
    def __init__(self, concurrency: int | None = None,
                 lease_seconds: int | None = None,
                 poll_interval: float | None = None,
                 logger=print):
        self.concurrency = concurrency or config.WORKER_CONCURRENCY
        self.lease_seconds = lease_seconds or config.LEASE_SECONDS
        self.poll_interval = poll_interval or config.POLL_INTERVAL
        self.log = logger
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []
        self.processed = 0
        self.failed = 0

    # ------------------------------------------------------------ 生命周期
    def start(self) -> None:
        for i in range(self.concurrency):
            t = threading.Thread(target=self._loop, name="recog-worker-%d" % i, daemon=True)
            t.start()
            self._threads.append(t)
        self.log("[worker] 已启动 %d 个消费线程" % self.concurrency)

    def stop(self, timeout: float = 10.0) -> None:
        self._stop.set()
        for t in self._threads:
            t.join(timeout=timeout)
        self.log("[worker] 已停止")

    # ------------------------------------------------------------ 主循环
    def _loop(self) -> None:
        con = connect()
        opener = build_opener()
        try:
            while not self._stop.is_set():
                job = queue.claim(con, self.lease_seconds)
                if not job:
                    self._stop.wait(self.poll_interval)
                    continue
                try:
                    self._process(con, job, opener)
                    self.processed += 1
                except Exception as e:                       # 兜底：绝不让线程死掉
                    self.failed += 1
                    self.log("[worker] 任务 %s 未捕获异常: %s" % (job["id"], e))
                    traceback.print_exc()
                    queue.fail(con, job["id"], "%s: %s" % (type(e).__name__, e),
                               retryable=True)
        finally:
            con.close()

    # ------------------------------------------------------------ 单任务
    def _process(self, con: sqlite3.Connection, job: dict, opener) -> None:
        jid = job["id"]
        cap = store.get_capture(con, job["capture_id"])
        path = store.image_path(cap["storage_key"])
        if not path.exists():
            queue.fail(con, jid, "原图缺失: %s" % cap["storage_key"], retryable=False)
            self.log("[worker] #%s 原图缺失，放弃" % jid)
            return

        raw = path.read_bytes()
        cache_hit = False

        # ---- 1. 查识别缓存（同一张图只付一次钱）----
        row = con.execute(
            """SELECT raw_result, model, prompt_version FROM recognition_cache
               WHERE image_hash=?""", (cap["image_hash"],)).fetchone()
        parsed, meta = None, None
        if row and row["prompt_version"] == config.PROMPT_VERSION:
            try:
                parsed = json.loads(row["raw_result"])
                cached_at = time.time()
                meta = {"usage": {}, "latency_s": 0.0, "finish_reason": "cache",
                        "model": row["model"], "_cached_at": cached_at}
                cache_hit = True
            except json.JSONDecodeError:
                parsed = None        # 缓存损坏 → 当作未命中，重新识别

        # ---- 2. 调模型 ----
        if parsed is None:
            try:
                parsed, meta = recognize(raw, cap["storage_key"], opener=opener)
            except RecognizeError as e:
                state = queue.fail(con, jid, str(e), retryable=e.retryable,
                                   retry_after=e.retry_after)
                self.log("[worker] #%s 识别失败(%s): %s" % (jid, state, e))
                return
            with tx(con):
                con.execute(
                    """INSERT OR REPLACE INTO recognition_cache
                         (image_hash, raw_result, model, prompt_version)
                       VALUES (?,?,?,?)""",
                    (cap["image_hash"], json.dumps(parsed, ensure_ascii=False),
                     config.VISION_MODEL, config.PROMPT_VERSION))

        # ---- 3. 落草稿（status 恒为 0）----
        mats = store.materialize_draft(con, jid, job["student_id"], parsed)

        # ---- 4. 更新缓存命中计数 / 截图状态 / 任务状态 ----
        if cache_hit:
            with tx(con):
                con.execute("UPDATE recognition_cache SET hit_count = hit_count + 1"
                            " WHERE image_hash=?", (cap["image_hash"],))
        with tx(con):
            con.execute("UPDATE capture SET status=2 WHERE id=?", (cap["id"],))
        queue.complete(con, jid, parsed, meta, cache_hit)

        n = sum(m.get("item_count", 0) for m in mats)
        self.log("[worker] #%s 完成 %s | 作业单 %d 条 / 作业项 %d 个 | %.1fs%s"
                 % (jid, "缓存命中" if cache_hit else "调用模型",
                    sum(1 for m in mats if m["materialized"]), n,
                    meta.get("latency_s") or 0, "" if cache_hit else
                    " tokens=%s" % (meta.get("usage") or {}).get("total_tokens")))
