# -*- coding: utf-8 -*-
"""作业变更的进程内广播（SSE / Server-Sent Events）。

家长端改动作业（发布 / 删除 / 修正）后，服务端在这里向所有订阅者各投一份
轻量事件；孩子端 App **前台**时挂着一条 SSE 长连接，收到事件立刻重新拉取 ——
不用等 6 小时周期任务，也不用孩子手动点刷新。

三条边界，想清楚再动：
  1. **只做通知，不捎数据**。事件体只有 type/action/id，客户端收到后走标准
     GET /assignments 全量拉取 —— 事件丢了没关系（还有周期任务兜底），
     事件本身绝不承载业务事实，所以这里不需要「可靠投递」这回事。
  2. **单进程假设**。订阅者放在进程内 set 里，run_server.py 只起一个
     uvicorn worker，所以可行；将来若拆多 worker，这里要换成 Redis pub/sub。
  3. **尽力而为**。没有订阅者时广播是 no-op；队列满了就丢（不重试不堆积）——
     可靠性由客户端的重连 + 周期同步兜底，服务端不背「必达」的锅。

线程模型：FastAPI 的同步端点跑在 anyio 线程池里，而订阅者的 asyncio.Queue
属于事件循环 —— 所以 offer() 不能直接 put，必须 call_soon_threadsafe 回到
事件循环线程。这是这里唯一一处容易写错的地方。
"""
from __future__ import annotations

import asyncio
import json
import threading

from fastapi.responses import StreamingResponse

KEEP_ALIVE_SEC = 20.0
QUEUE_MAX = 32


class _Subscriber:
    """一条 SSE 连接。

    offer() 允许从**任意线程**调用（同步端点在线程池里跑）；
    stream() 只在事件循环线程里跑。
    """

    def __init__(self, loop: asyncio.AbstractEventLoop):
        self._loop = loop
        self._q: asyncio.Queue[str] = asyncio.Queue(maxsize=QUEUE_MAX)

    def offer(self, frame: str) -> None:
        def _put() -> None:
            if not self._q.full():
                self._q.put_nowait(frame)

        try:
            self._loop.call_soon_threadsafe(_put)
        except RuntimeError:
            pass  # 事件循环已关（进程退出中），连接马上也会断

    async def stream(self):
        try:
            # 先发一条 hello：客户端用它立刻确认「链路通了」，不用等第一笔变更
            yield "data: " + json.dumps({"type": "hello"}, ensure_ascii=False) + "\n\n"
            while True:
                try:
                    frame = await asyncio.wait_for(self._q.get(), timeout=KEEP_ALIVE_SEC)
                    yield frame
                except asyncio.TimeoutError:
                    # 注释帧（以冒号开头，SSE 规范里客户端忽略）：
                    # 既当 keep-alive，也当死连接探测 —— 写失败连接即断、订阅者即清理
                    yield ": keep-alive\n\n"
        finally:
            with _subs_lock:
                _subs.discard(self)


_subs: set[_Subscriber] = set()
_subs_lock = threading.Lock()


def broadcast_assignment_changed(action: str, assignment_id: int) -> None:
    """作业单发生了家长侧的变更。在同步端点里直接调用。

    action 只是给日志/调试看的提示（publish / discard / patch），
    客户端**不按 action 分流处理** —— 一律全量刷新，避免两套事实。
    """
    frame = "data: " + json.dumps(
        {"type": "assignments.changed", "action": action, "id": assignment_id},
        ensure_ascii=False,
    ) + "\n\n"
    with _subs_lock:
        snapshot = list(_subs)
    for sub in snapshot:
        sub.offer(frame)


def sse_response() -> StreamingResponse:
    """构造 SSE 响应并登记订阅者。连接结束（客户端断开/写失败）自动除名。"""
    sub = _Subscriber(asyncio.get_running_loop())
    with _subs_lock:
        _subs.add(sub)
    return StreamingResponse(
        sub.stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
