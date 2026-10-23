# -*- coding: utf-8 -*-
"""学习域：作答落库、错题本、知识点掌握度、自主练习、学情报告。
⚠️【已暂缓 · 未接入】M2 整体搁置，本模块不被任何路由引用（`api.py` 已摘除 import）。

保留原因：三条不变式已经定死、派生逻辑（掌握度 / 错题本 / 报告）可直接复用，
恢复时不必重写。恢复方式：在 `api.py` 末尾接回 8 个端点。

三条不变式：
  1. **作答提交幂等**。`client_req_id` 是唯一键；重复提交（离线补传）必须回放第一次的结果，
     **不得二次批改、不得二次累加掌握度** —— 否则孩子断网重传一次，掌握度就悄悄少一点。
  2. **作业与学习共用 answer_record**，靠 assignment_item_id / practice_session_id 区分
     （CHECK 约束保证有且仅有一个非空）。学情统计只读这一张表，不写 UNION。
  3. **错题本与掌握度都是从 answer_record 派生的**，不制造第二份事实；
     它们只是为了让家长端「不每次全表聚合」而冗余出来的物化视图。

判定的部分在 grader.py，本模块只负责「落库 + 派生」。
"""
from __future__ import annotations

import json
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

import sqlite3

from . import grader, store
from .db import now_iso, parse_iso, tx

# 艾宾浩斯复习间隔（天）。错题重做答对一阶升一级，走完即视为掌握。
REVIEW_DAYS = [1, 2, 4, 7, 15, 30]

# 题库题型的两个派生映射（question.qtype → item_type / answer_mode）
QTYPE_NAME = {1: "选择", 2: "填空", 3: "计算", 4: "解答", 5: "阅读", 6: "作文", 7: "听写"}
QTYPE_ITEM_TYPE = {1: 1, 2: 1, 3: 1, 4: 2, 5: 2, 6: 2, 7: 1}
QTYPE_ANSWER_MODE = {1: 1, 2: 1, 3: 2, 4: 2, 5: 1, 6: 2, 7: 1}


# ---------------------------------------------------------------- 时区
def _tz(con: sqlite3.Connection) -> ZoneInfo:
    r = con.execute("SELECT timezone FROM family WHERE id=1").fetchone()
    name = (r["timezone"] if r else None) or "Asia/Shanghai"
    try:
        return ZoneInfo(name)
    except Exception:
        return ZoneInfo("Asia/Shanghai")


def _local_date(iso_s: str, tz: ZoneInfo) -> date:
    return parse_iso(iso_s).astimezone(tz).date()


