# -*- coding: utf-8 -*-
"""英语学习进度：**网页 / 平板 / 家长手机读的是同一份数据**。

## 为什么要这个模块

改造前三端各记各的：

| 端 | 学情存在哪 |
|---|---|
| 网页 `web/english` | 浏览器 localStorage（换台设备就没了） |
| 平板 `feature-english` | ViewModel 内存（切到数学再切回来就清空） |
| 家长手机 `feature-report` | 干脆是空占位，只有一句「依赖 M2，当前暂缓」 |

于是「孩子今天在平板上跟读了 5 句、错了 2 道题」家长永远看不到。

现在唯一的事实在这张表里：

    POST /api/v1/english/progress   客户端**上报增量**
    GET  /api/v1/english/progress   任何端读同一份汇总

## 上报是「增量」，不是「覆盖」

客户端每次只报**这次新增了多少**（翻过一个新词就 wordsSeen=1），服务端做加法。
这样平板和网页可以同时用，各自的数字会汇总到一起，不会互相盖掉。

代价是**不能重放**：同一个动作上报两次就多算一次。所以去重必须在客户端做
（`EnglishViewModel.wordsSeen` 是 Set，`mistakes` 按题面去重，就是这个原因）。

## 为什么存聚合而不是明细事件

英语的学习行为是「高频、低价值」的：翻一个词、答一道题都算一次，
一天几十上百条。存明细三个月就是几万行，而家长真正看的只有「这周怎样」。
真需要追溯某一次跟读，服务端日志里有（`POST /evaluate` 的评分记录）。
"""
from __future__ import annotations

from datetime import date, datetime, timedelta

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from . import db

router = APIRouter()

# 一天最多允许上报的量（防御脏数据 / 时钟错乱）。
# 孩子再猛也不可能一天翻 500 个词、答 500 道题 —— 超了就是客户端有 bug。
MAX_PER_REPORT = 200


class ProgressIn(BaseModel):
    """一次上报。字段全是**增量**，缺省表示这一类没有新增。"""

    unit: int
    # 客户端本地日期。不传则用服务端日期（容器 TZ=Asia/Shanghai）。
    # 让客户端传是因为「晚上 11 点学的算哪一天」该按孩子的设备算，不是按 NAS。
    day: str = ""
    wordsSeen: int = 0
    wordsKnown: int = 0
    quizTotal: int = 0
    quizCorrect: int = 0
    speakCount: int = 0
    # 这一次跟读的总分（0~100）。服务端累加，读取时算平均。
    speakScore: float | None = None


# ---------------------------------------------------------------- 工具
def _conn():
    return db.thread_conn()


def _student_id(con) -> int:
    """单家庭部署：只有第一个学生。没有就报错，而不是静默写成 0。"""
    row = con.execute("SELECT id FROM student ORDER BY id LIMIT 1").fetchone()
    if not row:
        raise HTTPException(status_code=500, detail="数据库里没有学生记录")
    return int(row["id"])


def _normalize_day(raw: str) -> str:
    """`YYYY-MM-DD`；不合法就用今天，未来日期退回今天。

    ⚠️ 不报 400 而是**纠正**：孩子平板的时钟可能比 NAS 快几分钟，
    跨零点时就会传一个「明天」的日期。为这个让整次上报丢数据不值得。
    """
    today = date.today()
    text = (raw or "").strip()
    if not text:
        return today.isoformat()
    try:
        parsed = datetime.strptime(text, "%Y-%m-%d").date()
    except ValueError:
        return today.isoformat()
    if parsed > today:
        return today.isoformat()
    # 太老的也不收（比如设备时钟被重置到 1970）
    if parsed < today - timedelta(days=400):
        return today.isoformat()
    return parsed.isoformat()


def _clamp(value: int) -> int:
    if value <= 0:
        return 0
    return min(int(value), MAX_PER_REPORT)


def _avg(total: float, count: int) -> float:
    return round(total / count, 1) if count > 0 else 0.0


