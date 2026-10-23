#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线批量识别工具（M1 可行性验证 + 提示词调优用）。

服务端（run_server.py）是生产路径；这个脚本用来跑批量样本、量准确率，
两者**共用同一份识别实现**（server/recognizer.py、server/store.py），
避免「线上跑的和验证过的不是同一套代码」。

用法：
  python tools/recognize.py --init
  python tools/recognize.py <img1> <img2> ... [--think off] [--no-cache] [--no-write]
  python tools/score.py            # 对照人工基准打分
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from server import config, db, store  # noqa: E402
from server.db import now_iso, tx  # noqa: E402
from server.recognizer import (RecognizeError, build_opener,  # noqa: E402
                               call_model, parse_json_block)

OUT_DIR = ROOT / "out"
OUT_DIR.mkdir(exist_ok=True)


def run(images: list[Path], write_db: bool, use_proxy: bool, think: str,
        use_cache: bool, tag: str) -> list[dict]:
    opener = build_opener(use_proxy)
    con = db.connect() if write_db else None
    student_id = (con.execute("SELECT id FROM student ORDER BY id LIMIT 1").fetchone()[0]
                  if con else 1)

    summary = []
    for img in images:
        print("\n" + "=" * 72)
        print("图片:", img.name, "(%.0f KB)" % (img.stat().st_size / 1024))
        raw = img.read_bytes()
        h = hashlib.sha256(raw).hexdigest()
        print("sha256:", h[:16], "…")

        cached, capture_id, job_id = None, None, None
        if con:
            if use_cache:
                hit = con.execute(
                    """SELECT raw_result, model, prompt_version FROM recognition_cache
                       WHERE image_hash=?""", (h,)).fetchone()
                if hit and hit["prompt_version"] == config.PROMPT_VERSION:
                    cached = hit["raw_result"]
                    with tx(con):
                        con.execute("UPDATE recognition_cache SET hit_count = hit_count + 1"
                                    " WHERE image_hash=?", (h,))
                    print("命中识别缓存（同图不重复付费）| model=%s" % hit["model"])
            c = con.execute("SELECT id FROM capture WHERE image_hash=? ORDER BY id LIMIT 1",
                            (h,)).fetchone()
            if c:
                capture_id = c["id"]
                print("该图已上传过 | capture_id=%d" % capture_id)
            else:
                res = store.upsert_capture(con, student_id, "dev-cli", raw, img.name)
                capture_id = res["capture_id"]
                print("新建 capture_id=%d" % capture_id)

        # ---- 调用模型（带退避重试）----
        if cached:
            meta = {"latency_s": 0.0, "usage": {}, "finish_reason": "cache"}
            parsed, perr = parse_json_block(cached)
        else:
            meta, parsed, perr = None, None, ""
            for attempt in range(1, 4):
                try:
                    meta = call_model(raw, img.name, think=think, opener=opener)
                    break
                except RecognizeError as e:
                    if e.retryable and attempt < 3:
                        wait = e.retry_after or 8 * attempt
                        print("  ⏳ %s，%ds 后重试 (%d/3)" % (e, wait, attempt))
                        import time
                        time.sleep(wait)
                        continue
                    print("  ❌ %s" % e)
                    break
            if meta is None:
                summary.append({"image": img.name, "hash": h, "ok": False,
                                "error": "调用失败"})
                continue
            u = meta["usage"]
            print("  模型 %.1fs | finish=%s | prompt=%s completion=%s (reasoning=%s)"
                  % (meta["latency_s"], meta["finish_reason"],
                     u.get("prompt_tokens"), u.get("completion_tokens"),
                     (u.get("completion_tokens_details") or {}).get("reasoning_tokens")))
            parsed, perr = parse_json_block(meta["content"])
            if not parsed and meta.get("reasoning"):
                print("  ⚠️ content 无 JSON，尝试从 reasoning 兜底")
                parsed, perr = parse_json_block(meta["reasoning"])

        if con:
            if not cached:
                body = (json.dumps(parsed, ensure_ascii=False) if parsed
                        else (meta.get("content") or ""))
                with tx(con):
                    con.execute(
                        """INSERT OR REPLACE INTO recognition_cache
                             (image_hash, raw_result, model, prompt_version)
                           VALUES (?,?,?,?)""",
                        (h, body, config.VISION_MODEL, config.PROMPT_VERSION))
                    con.execute(
                        """INSERT INTO recognition_job
                             (capture_id, student_id, status, cache_hit, result,
                              cost_tokens, duration_ms, started_at, finished_at)
                           VALUES (?,?,?,?,?,?,?,?,?)""",
                        (capture_id, student_id, 2 if parsed else 3,
                         1 if cached else 0,
                         json.dumps(parsed, ensure_ascii=False) if parsed else None,
                         (meta.get("usage") or {}).get("total_tokens"),
                         int((meta.get("latency_s") or 0) * 1000),
                         now_iso(), now_iso()))
                job_id = con.execute("SELECT last_insert_rowid()").fetchone()[0]
                with tx(con):
                    con.execute("UPDATE capture SET status=2 WHERE id=?", (capture_id,))

        if not parsed:
            print("  ❌ 解析失败:", perr)
            summary.append({"image": img.name, "hash": h, "ok": False, "error": perr})
            continue

        mats = (store.materialize_draft(con, job_id, student_id, parsed) if con else [])

        for a in parsed.get("assignments") or []:
            print("  📅 %s %s | %s | 需提交=%s  置信度=%s"
                  % (a.get("assign_date"), a.get("subject"), a.get("title"),
                     a.get("need_submit"), a.get("confidence")))
            if a.get("date_basis"):
                print("     日期依据: %s" % a["date_basis"])
            for it in a.get("items") or []:
                print("     %s. [%s/%s/%s分钟] %s"
                      % (it.get("seq"), it.get("item_type"), it.get("answer_mode"),
                         it.get("est_minutes") or "-", it.get("title")))
                if it.get("requirement"):
                    print("         └ %s" % it["requirement"])
        for att in parsed.get("attachments") or []:
            print("  📎 附件: %s" % att.get("desc"))
        for nh in parsed.get("not_homework") or []:
            print("  🚫 非作业(%s): %s" % (nh.get("kind"), nh.get("raw")))
        for nr in parsed.get("needs_review") or []:
            print("  ⚠️ 待复核: %s" % nr)
        for m in mats:
            print("  💾 落库: %s" % (m if m["materialized"]
                                    else "%s → %s" % (m.get("title"), m.get("reason"))))

        (OUT_DIR / ("%s%s.result.json" % (h[:12], tag))).write_text(
            json.dumps({"image": img.name, "sha256": h,
                        "prompt_version": config.PROMPT_VERSION,
                        "meta": meta, "parsed": parsed, "db": mats},
                       ensure_ascii=False, indent=2), encoding="utf-8")
        summary.append({"image": img.name, "hash": h, "ok": True,
                        "assignments": len(parsed.get("assignments") or []),
                        "items": sum(len(a.get("items") or [])
                                     for a in parsed.get("assignments") or []),
                        "tokens": (meta.get("usage") or {}).get("total_tokens"),
                        "latency_s": meta.get("latency_s"), "db": mats})

    if con:
        print("\n" + "=" * 72)
        print("库内统计")
        for t in ("capture", "recognition_cache", "recognition_job",
                  "assignment", "assignment_item"):
            print("  %-18s %d 行" % (t, con.execute("SELECT COUNT(*) FROM %s" % t).fetchone()[0]))
        print("  作业单：草稿(待确认)=%d  已发布=%d  ← 模型输出必须是草稿"
              % (con.execute("SELECT COUNT(*) FROM assignment WHERE status=0").fetchone()[0],
                 con.execute("SELECT COUNT(*) FROM assignment WHERE status=1").fetchone()[0]))
        con.close()

    (OUT_DIR / ("run-summary%s.json" % tag)).write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    ok = [s for s in summary if s.get("ok")]
    print("\n完成：%d/%d 张识别成功 | 总 token=%s | 总耗时=%.1fs"
          % (len(ok), len(summary), sum((s.get("tokens") or 0) for s in ok),
             sum((s.get("latency_s") or 0) for s in ok)))
    return summary


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="*")
    ap.add_argument("--init", action="store_true")
    ap.add_argument("--no-write", action="store_true")
    ap.add_argument("--no-proxy", action="store_true")
    ap.add_argument("--no-cache", action="store_true")
    ap.add_argument("--think", choices=["auto", "on", "off"], default=None)
    ap.add_argument("--tag", default="")
    args = ap.parse_args()

    if args.init:
        p = db.init_db()
        print("建库完成:", p, "| schema v%d" % db.db_version(db.connect()))
        return 0
    if not args.images:
        print("用法: python tools/recognize.py <img...> 或 --init")
        return 2
    imgs = [Path(p) for p in args.images]
    for p in imgs:
        if not p.exists():
            print("图片不存在:", p)
            return 2
    think = args.think or config.VISION_THINKING
    print("配置：model=%s think=%s max_tokens=%s prompt=%s"
          % (config.VISION_MODEL, think, config.VISION_MAX_TOKENS,
             config.PROMPT_VERSION))
    run(imgs, write_db=not args.no_write, use_proxy=not args.no_proxy,
        think=think, use_cache=not args.no_cache, tag=args.tag)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
