#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
识别准确率评分器

对照 tools/ground_truth.json（人工从原图标注的基准），给识别结果打分。
设计原则：**分维度量，不给一个笼统的总分**——因为不同维度的失败代价完全不同：
  · 漏掉一个作业项 → 孩子少做一项，家长会发现
  · 日期错了       → 作业挂到错误的一天，整天的计划全乱
  · 编造内容       → 最严重，家长信任直接崩塌
  · 噪声没拦住     → 学号列表变成作业项，同样致命
  · 附件被逐题转录 → 不是错误，是白花钱（token 浪费）

用法:
  python tools/score.py              # 评分 out/*.result.json
  python tools/score.py --tag=_thinkoff
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "out"
GT = json.loads((ROOT / "tools" / "ground_truth.json").read_text(encoding="utf-8"))


def norm(s) -> str:
    return re.sub(r"[\s，。、；：（）()【】\[\]！!,.:;]", "", str(s or ""))


def cov(keywords, text) -> tuple[int, int, list[str]]:
    t = norm(text)
    miss = [k for k in keywords if norm(k) not in t]
    return len(keywords) - len(miss), len(keywords), miss


def score_one(hash12: str, gt: dict, got: dict) -> dict:
    parsed = got.get("parsed") or {}
    ga = parsed.get("assignments") or []
    r = {"image": got.get("image"), "hash12": hash12,
         "json_ok": bool(parsed.get("assignments") is not None),
         "gt_assignments": len(gt["assignments"]), "got_assignments": len(ga),
         "details": [], "violations": []}

    # ---- 必须不能出现在作业项里的内容 ------------------------------------
    all_item_text = " ".join(
        (it.get("title") or "") + " " + (it.get("requirement") or "")
        for a in ga for it in (a.get("items") or []))
    for bad in gt.get("must_not_appear_in_items", []):
        if norm(bad) in norm(all_item_text):
            r["violations"].append("作业项里出现不该有的内容: %r" % bad)

    # ---- 噪声拦截（not_homework）-----------------------------------------
    nh = " ".join((x.get("raw") or "") for x in (parsed.get("not_homework") or []))
    if gt.get("expect_not_homework_flag"):
        r["noise_caught"] = norm(gt["expect_not_homework_flag"]) in norm(nh)

    # ---- 附件登记（不许逐题转录）-----------------------------------------
    if gt.get("expect_attachment_count_min"):
        r["attachments"] = len(parsed.get("attachments") or [])
        r["attachments_ok"] = r["attachments"] >= gt["expect_attachment_count_min"]

    # ---- 逐条作业单比对 ---------------------------------------------------
    used = set()
    for g in gt["assignments"]:
        best, best_score = None, -1
        for i, a in enumerate(ga):
            if i in used:
                continue
            sc = 0
            if a.get("subject") == g["subject"]:
                sc += 2
            if g["assign_date"] and a.get("assign_date") == g["assign_date"]:
                sc += 3
            if sc > best_score:
                best, best_score = i, sc
        d = {"gt": {"date": g["assign_date"], "subject": g["subject"],
                    "items": len(g["items"]),
                    "need_submit": g.get("need_submit")}, "matched": best is not None and best_score > 0}
        if d["matched"]:
            a = ga[best]
            used.add(best)
            d["got"] = {"date": a.get("assign_date"), "subject": a.get("subject"),
                        "title": a.get("title"), "items": len(a.get("items") or [])}
            d["date_ok"] = (not g["assign_date"]) or a.get("assign_date") == g["assign_date"]
            d["subject_ok"] = a.get("subject") == g["subject"]
            d["item_count_ok"] = len(a.get("items") or []) == len(g["items"])
            if "need_submit" in g:
                d["need_submit_ok"] = bool(a.get("need_submit")) == g["need_submit"]
                d["need_submit_got"] = a.get("need_submit")

            # 题项：每个基准项在候选里找覆盖率最高的那个
            got_items = a.get("items") or []
            per = []
            for gi in g["items"]:
                bestk, bestc = None, -1
                for it in got_items:
                    c, t, miss = cov(gi["must_include"],
                                     (it.get("title") or "") + " " + (it.get("requirement") or ""))
                    if c > bestc:
                        bestk, bestc = (c, t, miss, it), c
                per.append({"kw": gi["must_include"],
                            "hit": bestk[0] if bestk else 0,
                            "total": bestk[1] if bestk else len(gi["must_include"]),
                            "miss": bestk[2] if bestk else gi["must_include"]})
            d["items"] = per
            d["kw_hit"] = sum(p["hit"] for p in per)
            d["kw_total"] = sum(p["total"] for p in per)
        else:
            d["got"] = None
            d["kw_hit"] = 0
            d["kw_total"] = sum(len(x["must_include"]) for x in g["items"])
        r["details"].append(d)
    r["extra_assignments"] = len(ga) - len(used)
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="")
    args = ap.parse_args()

    files = sorted(p for p in OUT.glob("*%s.result.json" % args.tag)
                   if len(p.name.split(".")[0]) == 12) if args.tag == "" else \
        sorted(OUT.glob("*%s.result.json" % args.tag))
    if not files:
        raise SystemExit("找不到识别结果，先运行 tools/recognize.py")

    results = []
    for f in files:
        got = json.loads(f.read_text(encoding="utf-8"))
        key = f.name.split(".")[0].split("_")[0]
        gt_db = {k: v for k, v in GT.items() if not k.startswith("_")}
        # 文件名用的是 sha256 前 12 位，基准里写的是 16 位 → 前缀匹配
        gt = next((v for k, v in gt_db.items() if k.startswith(key)), None)
        if not gt:
            print("跳过（无基准）:", f.name)
            continue
        results.append(score_one(key, gt, got))

    # ---------------- 报表 ----------------
    tot = {"assign": 0, "assign_ok": 0, "date": 0, "date_ok": 0,
           "subj": 0, "subj_ok": 0, "cnt": 0, "cnt_ok": 0,
           "kw_hit": 0, "kw_total": 0, "need": 0, "need_ok": 0,
           "viol": 0, "extra": 0, "noise": 0, "noise_ok": 0}

    print("=" * 74)
    print("识别准确率报告   prompt_version=hw-extract-v1")
    print("=" * 74)
    for r in results:
        print("\n【%s】%s" % (r["hash12"], r["image"]))
        print("  作业单：基准 %d 条 / 识别 %d 条  额外多出 %d 条"
              % (r["gt_assignments"], r["got_assignments"], r["extra_assignments"]))
        for d in r["details"]:
            tot["assign"] += 1
            if d["matched"]:
                tot["assign_ok"] += 1
                tot["date"] += 1; tot["date_ok"] += 1 if d.get("date_ok") else 0
                tot["subj"] += 1; tot["subj_ok"] += 1 if d.get("subject_ok") else 0
                tot["cnt"] += 1;  tot["cnt_ok"] += 1 if d.get("item_count_ok") else 0
                if "need_submit_ok" in d:
                    tot["need"] += 1; tot["need_ok"] += 1 if d["need_submit_ok"] else 0
                tot["kw_hit"] += d["kw_hit"]; tot["kw_total"] += d["kw_total"]
                flag = lambda b: "✓" if b else "✗"
                print("   %s %s | 题项 %d/%d %s | 关键信息 %d/%d"
                      % (flag(d.get("date_ok")), d["gt"]["date"],
                         d["got"]["items"], d["gt"]["items"],
                         flag(d.get("item_count_ok")), d["kw_hit"], d["kw_total"]))
                if "need_submit_ok" in d and not d["need_submit_ok"]:
                    print("       ⚠️ need_submit 判错：基准=%s 识别=%s"
                          % (d["gt"]["need_submit"], d["need_submit_got"]))
                for p in d["items"]:
                    if p["miss"]:
                        print("       缺: %s" % "、".join(p["miss"]))
            else:
                tot["kw_total"] += d["kw_total"]
                print("   ✗ 该作业单完全未匹配到: %s" % d["gt"])
        if "noise_caught" in r:
            tot["noise"] += 1; tot["noise_ok"] += 1 if r["noise_caught"] else 0
            print("   噪声拦截(【反馈】)：%s" % ("✓" if r["noise_caught"] else "✗"))
        if "attachments_ok" in r:
            print("   附件登记：%d 条 %s（未逐题转录=%s）"
                  % (r["attachments"], "✓" if r["attachments_ok"] else "✗",
                     "✓" if not r["violations"] else "?"))
        for v in r["violations"]:
            tot["viol"] += 1
            print("   ⚠️ 违规: %s" % v)

    def pct(a, b):
        return "%d/%d = %.0f%%" % (a, b, 100.0 * a / b) if b else "n/a"

    print("\n" + "=" * 74)
    print("汇总")
    print("=" * 74)
    print("  作业单召回率      :", pct(tot["assign_ok"], tot["assign"]))
    print("  日期准确率        :", pct(tot["date_ok"], tot["date"]))
    print("  科目准确率        :", pct(tot["subj_ok"], tot["subj"]))
    print("  题项数准确率      :", pct(tot["cnt_ok"], tot["cnt"]))
    print("  关键信息覆盖率    :", pct(tot["kw_hit"], tot["kw_total"]))
    print("  need_submit 准确率:", pct(tot["need_ok"], tot["need"]))
    print("  噪声拦截率        :", pct(tot["noise_ok"], tot["noise"]))
    print("  严重违规(编造/混入):", tot["viol"])
    print("  多出的作业单条数  :", sum(r["extra_assignments"] for r in results))

    (OUT / ("score-report%s.json" % args.tag)).write_text(
        json.dumps({"totals": tot, "results": results}, ensure_ascii=False, indent=2),
        encoding="utf-8")
    print("\n明细:", OUT / ("score-report%s.json" % args.tag))


if __name__ == "__main__":
    main()