# ---------------------------------------------------------------- 接口
@router.post("/progress")
def post_progress(body: ProgressIn) -> dict:
    """上报一次增量。客户端调完就当成功，不做重试（增量重试会双计）。"""
    unit = int(body.unit)
    if not 1 <= unit <= 12:
        raise HTTPException(status_code=422, detail="unit 需为 1~12")

    words_seen = _clamp(body.wordsSeen)
    words_known = _clamp(body.wordsKnown)
    quiz_total = _clamp(body.quizTotal)
    quiz_correct = min(_clamp(body.quizCorrect), quiz_total or _clamp(body.quizCorrect))
    speak_count = _clamp(body.speakCount)
    speak_score = float(body.speakScore) if body.speakScore is not None else None
    if speak_score is not None:
        speak_score = max(0.0, min(100.0, speak_score))
        # 有分数就必须有一次计数，否则平均值分母是 0
        speak_count = speak_count or 1
    else:
        speak_count = 0  # 没有分数就没有跟读这回事

    if not any((words_seen, words_known, quiz_total, quiz_correct, speak_count)):
        return {"ok": True, "skipped": True, "reason": "没有可累加的增量"}

    day = _normalize_day(body.day)
    con = _conn()
    sid = _student_id(con)
    con.execute(
        """
        INSERT INTO english_progress
            (student_id, day, unit, words_seen, words_known,
             quiz_total, quiz_correct, speak_count, speak_score_sum, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(student_id, day, unit) DO UPDATE SET
            words_seen      = words_seen      + excluded.words_seen,
            words_known     = words_known     + excluded.words_known,
            quiz_total      = quiz_total      + excluded.quiz_total,
            quiz_correct    = quiz_correct    + excluded.quiz_correct,
            speak_count     = speak_count     + excluded.speak_count,
            speak_score_sum = speak_score_sum + excluded.speak_score_sum,
            updated_at      = excluded.updated_at
        """,
        (
            sid, day, unit, words_seen, words_known,
            quiz_total, quiz_correct, speak_count, speak_score or 0.0,
            db.now_iso(),
        ),
    )
    return {"ok": True, "day": day, "unit": unit}


@router.get("/progress")
def get_progress(days: int = 7) -> dict:
    """最近 N 天的汇总。**三端都用这个**，所以字段名要稳定。"""
    span = max(1, min(int(days or 7), 90))
    start = (date.today() - timedelta(days=span - 1)).isoformat()

    con = _conn()
    sid = _student_id(con)

    # 按天 → 给「趋势」
    daily_rows = con.execute(
        """
        SELECT day,
               SUM(words_seen)      AS words_seen,
               SUM(words_known)     AS words_known,
               SUM(quiz_total)      AS quiz_total,
               SUM(quiz_correct)    AS quiz_correct,
               SUM(speak_count)     AS speak_count,
               SUM(speak_score_sum) AS speak_score_sum
        FROM english_progress
        WHERE student_id = ? AND day >= ?
        GROUP BY day
        ORDER BY day
        """,
        (sid, start),
    ).fetchall()

    # 按单元 → 给「哪几个单元在学」
    unit_rows = con.execute(
        """
        SELECT unit,
               SUM(words_seen)      AS words_seen,
               SUM(words_known)     AS words_known,
               SUM(quiz_total)      AS quiz_total,
               SUM(quiz_correct)    AS quiz_correct,
               SUM(speak_count)     AS speak_count,
               SUM(speak_score_sum) AS speak_score_sum
        FROM english_progress
        WHERE student_id = ? AND day >= ?
        GROUP BY unit
        ORDER BY unit
        """,
        (sid, start),
    ).fetchall()

    def pack(row) -> dict:
        speak_count = int(row["speak_count"] or 0)
        quiz_total = int(row["quiz_total"] or 0)
        quiz_correct = int(row["quiz_correct"] or 0)
        return {
            "wordsSeen": int(row["words_seen"] or 0),
            "wordsKnown": int(row["words_known"] or 0),
            "quizTotal": quiz_total,
            "quizCorrect": quiz_correct,
            "quizAccuracy": round(quiz_correct / quiz_total, 3) if quiz_total else 0.0,
            "speakCount": speak_count,
            "speakAvg": _avg(float(row["speak_score_sum"] or 0.0), speak_count),
        }

    daily = [dict(day=row["day"], **pack(row)) for row in daily_rows]
    units = [dict(unit=int(row["unit"]), **pack(row)) for row in unit_rows]

    totals = {
        "days": len(daily),
        "wordsSeen": sum(d["wordsSeen"] for d in daily),
        "wordsKnown": sum(d["wordsKnown"] for d in daily),
        "quizTotal": sum(d["quizTotal"] for d in daily),
        "quizCorrect": sum(d["quizCorrect"] for d in daily),
        "speakCount": sum(d["speakCount"] for d in daily),
    }
    totals["quizAccuracy"] = (round(totals["quizCorrect"] / totals["quizTotal"], 3)
                              if totals["quizTotal"] else 0.0)
    score_sum = sum(float(r["speak_score_sum"] or 0.0) for r in daily_rows)
    totals["speakAvg"] = _avg(score_sum, totals["speakCount"])

    student = con.execute("SELECT id, name, grade FROM student WHERE id = ?",
                          (sid,)).fetchone()

    return {
        "ok": True,
        "student": {"id": int(student["id"]), "name": student["name"],
                    "grade": int(student["grade"])} if student else None,
        "range": {"days": span, "from": start, "to": date.today().isoformat()},
        "totals": totals,
        "daily": daily,
        "units": units,
    }
