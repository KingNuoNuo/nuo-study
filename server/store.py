# -*- coding: utf-8 -*-
"""数据访问层：截图入库、草稿落库、家长修正、发布。

两条必须守住的规则：
  1. **识别结果永远是草稿**（assignment.status=0）。模型输出绝不直接成为孩子的作业。
  2. **幂等**。同一张图重传 / 任务重放，不能让作业项翻倍。
     ⚠️ 教训：父表 ON CONFLICT DO NOTHING 保护不了子表，子表必须自己处理。
"""
from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from . import config
from .db import now_iso, tx
from .recognizer import (ANSWER_MODE_MAP, ANSWER_MODE_NAME, ITEM_TYPE_MAP,
                         ITEM_TYPE_NAME, SUBJECT_MAP, SUBJECT_NAME, coerce_enum)

DRAFT, PUBLISHED, DONE, ARCHIVED = 0, 1, 2, 3
STATUS_NAME = {0: "draft", 1: "published", 2: "completed", 3: "archived"}


class ConflictError(Exception):
    """业务冲突，HTTP 409。"""


class NotFound(Exception):
    """资源不存在，HTTP 404。"""


# ---------------------------------------------------------------- 截图
def save_image(raw: bytes, filename: str) -> tuple[str, str]:
    """落盘并返回 (sha256, storage_key)。按哈希前两位分目录，避免单目录堆几万文件。"""
    h = hashlib.sha256(raw).hexdigest()
    ext = (Path(filename).suffix or ".jpg").lower()
    if ext not in (".jpg", ".jpeg", ".png", ".webp", ".heic"):
        ext = ".jpg"
    key = "%s/%s%s" % (h[:2], h[:16], ext)
    p = config.IMAGE_DIR / key
    p.parent.mkdir(parents=True, exist_ok=True)
    if not p.exists():
        p.write_bytes(raw)
    return h, key


def image_path(storage_key: str) -> Path:
    return config.IMAGE_DIR / storage_key


def upsert_capture(con: sqlite3.Connection, student_id: int, device_id: str | None,
                   raw: bytes, filename: str, width=None, height=None) -> dict:
    """同一张图（sha256 相同）只建一条 capture —— 重复拍/误删重传都会命中这里。"""
    h, key = save_image(raw, filename)
    with tx(con):
        row = con.execute(
            "SELECT id FROM capture WHERE image_hash=? ORDER BY id LIMIT 1", (h,)).fetchone()
        if row:
            return {"capture_id": row["id"], "image_hash": h, "storage_key": key,
                    "dedup": True}
        con.execute(
            """INSERT INTO capture (student_id, device_id, image_hash, storage_key,
                                    width, height, byte_size, status)
               VALUES (?,?,?,?,?,?,?,1)""",
            (student_id, device_id, h, key, width, height, len(raw)))
        cid = con.execute("SELECT last_insert_rowid()").fetchone()[0]
    return {"capture_id": cid, "image_hash": h, "storage_key": key, "dedup": False}


def get_capture(con: sqlite3.Connection, capture_id: int) -> dict:
    r = con.execute("SELECT * FROM capture WHERE id=?", (capture_id,)).fetchone()
    if not r:
        raise NotFound("截图不存在: %s" % capture_id)
    return dict(r)


def capture_upload_date(con: sqlite3.Connection, job_id: int | None) -> str | None:
    """识别任务对应截图的**上传日期**（东八区，yyyy-MM-dd）。

    用途：聊天截图常常整图没有日期（状态栏只有「15:24」这种时刻），模型只能
    把 assign_date 交成 null。家长几乎总是当天拍照/存图上传，所以上传日期是
    最可靠的推定来源。拿不到（job_id 为空 / 记录缺失 / 时间格式异常）就返回
    None，由调用方退回「不建作业单」的老路。
    """
    if not job_id:
        return None
    row = con.execute(
        """SELECT c.created_at AS created_at FROM recognition_job j
           JOIN capture c ON c.id = j.capture_id WHERE j.id=?""", (job_id,)).fetchone()
    raw = row["created_at"] if row else None
    if not raw:
        return None
    try:
        dt = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is None:  # schema 默认值带 Z；naive 值按 UTC 防御性处理
        dt = dt.replace(tzinfo=timezone.utc)
    # ⚠️ created_at 是 UTC，家长都在东八区 —— 早上 8 点前上传时 UTC 还停在
    # 昨天，直接截前 10 位会偶发「作业落到昨天」，必须先 +8h 再取日期。
    return (dt + timedelta(hours=8)).date().isoformat()


