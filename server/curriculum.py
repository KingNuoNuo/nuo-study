# -*- coding: utf-8 -*-
"""课程内容层：教材单元 / 单词 / 句型 / 课文 / 练习。

数据只有一份，在 `web/english/curriculum.json`，由 `tools/build_curriculum.py`
从教材页图里抽出来（模型抽取 + 人工校对）。服务端**只读不写**。

## 为什么是 JSON 文件，不是 SQLite 表

- 这是**作者（家长）改的内容**，不是运行时产生的数据。写进库里会让「改一个单词」
  变成「连上 NAS 执行一条 UPDATE」，而改 JSON 就是改文本文件、push 一下就完了。
- 服务端已经有 SQLite，但那是**孩子做的事**（作业、答题、错题）。两者的生命周期
  完全不同：教材一学期变一次，答题每天都变。混在一张库的备份策略里只会互相拖累。
- 读起来零成本：文件几百 KB，按 mtime 缓存在内存里，改完 JSON 重启即生效。

## 字段为什么有 `status`

教材是分批录入的（目前录到 Unit 5，6-8 只有标题）。界面必须能如实区分
「这个单元有内容」和「这个单元还没录」，否则孩子点进去看到空白会以为坏了。
  ready   —— 单词 / 课文 / 练习都有
  partial —— 有部分内容（比如只有单词）
  missing —— 只有标题，正文还没录
"""
from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException

from . import config

router = APIRouter()

_lock = threading.Lock()
_cache: dict[str, Any] = {"mtime": -1.0, "data": None}


class CurriculumError(Exception):
    pass


def _path() -> Path:
    return config.CURRICULUM_FILE


def load() -> dict:
    """读课程文件，按 mtime 缓存。文件坏了要**明确报错**，不要静默返回空。"""
    path = _path()
    if not path.exists():
        return {"version": 0, "source": "", "units": [],
                "_warning": "课程文件不存在：%s" % path}
    mtime = path.stat().st_mtime
    with _lock:
        if _cache["data"] is not None and abs(_cache["mtime"] - mtime) < 1e-6:
            return _cache["data"]
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            raise CurriculumError("课程文件解析失败（%s）：%s" % (path, exc))
        _cache["data"], _cache["mtime"] = data, mtime
        return data


def units() -> list[dict]:
    return list(load().get("units") or [])


def unit(number: int) -> dict:
    for u in units():
        if int(u.get("unit") or 0) == number:
            return u
    raise HTTPException(status_code=404, detail="没有 Unit %d" % number)


# ---------------------------------------------------------------- 接口
@router.get("/curriculum")
def get_curriculum() -> dict:
    """整本教材。网页一次拿全（几百 KB），换单元不用再请求。

    ⚠️ 不要给英语这组接口套 `{code,data}` 信封：网页 `app.js` 直接读 `units` 字段。
    """
    data = load()
    return {
        "version": data.get("version", 0),
        "source": data.get("source", ""),
        "updatedAt": data.get("updatedAt", ""),
        "units": data.get("units") or [],
        "warning": data.get("_warning", ""),
    }


@router.get("/curriculum/{number}")
def get_unit(number: int) -> dict:
    return unit(number)


@router.get("/units")
def get_units() -> dict:
    """精简列表：单元选择器只要这几个字段，不用把全部单词拖下来。"""
    return {"units": [{
        "unit": u.get("unit"),
        "title": u.get("title", ""),
        "titleZh": u.get("titleZh", ""),
        "module": u.get("module", ""),
        "bigQuestion": u.get("bigQuestion", ""),
        "status": u.get("status", "missing"),
        "words": len(u.get("words") or []),
        "sentences": len(u.get("sentences") or []),
        "quiz": len(u.get("quiz") or []),
        "hasPassage": bool((u.get("passage") or {}).get("lines")),
    } for u in units()]}