def _day_add(days: int) -> str:
    return (datetime.now(ZoneInfo("UTC")) + timedelta(days=days)).strftime(
        "%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


# ---------------------------------------------------------------- 派生：掌握度 / 错题本
def _apply_grading(con: sqlite3.Connection, student_id: int, question_id: int | None,
                   is_correct: int | None, from_wrong_book: bool = False) -> None:
    """把一次判定落到 mastery 与 wrong_book 上。**调用方必须已在事务里**。

    只认「有明确对错」的判定：is_correct 为 None（待批 / 打卡）不参与统计，
    否则待批的题会把正确率往下拉，家长看到的就是一个假的下滑曲线。
    """
    if not question_id or is_correct is None:
        return
    now = now_iso()

    # 知识点掌握度：一道题可能挂多个知识点，每个都要更新
    kps = [r["kp_id"] for r in con.execute(
        "SELECT kp_id FROM question_knowledge WHERE question_id=?", (question_id,))]
    for kp in kps:
        # ⚠️ SQLite 的 ON CONFLICT DO UPDATE 里，SET 右值一律基于**更新前的行**，
        #    所以 score 表达式里引用的 correct_cnt / total_cnt 都是旧值，与书写顺序无关。
        con.execute(
            """INSERT INTO mastery (student_id, kp_id, total_cnt, correct_cnt, score, updated_at)
               VALUES (?,?,1,?,?,?)
               ON CONFLICT (student_id, kp_id) DO UPDATE SET
                 total_cnt   = total_cnt + 1,
                 correct_cnt = correct_cnt + excluded.correct_cnt,
                 score       = CAST(correct_cnt + excluded.correct_cnt AS REAL) / (total_cnt + 1),
                 updated_at  = excluded.updated_at""",
            (student_id, kp, 1 if is_correct else 0, 1.0 if is_correct else 0.0, now))

    row = con.execute("SELECT * FROM wrong_book WHERE student_id=? AND question_id=?",
                      (student_id, question_id)).fetchone()
    if is_correct == 0:
        # 答错：进错题本 / 已有则次数 +1 并把复习进度**打回起点**
        con.execute(
            """INSERT INTO wrong_book (student_id, question_id, first_wrong_at, last_wrong_at,
                                       wrong_count, review_stage, next_review_at, mastered)
               VALUES (?,?,?,?,1,0,?,0)
               ON CONFLICT (student_id, question_id) DO UPDATE SET
                 last_wrong_at  = excluded.last_wrong_at,
                 wrong_count    = wrong_count + 1,
                 review_stage   = 0,
                 next_review_at = excluded.next_review_at,
                 mastered       = 0""",
            (student_id, question_id, now, now, _day_add(REVIEW_DAYS[0])))
    elif is_correct == 1 and row and not row["mastered"]:
        # 答对且是从错题本里重做的：升一阶；走完全部间隔才算掌握
        stage = row["review_stage"] + 1
        mastered = 1 if stage >= len(REVIEW_DAYS) else 0
        con.execute(
            """UPDATE wrong_book SET review_stage=?, mastered=?, next_review_at=?
               WHERE student_id=? AND question_id=?""",
            (stage, mastered, _day_add(REVIEW_DAYS[min(stage, len(REVIEW_DAYS) - 1)]),
             student_id, question_id))


def _correct_answer(con: sqlite3.Connection, question_id: int | None,
                    stem: str, item_type: int) -> str | None:
    """取正确答案用于回显。答错时给孩子看 —— 这是「错题本」能自我纠错的前提。"""
    if question_id:
        r = con.execute(
            """SELECT answer_text FROM question_answer WHERE question_id=?
               ORDER BY is_primary DESC, id LIMIT 1""", (question_id,)).fetchone()
        if r:
            return r["answer_text"]
    if item_type == 1:
        expr = grader.stem_to_expr(stem or "")
        if expr:
            try:
                return grader.frac_str(grader.eval_expr(expr))
            except (ValueError, SyntaxError, ZeroDivisionError):
                return None
    return None


# ---------------------------------------------------------------- 作答提交
def _result_of(con: sqlite3.Connection, row: sqlite3.Row, duplicate: bool = False) -> dict:
    ic = row["is_correct"]
    d = {"recordId": row["id"],
         "isCorrect": None if ic is None else bool(ic),
         "score": row["score"],
         "gradingMode": row["grading_mode"],
         "gradingPending": ic is None,
         "durationMs": row["duration_ms"],
         "answeredAt": row["answered_at"],
         "duplicate": duplicate}
    if ic == 0:
        stem = ""
        if row["assignment_item_id"]:
            it = con.execute("SELECT title, item_type FROM assignment_item WHERE id=?",
                             (row["assignment_item_id"],)).fetchone()
            if it:
                stem, itype = it["title"], it["item_type"]
                d["correctAnswer"] = _correct_answer(con, row["question_id"], stem, itype)
        elif row["question_id"]:
            q = con.execute("SELECT stem FROM question WHERE id=?",
                            (row["question_id"],)).fetchone()
            if q:
                d["correctAnswer"] = _correct_answer(con, row["question_id"], q["stem"], 1)
    return d


def _submit(con: sqlite3.Connection, student_id: int, item: dict | None,
            session: dict | None, body: dict, from_wrong_book: bool = False) -> dict:
    """作业作答与练习作答的唯一落库路径。两者只有来源字段不同。"""
    rid = (body.get("clientReqId") or "").strip()
    if not rid:
        raise store.ConflictError("clientReqId 必填 —— 离线补传要靠它做幂等")

    dup = con.execute("SELECT * FROM answer_record WHERE client_req_id=?", (rid,)).fetchone()
    if dup:
        # 幂等回放。两种错用要挡住：同一个 clientReqId 被用到别的题 / 别的孩子身上。
        same_item = (dup["assignment_item_id"] == (item or {}).get("id")
                     and dup["practice_session_id"] == (session or {}).get("id"))
        if not same_item or dup["student_id"] != student_id:
            raise store.ConflictError("clientReqId 已被其他作答占用: %s" % rid)
        return _result_of(con, dup, duplicate=True)

    question_id = (item or {}).get("question_id") or body.get("questionId")
    stem = (item or {}).get("title") or ""
    if question_id and not stem:
        q = con.execute("SELECT stem FROM question WHERE id=?", (question_id,)).fetchone()
        stem = q["stem"] if q else ""
    item_type = (item or {}).get("item_type") or 1
    answer_mode = (item or {}).get("answer_mode", 1)

    expected: list[str] = []
    if question_id:
        expected = [r["answer_text"] for r in con.execute(
            """SELECT answer_text FROM question_answer WHERE question_id=?
               ORDER BY is_primary DESC, id""", (question_id,))]

    g = grader.grade(stem, expected, body.get("answerText"), item_type,
                     answer_mode if item else 1)

    media = body.get("mediaKeys")
    with tx(con):
        con.execute(
            """INSERT INTO answer_record
                 (student_id, assignment_item_id, practice_session_id, question_id,
                  answer_text, media_keys, is_correct, score, grading_mode,
                  duration_ms, client_req_id, answered_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            (student_id, (item or {}).get("id"), (session or {}).get("id"), question_id,
             body.get("answerText"),
             json.dumps(media, ensure_ascii=False) if media else None,
             g["is_correct"], g["score"], g["grading_mode"],
             body.get("durationMs"), rid, now_iso()))
        rec_id = con.execute("SELECT last_insert_rowid()").fetchone()[0]
        _apply_grading(con, student_id, question_id, g["is_correct"], from_wrong_book)
        if item:
            _refresh_item_status(con, item["id"], g["is_correct"])

    row = con.execute("SELECT * FROM answer_record WHERE id=?", (rec_id,)).fetchone()
    out = _result_of(con, row)
    if g.get("note"):
        out["note"] = g["note"]
    if g["is_correct"] == 0 and not out.get("correctAnswer"):
        out["correctAnswer"] = g.get("correct_answer")
    return out


def _refresh_item_status(con: sqlite3.Connection, item_id: int, is_correct: int | None) -> None:
    """作业项状态机：0 待做 → 1 已做 → 2 已批改。

    ⚠️ 用 CASE 而不是直接赋值：孩子可以先提交第 3 题再回头补第 1 题，
       状态只能单调前进，不能被后来的「待批」打回去。
    """
    new = 2 if is_correct is not None else 1
    con.execute(
        "UPDATE assignment_item SET status = CASE WHEN status < ? THEN ? ELSE status END "
        "WHERE id=?", (new, new, item_id))
    r = con.execute("SELECT assignment_id FROM assignment_item WHERE id=?",
                    (item_id,)).fetchone()
    if r:
        _refresh_assignment_status(con, r["assignment_id"])


def _refresh_assignment_status(con: sqlite3.Connection, aid: int) -> None:
    """全部作业项都动过了 → 作业单置「已完成」。

    注意「已完成」不等于「全对」，也不要求 all is_correct ——
    含作文、朗读的作业单永远等不到自动批改，不能让状态卡在 1。
    """
    left = con.execute(
        "SELECT COUNT(*) FROM assignment_item WHERE assignment_id=? AND status=0",
        (aid,)).fetchone()[0]
    if left == 0:
        con.execute("UPDATE assignment SET status=? WHERE id=? AND status=?",
                    (store.DONE, aid, store.PUBLISHED))


def submit_answer(con: sqlite3.Connection, assignment_id: int, item_id: int,
                  body: dict, device_id: str | None = None) -> dict:
    a = store.get_assignment(con, assignment_id, with_items=False)
    if a["status"] not in (store.PUBLISHED, store.DONE):
        raise store.ConflictError("作业单未发布（当前 %s），不能作答" % a["statusName"])
    r = con.execute("SELECT %s FROM assignment_item WHERE id=?" % store.ITEM_COLS,
                    (item_id,)).fetchone()
    if not r:
        raise store.NotFound("作业项不存在: %s" % item_id)
    item = dict(r)
    if item["assignment_id"] != assignment_id:
        raise store.NotFound("作业项 %s 不属于作业单 %s" % (item_id, assignment_id))
    return _submit(con, a["student_id"], item, None, body)


def attach_answers(con: sqlite3.Connection, items: list[dict]) -> list[dict]:
    """给作业项附上最近一次作答结果，孩子端一屏就能显示「做没做 / 对不对」。"""
    for it in items:
        r = con.execute(
            """SELECT * FROM answer_record WHERE assignment_item_id=?
               ORDER BY id DESC LIMIT 1""", (it["id"],)).fetchone()
        it["answer"] = _result_of(con, r) if r else None
    return items


# ---------------------------------------------------------------- 自主练习
def _question_items(con: sqlite3.Connection, rows: list[sqlite3.Row]) -> list[dict]:
    """把题库题投影成客户端可直接渲染的题面结构。

    ⚠️ 这里借用了 AssignmentItem 的形状（契约如此），但 id 是 **question_id**，
       不是 assignment_item.id —— 练习的题不挂在任何作业单下。
       客户端渲染时用 questionId 提交作答，别拿 id 去查作业单。
    """
    out = []
    for n, q in enumerate(rows, start=1):
        qt = q["qtype"]
        out.append({
            "id": q["id"], "questionId": q["id"], "seq": n,
            "subject": q["subject"], "qtype": qt, "qtypeName": QTYPE_NAME.get(qt),
            "itemType": QTYPE_ITEM_TYPE.get(qt, 1),
            "title": q["stem"], "requirement": None,
            "answerMode": QTYPE_ANSWER_MODE.get(qt, 1),
            "difficulty": q["difficulty"], "status": 0,
        })
    return out


def start_practice(con: sqlite3.Connection, student_id: int, subject: int, mode: int,
                   kp_id: int | None = None, item_count: int = 10) -> dict:
    """开一次自主练习并选出题单。

    ⚠️ 题单**不持久化**（schema 里没有 practice_item 表），有意为之：
       服务端只记「计划题数 + 每一题的作答」，题面由选题算法随时重现。
       好处是题库更新后题单不会失配；代价是客户端中途重启要自己存题单
       （孩子端 Room 的 local_practice 就是干这个的）。
    """
    item_count = max(1, min(int(item_count or 10), 50))
    rows: list[sqlite3.Row] = []
    if mode == 2:
        # 错题重做：从错题本里挑，优先到期待复习的
        rows = con.execute(
            """SELECT q.* FROM wrong_book w JOIN question q ON q.id = w.question_id
               WHERE w.student_id=? AND w.mastered=0 AND q.subject=?
               ORDER BY (w.next_review_at IS NULL), w.next_review_at, w.wrong_count DESC
               LIMIT ?""", (student_id, subject, item_count)).fetchall()
    elif kp_id:
        rows = con.execute(
            """SELECT q.* FROM question q
               JOIN question_knowledge qk ON qk.question_id = q.id
               WHERE q.status=1 AND q.subject=? AND qk.kp_id=?
               ORDER BY q.difficulty, q.id LIMIT ?""",
            (subject, kp_id, item_count)).fetchall()
    if not rows:
        rows = con.execute(
            "SELECT * FROM question WHERE status=1 AND subject=? ORDER BY difficulty, id LIMIT ?",
            (subject, item_count)).fetchall()

    with tx(con):
        con.execute(
            """INSERT INTO practice_session (student_id, subject, mode, kp_id, item_count)
               VALUES (?,?,?,?,?)""",
            (student_id, subject, mode, kp_id, len(rows) or item_count))
        sid = con.execute("SELECT last_insert_rowid()").fetchone()[0]
    s = con.execute("SELECT * FROM practice_session WHERE id=?", (sid,)).fetchone()
    d = dict(s)
    d["items"] = _question_items(con, rows)
    d["questionCount"] = len(rows)
    if not rows:
        d["note"] = "题库里没有匹配的题目 —— 学习板块要先有题库才能练（M3）"
    return d


def submit_practice_answer(con: sqlite3.Connection, session_id: int, body: dict) -> dict:
    s = con.execute("SELECT * FROM practice_session WHERE id=?", (session_id,)).fetchone()
    if not s:
        raise store.NotFound("练习会话不存在: %s" % session_id)
    if s["finished_at"]:
        raise store.ConflictError("练习已结束（%s），不能再提交" % s["finished_at"])
    qid = body.get("questionId")
    if not qid:
        raise store.ConflictError("练习作答必须带 questionId")
    q = con.execute("SELECT * FROM question WHERE id=?", (qid,)).fetchone()
    if not q:
        raise store.NotFound("题目不存在: %s" % qid)
    # 这道题是不是从错题本里挑出来的？决定答对时是否推进复习阶段
    in_wrong = con.execute(
        "SELECT 1 FROM wrong_book WHERE student_id=? AND question_id=?",
        (s["student_id"], qid)).fetchone() is not None
    item = {"id": None, "question_id": qid, "title": q["stem"],
            "item_type": QTYPE_ITEM_TYPE.get(q["qtype"], 1),
            "answer_mode": QTYPE_ANSWER_MODE.get(q["qtype"], 1)}
    return _submit(con, s["student_id"], item, dict(s), body, from_wrong_book=in_wrong)


def finish_practice(con: sqlite3.Connection, session_id: int) -> dict:
    """结算：从 answer_record 汇总，回写 practice_session。"""
    s = con.execute("SELECT * FROM practice_session WHERE id=?", (session_id,)).fetchone()
    if not s:
        raise store.NotFound("练习会话不存在: %s" % session_id)
    if not s["finished_at"]:
        row = con.execute(
            """SELECT COUNT(*) n,
                      SUM(CASE WHEN is_correct=1 THEN 1 ELSE 0 END) c,
                      SUM(CASE WHEN is_correct IS NOT NULL THEN 1 ELSE 0 END) graded,
                      COALESCE(SUM(duration_ms),0) ms
               FROM answer_record WHERE practice_session_id=?""", (session_id,)).fetchone()
        with tx(con):
            con.execute(
                """UPDATE practice_session SET correct_count=?, item_count=?, duration_ms=?,
                     finished_at=? WHERE id=?""",
                (row["c"] or 0, row["n"] or 0, row["ms"], now_iso(), session_id))
    s = con.execute("SELECT * FROM practice_session WHERE id=?", (session_id,)).fetchone()
    d = dict(s)
    row = con.execute(
        """SELECT COUNT(*) n, SUM(CASE WHEN is_correct IS NOT NULL THEN 1 ELSE 0 END) graded
           FROM answer_record WHERE practice_session_id=?""", (session_id,)).fetchone()
    d["answeredCount"] = row["n"]
    d["gradedCount"] = row["graded"]
    d["accuracy"] = (round(d["correct_count"] / row["graded"], 4)
                     if row["graded"] else None)
    return d


def recommend(con: sqlite3.Connection, student_id: int, subject: int,
              limit: int = 6) -> list[dict]:
    """学习板块首页推荐：弱项知识点 + 到期错题。

    只推「有题可练」的项 —— 推荐了一个空知识点，孩子点进去看到空白页，
    比不推荐还糟。
    """
    out: list[dict] = []
    weak = con.execute(
        """SELECT m.kp_id, m.score, k.name, k.code,
                  (SELECT COUNT(*) FROM question_knowledge qk
                     JOIN question q ON q.id=qk.question_id AND q.status=1
                   WHERE qk.kp_id=m.kp_id) qn
           FROM mastery m JOIN knowledge_point k ON k.id = m.kp_id
           WHERE m.student_id=? AND k.subject=? AND m.score < 0.6
           ORDER BY m.score ASC, qn DESC LIMIT ?""",
        (student_id, subject, limit)).fetchall()
    for r in weak:
        out.append({"kpId": r["kp_id"], "kpName": r["name"], "kpCode": r["code"],
                    "subject": subject, "reason": "weak",
                    "mastery": round(r["score"], 4), "dueCount": 0,
                    "questionCount": r["qn"], "suggestMode": 1})
    due = con.execute(
        """SELECT w.kp_id, COUNT(*) n, k.name, k.code,
                  COALESCE(MIN(w.next_review_at),'') nra
           FROM wrong_book w
           JOIN question q ON q.id = w.question_id
           LEFT JOIN question_knowledge qk ON qk.question_id = q.id
           LEFT JOIN knowledge_point k ON k.id = qk.kp_id
           WHERE w.student_id=? AND q.subject=? AND w.mastered=0
                 AND (w.next_review_at IS NULL OR w.next_review_at <= ?)
           GROUP BY w.kp_id ORDER BY n DESC LIMIT ?""",
        (student_id, subject, now_iso(), limit)).fetchall()
    seen = {x["kpId"] for x in out}
    for r in due:
        if r["kp_id"] in seen:
            continue
        out.append({"kpId": r["kp_id"], "kpName": r["name"] or "综合错题",
                    "kpCode": r["code"], "subject": subject, "reason": "wrong_due",
                    "mastery": None, "dueCount": r["n"], "suggestMode": 2})
    return out[:limit]


# ---------------------------------------------------------------- 错题本
def wrong_book(con: sqlite3.Connection, student_id: int, subject: int | None = None,
               due_only: bool = False) -> list[dict]:
    sql = """SELECT w.*, q.stem, q.subject, q.qtype
             FROM wrong_book w JOIN question q ON q.id = w.question_id
             WHERE w.student_id=?"""
    args: list = [student_id]
    if subject:
        sql += " AND q.subject=?"
        args.append(subject)
    if due_only:
        sql += " AND w.mastered=0 AND (w.next_review_at IS NULL OR w.next_review_at <= ?)"
        args.append(now_iso())
    sql += " ORDER BY w.mastered, w.next_review_at, w.last_wrong_at DESC"
    rows = con.execute(sql, args).fetchall()
    out = []
    for r in rows:
        kps = [x["name"] for x in con.execute(
            """SELECT k.name FROM question_knowledge qk
               JOIN knowledge_point k ON k.id = qk.kp_id
               WHERE qk.question_id=?""", (r["question_id"],))]
        out.append({"id": r["id"], "questionId": r["question_id"], "subject": r["subject"],
                    "qtype": r["qtype"], "stem": r["stem"],
                    "wrongCount": r["wrong_count"], "reviewStage": r["review_stage"],
                    "firstWrongAt": r["first_wrong_at"], "lastWrongAt": r["last_wrong_at"],
                    "nextReviewAt": r["next_review_at"], "mastered": bool(r["mastered"]),
                    "knowledgePoints": kps})
    return out


# ---------------------------------------------------------------- 学情报告
def weekly_report(con: sqlite3.Connection, student_id: int,
                  week_start: str | None = None) -> dict:
    """周学情报告。

    ⚠️ 有意用 Python 聚合而不是写 SQL 算时区：SQLite 的日期函数认不了 IANA 时区，
       `date(answered_at, '+8 hours')` 这种硬编码偏移在改 timezone 后会静默算错。
       单家庭一学期也就几百条作答，全取回来聚合既清晰又不会慢。
    """
    tz = _tz(con)
    today = datetime.now(tz).date()
    ws = (date.fromisoformat(week_start) if week_start
          else today - timedelta(days=today.weekday()))
    ws = ws - timedelta(days=ws.weekday())          # 归一到周一
    we = ws + timedelta(days=7)

    rows = con.execute("SELECT * FROM answer_record WHERE student_id=?",
                       (student_id,)).fetchall()
    in_week = [r for r in rows if ws <= _local_date(r["answered_at"], tz) < we]

    # 该周的作业项总量（按作业单的 assign_date 算，不是按作答时间）
    items = con.execute(
        """SELECT i.id, i.status, a.assign_date FROM assignment_item i
           JOIN assignment a ON a.id = i.assignment_id
           WHERE a.student_id=? AND a.status >= 1 AND a.assign_date >= ? AND a.assign_date < ?""",
        (student_id, ws.isoformat(), we.isoformat())).fetchall()

    graded = [r for r in in_week if r["is_correct"] is not None]
    accuracy = (round(sum(1 for r in graded if r["is_correct"] == 1) / len(graded), 4)
                if graded else None)
    minutes = int(sum((r["duration_ms"] or 0) for r in in_week) // 60000)

    # 连续打卡天数：从今天往前数，哪天没有作答就断
    days = {_local_date(r["answered_at"], tz) for r in rows}
    streak, d = 0, today
    while d in days:
        streak += 1
        d -= timedelta(days=1)

    weak = con.execute(
        """SELECT k.code, k.name, m.score FROM mastery m
           JOIN knowledge_point k ON k.id = m.kp_id
           WHERE m.student_id=? ORDER BY m.score ASC, m.total_cnt DESC LIMIT 5""",
        (student_id,)).fetchall()

    trend = []
    for i in range(6, -1, -1):
        dd = today - timedelta(days=i)
        day = [r for r in rows if _local_date(r["answered_at"], tz) == dd
               and r["is_correct"] is not None]
        trend.append({"date": dd.isoformat(),
                      "answered": len(day),
                      "accuracy": (round(sum(1 for r in day if r["is_correct"] == 1) / len(day), 4)
                                   if day else None)})

    return {
        "studentId": student_id,
        "weekStart": ws.isoformat(), "weekEnd": we.isoformat(),
        "totalItems": len(items),
        "finishedItems": len([i for i in items if i["status"] > 0]),
        "answeredCount": len(in_week),
        "accuracy": accuracy,
        "studyMinutes": minutes,
        "streakDays": streak,
        "wrongCount": len([r for r in in_week if r["is_correct"] == 0]),
        "weakPoints": [{"code": r["code"], "name": r["name"],
                        "score": round(r["score"], 4)} for r in weak],
        "trend": trend,
    }


# ---------------------------------------------------------------- 知识点树
def kp_tree(con: sqlite3.Connection, subject: int, student_id: int | None = None) -> list[dict]:
    rows = con.execute(
        "SELECT * FROM knowledge_point WHERE subject=? ORDER BY sort_no, id",
        (subject,)).fetchall()
    master = {}
    if student_id:
        master = {r["kp_id"]: r["score"] for r in con.execute(
            "SELECT kp_id, score FROM mastery WHERE student_id=?", (student_id,))}
    nodes = {r["id"]: {"id": r["id"], "parentId": r["parent_id"], "subject": r["subject"],
                       "name": r["name"], "code": r["code"],
                       "mastery": (round(master[r["id"]], 4) if r["id"] in master else None),
                       "children": []}
             for r in rows}
    roots = []
    for nid, n in nodes.items():
        p = n["parentId"]
        if p and p in nodes:
            nodes[p]["children"].append(n)
        else:
            roots.append(n)
    return roots