# ---------------------------------------------------------------- 草稿落库
def materialize_draft(con: sqlite3.Connection, job_id: int, student_id: int,
                      parsed: dict) -> list[dict]:
    """把识别草稿灌进 assignment / assignment_item。**status 恒为 0**。"""
    out = []
    with tx(con):
        for a in parsed.get("assignments") or []:
            date = (a.get("assign_date") or "").strip() or None
            subject = coerce_enum(a.get("subject"), SUBJECT_MAP, 9) or 9
            title = (a.get("title") or "").strip() or None
            entry = {"assign_date": date, "subject": SUBJECT_NAME.get(subject),
                     "title": title, "materialized": False,
                     "assignment_id": None, "item_count": 0}

            if not date:
                # 旧策略是「不建作业单，推回家长补日期」（当时担心瞎填「今天」）。
                # 实测这就是家长嘴里的「无法识别」：题项提取全对，只因为图上
                # 没日期整单凭空消失。新策略：用 capture 上传日期推定 ——
                # 来源可解释（entry.date_basis）、入库即草稿、家长确认时可改日期，
                # 不是瞎填。拿不到上传时间才退回老路。
                fallback = capture_upload_date(con, job_id)
                if not fallback:
                    entry["reason"] = ("assign_date 无法确定且无上传时间可推定，"
                                       "未建作业单（需家长补日期）")
                    out.append(entry)
                    continue
                date = fallback
                entry["assign_date"] = fallback
                entry["date_basis"] = ("图中无日期，按上传时间推定为 %s（请核对）"
                                       % fallback)
            if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
                entry["reason"] = "日期格式非法: %s" % date
                out.append(entry)
                continue

            con.execute(
                """INSERT INTO assignment
                     (student_id, assign_date, subject, title, source, status, need_submit)
                   VALUES (?,?,?,?,2,0,?)
                   ON CONFLICT (student_id, assign_date, subject) DO NOTHING""",
                (student_id, date, subject, title,
                 1 if a.get("need_submit", True) else 0))
            row = con.execute(
                """SELECT id, status, title, need_submit FROM assignment
                   WHERE student_id=? AND assign_date=? AND subject=?""",
                (student_id, date, subject)).fetchone()
            if row is None:
                entry["reason"] = "作业单插入后查不到，跳过"
                out.append(entry)
                continue
            aid, astatus = row["id"], row["status"]
            entry["assignment_id"] = aid
            entry["title"] = row["title"]

            # 关联要在这里建立，**不能等到落库成功才建**。
            # job_assignment 的语义是「这次识别涉及了哪些作业单」——
            # 即使因为已发布而选择不覆盖，这张单子也确实来自这次识别。
            # 否则家长端会看到「识别完成，产出 0 条作业单」这种莫名其妙的提示。
            if job_id:
                con.execute(
                    """INSERT OR IGNORE INTO job_assignment (job_id, assignment_id)
                       VALUES (?,?)""", (job_id, aid))

            # 幂等：草稿可被重识别覆盖；已发布一律不动，转人工修正流程
            if astatus != DRAFT:
                entry["reason"] = "作业单已发布(status=%s)，已关联但未覆盖" % astatus
                entry["skipped"] = True
                out.append(entry)
                continue
            # 标题只在为空时由模型补。家长改过的标题**不覆盖**——
            # 重识别一次就把人改好的标题冲掉，比标题不新鲜糟糕得多。
            # 想彻底重来：先 DELETE /assignments/{id} 删草稿，再重新识别。
            if not (row["title"] or "").strip() and title:
                con.execute("UPDATE assignment SET title=? WHERE id=?", (title, aid))
            con.execute("DELETE FROM assignment_item WHERE assignment_id=?", (aid,))
            for it in a.get("items") or []:
                seq = it.get("seq") or (entry["item_count"] + 1)
                con.execute(
                    """INSERT INTO assignment_item
                         (assignment_id, seq, subject, item_type, title, requirement,
                          est_minutes, status, sort_no, answer_mode)
                       VALUES (?,?,?,?,?,?,?,0,?,?)""",
                    (aid, seq, subject,
                     coerce_enum(it.get("item_type"), ITEM_TYPE_MAP, 2) or 2,
                     (it.get("title") or "").strip() or "(空)",
                     it.get("requirement"), it.get("est_minutes"), seq,
                     coerce_enum(it.get("answer_mode"), ANSWER_MODE_MAP, 1)))
                entry["item_count"] += 1
            entry["materialized"] = True
            out.append(entry)
    return out


def assignments_of_job(con: sqlite3.Connection, job_id: int) -> list[dict]:
    ids = [r["assignment_id"] for r in con.execute(
        "SELECT assignment_id FROM job_assignment WHERE job_id=? ORDER BY assignment_id",
        (job_id,))]
    return [get_assignment(con, i) for i in ids]


def job_of_assignment(con: sqlite3.Connection, aid: int) -> int | None:
    r = con.execute(
        """SELECT job_id FROM job_assignment WHERE assignment_id=?
           ORDER BY job_id DESC LIMIT 1""", (aid,)).fetchone()
    return r["job_id"] if r else None


# ---------------------------------------------------------------- 输出格式
def _camel_key(k: str) -> str:
    """snake_case → camelCase。不含下划线的 key 原样返回（那已经是 camelCase）。"""
    if "_" not in k:
        return k
    head, *tail = k.split("_")
    return head + "".join(w[:1].upper() + w[1:] for w in tail if w)


def camelize(obj):
    """递归把 dict / list 的 key 转成 camelCase。**这是输出边界的唯一转换点。**

    ⚠️ 为什么必须有这一层：契约（docs/api/openapi.yaml）声明的是 camelCase，
       而 SQLite 的列名是 snake_case，`dict(row)` 出来的就是 snake_case。
       不转换的话，客户端按契约写的 DTO **一个字段都取不到** ——
       而且不会报错，只会静默地全取默认值（assignDate=null、itemType=1），
       等发现时已经是一堆「作业日期怎么是空的」这种莫名其妙的现象。

    ⚠️ **不要**用在模型返回的原始 JSON（recognition_job.result / draft）上：
       那是外部数据，键名由提示词定义，转了就等于篡改原始记录，
       以后重放识别结果会与线上不一致。
    """
    if isinstance(obj, dict):
        return {_camel_key(k): camelize(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [camelize(i) for i in obj]
    return obj


# ---------------------------------------------------------------- 作业单查询
ITEM_COLS = """id, assignment_id, seq, subject, item_type, question_id, title,
               requirement, image_key, est_minutes, status, sort_no, answer_mode"""


def _item_to_dict(r: sqlite3.Row) -> dict:
    d = camelize(dict(r))
    d["subjectName"] = SUBJECT_NAME.get(d["subject"])
    d["itemTypeName"] = ITEM_TYPE_NAME.get(d["itemType"])
    d["answerModeName"] = ANSWER_MODE_NAME.get(d["answerMode"])
    return d


def _assignment_to_dict(r: sqlite3.Row, items: list | None = None) -> dict:
    d = camelize(dict(r))
    d["statusName"] = STATUS_NAME.get(d["status"], str(d["status"]))
    d["subjectName"] = SUBJECT_NAME.get(d["subject"], str(d["subject"]))
    # SQLite 里是 0/1，契约声明的是 boolean。kotlinx.serialization **不认 0/1 当布尔**，
    # 不转的话客户端反序列化直接抛异常（比字段缺失更严重，是整个请求失败）。
    d["needSubmit"] = bool(d["needSubmit"])
    if items is not None:
        d["items"] = items
    return d


def get_assignment(con: sqlite3.Connection, aid: int, with_items: bool = True) -> dict:
    r = con.execute("SELECT * FROM assignment WHERE id=?", (aid,)).fetchone()
    if not r:
        raise NotFound("作业单不存在: %s" % aid)
    items = None
    if with_items:
        items = [_item_to_dict(x) for x in con.execute(
            "SELECT %s FROM assignment_item WHERE assignment_id=? ORDER BY seq, id" % ITEM_COLS,
            (aid,))]
    return _assignment_to_dict(r, items)


def list_assignments(con: sqlite3.Connection, assign_date: str | None = None,
                     status: int | None = None, student_id: int | None = None,
                     limit: int = 50) -> list[dict]:
    sql = "SELECT * FROM assignment WHERE 1=1"
    args: list = []
    if assign_date:
        sql += " AND assign_date=?"
        args.append(assign_date)
    if status is not None:
        sql += " AND status=?"
        args.append(status)
    if student_id:
        sql += " AND student_id=?"
        args.append(student_id)
    sql += " ORDER BY assign_date DESC, id DESC LIMIT ?"
    args.append(limit)
    rows = con.execute(sql, args).fetchall()
    if not rows:
        return []

    # ⚠️ 必须一并带上 items。本端点就是给学生端「今日作业」用的（见 openapi.yaml 的
    #    `/assignments` summary），而 `Assignment` schema 里 items 是常规属性。
    #    早先为了省查询用 `_assignment_to_dict(r)` 不带 items，后果是孩子端拉下去
    #    每个作业单的 items 都是空的 → 界面显示「共 0 项」+ 空白。
    #    这个 bug **编译期、静态检查、服务端单测全都看不出来**，
    #    只有把两端真跑一遍端到端才会暴露（2026-10-09 实测踩到）。
    #
    #    这里用「一次 IN 查询 + 内存归组」而不是每个作业单查一次：
    #    按天算最多几条作业单 × 十几个 item，N+1 在家庭局域网里也够慢。
    ids = [r["id"] for r in rows]
    grouped: dict[int, list] = {}
    for x in con.execute(
            "SELECT %s FROM assignment_item WHERE assignment_id IN (%s) "
            "ORDER BY assignment_id, seq, id"
            % (ITEM_COLS, ",".join("?" * len(ids))), ids):
        grouped.setdefault(x["assignment_id"], []).append(_item_to_dict(x))

    return [_assignment_to_dict(r, grouped.get(r["id"], [])) for r in rows]


def list_jobs(con: sqlite3.Connection, limit: int = 50) -> list[dict]:
    return [camelize(dict(r)) for r in con.execute(
        """SELECT j.id, j.capture_id, j.status, j.cache_hit, j.retry_count, j.max_retry,
                  j.error_msg, j.next_retry_at, j.cost_tokens, j.duration_ms,
                  j.created_at, j.finished_at, c.image_hash, c.storage_key
           FROM recognition_job j JOIN capture c ON c.id = j.capture_id
           ORDER BY j.id DESC LIMIT ?""", (limit,))]


# ---------------------------------------------------------------- 家长修正
def add_correction(con: sqlite3.Connection, job_id: int | None, device_id: str | None,
                   field_path: str, before, after) -> None:
    """修正记录是优化提示词的**唯一依据**，务必留档。

    ⚠️ `recognition_correction.job_id` 是 NOT NULL —— 修正记录的价值就在于
       「哪一次识别输出被改成了什么」，没有 job 就没法归因。
       所以脚本 / 手工建的、或 job 已被清理掉的作业单，这里**直接跳过而不是插入 NULL**：
       旧代码是照着插的，结果家长一改这种作业单就吃一个 500
       （IntegrityError: NOT NULL constraint failed，堆栈里一句人话都没有）。
    """
    if job_id is None:
        return
    con.execute(
        """INSERT INTO recognition_correction
             (job_id, device_id, field_path, before_value, after_value)
           VALUES (?,?,?,?,?)""",
        (job_id, device_id, field_path,
         None if before is None else str(before),
         None if after is None else str(after)))


# ⚠️ items 补丁的字段名陷阱：请求体走的是 `ItemPatch`（**camelCase**：
#    itemType / answerMode / estMinutes），而下面写库用的列名是 snake_case。
#    早先这里是按 snake_case 的键去读的（`it.get("item_type")`），后果有两层：
#      ① `itemType` / `answerMode` 被**静默忽略** —— 家长改了题型、保存却看不出来；
#      ② `est_minutes` 取到 None，而它又被**无条件**塞进 cols，
#         于是每改一次作业项，预估耗时就被顺手清成 NULL。
#    所以这里先在本函数内统一转一次，此后只认 snake_case。
_ITEM_ALIASES = (("itemType", "item_type"),
                 ("answerMode", "answer_mode"),
                 ("estMinutes", "est_minutes"))


def _normalize_item(it: dict) -> dict:
    out = dict(it)
    for camel, snake in _ITEM_ALIASES:
        if out.get(snake) is None and out.get(camel) is not None:
            out[snake] = out[camel]
    return out


def patch_assignment(con: sqlite3.Connection, aid: int, body: dict,
                     device_id: str | None = None) -> dict:
    """家长修正作业单。**草稿与已发布都可以改**；每处改动都写进修正记录。

    ⚠️ 早先这里只放行草稿，理由是「已发布的就该冻结」。实践下来这条限制是错的：
       家长发现标题打错、日期选错、少录了一项，唯一的补救是删了重录 ——
       而删除同样被挡住，最后只能 SSH 上去改库。
       单家庭私有部署里家长就是唯一管理员，不存在「别人背着我改了」的问题。
       所以改判：状态 0（草稿）/ 1（已发布）都可改；
       2（已完成）/ 3（已归档）仍然拒绝 —— 那意味着孩子已经在这条上做过题。
    """
    # ⚠️ 必须用 _require_row() 取**原始行**，不能用 get_assignment()。
    #    get_assignment() 返回的 key 已经 camelize 过（assign_date → assignDate），
    #    而下面 field_map 的 value 全是列名 snake_case —— 旧代码写 `cur["assign_date"]`
    #    会直接 KeyError。之所以没炸，只是因为客户端从来没调过这个端点
    #    （确认页只有发布/丢弃两个动作）。同一条规矩：内部逻辑不消费输出格式。
    row = _require_row(con, aid)
    if row["status"] not in (DRAFT, PUBLISHED):
        raise ConflictError("作业单当前是「%s」，不能再修改"
                            % STATUS_NAME.get(row["status"], row["status"]))

    # 改日期可能撞上 UNIQUE(student_id, assign_date, subject) —— 一天一科只能一条。
    # 交给 SQLite 抛 IntegrityError 的话，家长看到的是一句英文栈；
    # 提前查一次，给一句能看懂、能照着做的话。
    new_date = body.get("assignDate")
    if new_date and new_date != row["assign_date"]:
        dup = con.execute(
            "SELECT id FROM assignment WHERE student_id=? AND assign_date=?"
            " AND subject=? AND id<>?",
            (row["student_id"], new_date, row["subject"], aid)).fetchone()
        if dup:
            raise ConflictError(
                "这一天这个科目已经有一条作业单了（#%s）。同一天同一科只能有一条，"
                "请先把那一条删掉，或改用别的日期。" % dup["id"])

    field_map = {"assignDate": "assign_date", "title": "title", "needSubmit": "need_submit"}
    with tx(con):
        # 修正记录挂到产出这条作业单的识别任务上，这样提示词归因才成立
        jid = job_of_assignment(con, aid)

        for api_k, col in field_map.items():
            if api_k not in body:
                continue
            v = body[api_k]
            if api_k == "needSubmit":
                v = 1 if v else 0
            if api_k == "title":
                v = (v or "").strip() or None
            if v != row[col]:
                add_correction(con, jid, device_id, "%s" % api_k, row[col], v)
                con.execute("UPDATE assignment SET %s=? WHERE id=?" % col, (v, aid))

        if "items" in body and body["items"] is not None:
            given = {int(i["id"]) for i in body["items"] if i.get("id")}
            old = {r["id"]: dict(r) for r in con.execute(
                "SELECT %s FROM assignment_item WHERE assignment_id=?" % ITEM_COLS, (aid,))}
            # 目录里没有的项 = 家长删掉的
            for oid in set(old) - given:
                add_correction(con, jid, device_id, "items[%d].deleted" % oid,
                               old[oid]["title"], None)
                con.execute("DELETE FROM assignment_item WHERE id=?", (oid,))
            for n, raw in enumerate(body["items"], start=1):
                it = _normalize_item(raw)
                # 只收集**本次真的给了**的列。早先是无条件写 seq/title/requirement/
                # est_minutes，导致「只想改标题」的补丁顺手把要求清空、耗时清 NULL。
                cols: dict = {}
                if it.get("seq") is not None:
                    cols["seq"] = int(it["seq"]) or n
                if it.get("title") is not None:
                    cols["title"] = (it["title"] or "").strip() or "(空)"
                if "requirement" in it:
                    cols["requirement"] = it["requirement"]
                if it.get("est_minutes") is not None:
                    cols["est_minutes"] = it["est_minutes"]
                if it.get("item_type") is not None:
                    cols["item_type"] = coerce_enum(it["item_type"], ITEM_TYPE_MAP, 2)
                if it.get("answer_mode") is not None:
                    cols["answer_mode"] = coerce_enum(it["answer_mode"], ANSWER_MODE_MAP, 1)
                if it.get("subject") is not None:
                    cols["subject"] = coerce_enum(it["subject"], SUBJECT_MAP, 9)

                if it.get("id"):
                    oid = int(it["id"])
                    if not cols:
                        continue
                    for k, v in cols.items():
                        if oid in old and old[oid][k] != v:
                            add_correction(con, jid, device_id,
                                           "items[%d].%s" % (oid, k), old[oid][k], v)
                    sets = ", ".join("%s=?" % k for k in cols)
                    con.execute("UPDATE assignment_item SET %s WHERE id=?" % sets,
                                (*cols.values(), oid))
                else:
                    cols.setdefault("seq", n)
                    cols.setdefault("title", "")
                    cols.setdefault("requirement", None)
                    cols.setdefault("est_minutes", None)
                    cols.setdefault("subject", SUBJECT_MAP.get(9, 9))
                    cols.setdefault("item_type", 2)
                    cols.setdefault("answer_mode", 1)
                    keys = list(cols)
                    con.execute(
                        "INSERT INTO assignment_item (assignment_id, sort_no, status, %s)"
                        " VALUES (?,?,0,%s)"
                        % (", ".join(keys), ", ".join("?" * len(keys))),
                        (aid, cols["seq"], *[cols[k] for k in keys]))
    return get_assignment(con, aid)


def _require_row(con: sqlite3.Connection, aid: int) -> sqlite3.Row:
    """取作业单的**原始行**。内部校验一律用它，别用 get_assignment()。

    ⚠️ get_assignment() 返回的是「输出格式」（key 已 camelize），
       内部逻辑去读它会因为 key 名变化而 KeyError —— 这个坑真的踩过：
       加完 camelize 之后 publish_assignment 里的 cur["assign_date"] 直接崩。
       数据访问归数据访问，序列化归序列化，别在一个函数里混着做。
    """
    r = con.execute("SELECT * FROM assignment WHERE id=?", (aid,)).fetchone()
    if not r:
        raise NotFound("作业单不存在: %s" % aid)
    return r


def publish_assignment(con: sqlite3.Connection, aid: int,
                       device_id: str | None = None) -> dict:
    """草稿 → 已发布。发布前必须过校验，否则孩子端会出现一条做不了的作业。"""
    r = _require_row(con, aid)
    if r["status"] == PUBLISHED:
        return get_assignment(con, aid)   # 幂等：重复发布返回当前状态，不报错
    if r["status"] != DRAFT:
        raise ConflictError("只有草稿可以发布（当前 %s）"
                            % STATUS_NAME.get(r["status"], r["status"]))
    if not r["assign_date"]:
        raise ConflictError("缺少作业日期，无法发布")

    items = [dict(x) for x in con.execute(
        "SELECT %s FROM assignment_item WHERE assignment_id=? ORDER BY seq, id" % ITEM_COLS,
        (aid,))]
    if not items:
        raise ConflictError("作业单没有任何作业项，无法发布")
    # 校验作业项内容。空标题的项发下去，孩子端会显示一个点不开的空白条目。
    for it in items:
        if not (it.get("title") or "").strip() or it["title"] == "(空)":
            raise ConflictError("存在空的作业项（seq=%s），请先修正" % it["seq"])

    with tx(con):
        con.execute(
            """UPDATE assignment SET status=?, confirmed_by_device=?, confirmed_at=?
               WHERE id=?""",
            (PUBLISHED, device_id, now_iso(), aid))
    return get_assignment(con, aid)


def delete_assignment(con: sqlite3.Connection, aid: int) -> None:
    """删除作业单。**草稿和已发布都能删**。

    ⚠️ 早先只允许删草稿（「别误删孩子正在做的作业」）。这条也被放开了，理由同
       [patch_assignment]：家长录错了一条已发布的，唯一出路就是删掉重录，
       挡在服务端只会逼他去 SSH 改库（真发生过）。
       误删的代价由**客户端**兜 —— 界面上删除必须走二次确认弹窗，
       并把「孩子端下次刷新就会消失」写清楚。

    2（已完成）/ 3（已归档）仍然禁止：那说明孩子已经在这条上做过题，
    删掉会让他的作答记录（answer_record.assignment_item_id）失去归属。

    assignment_item / job_assignment 的外键都是 ON DELETE CASCADE，
    且每个连接都开了 `PRAGMA foreign_keys=ON`（见 db.connect），所以这里不用手工清。
    """
    r = _require_row(con, aid)
    if r["status"] not in (DRAFT, PUBLISHED):
        raise ConflictError("只有草稿或已发布的作业单可以删除（当前 %s）"
                            % STATUS_NAME.get(r["status"], r["status"]))
    with tx(con):
        con.execute("DELETE FROM assignment WHERE id=?", (aid,))
