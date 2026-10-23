#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""服务端启动入口。

一个进程里同时跑：
  · HTTP 接口（uvicorn）
  · 识别队列消费线程（默认 1 个）

单家庭私有部署不需要拆进程。若将来识别压力大，用 `--no-worker` 起纯接口进程，
再把 worker 单独起一份指向同一个 SQLite（WAL 模式支持一写多读，队列用 BEGIN IMMEDIATE 抢任务）。

用法：
  python run_server.py                          # 默认 0.0.0.0:8080
  python run_server.py --host 127.0.0.1 --port 8099
  python run_server.py --no-worker              # 只起接口，不消费队列
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import uvicorn  # noqa: E402

from server import config, db  # noqa: E402
from server.api import app  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=config.env("SERVER_HOST", "0.0.0.0"))
    ap.add_argument("--port", type=int, default=int(config.env("SERVER_PORT", "8080")))
    ap.add_argument("--no-worker", action="store_true", help="只起 HTTP，不消费识别队列")
    ap.add_argument("--log-level", default="info")
    args = ap.parse_args()

    path = db.init_db()
    print("[boot] 数据库: %s (schema v%d)" % (path, db.db_version(db.connect())))
    print("[boot] 图片目录: %s" % config.IMAGE_DIR)
    print("[boot] 模型: %s @ %s / prompt=%s / think=%s"
          % (config.VISION_MODEL, config.VISION_BASE_URL,
             config.PROMPT_VERSION, config.VISION_THINKING))
    if config.FAMILY_TOKEN.startswith("dev-"):
        print("[boot] ⚠️ 正在使用开发默认 FAMILY_TOKEN，正式部署请在 .env 里改掉")

    worker = None
    if not args.no_worker:
        from server.worker import RecognitionWorker

        worker = RecognitionWorker()
        worker.start()

    print("[boot] HTTP 监听 %s:%d  文档 /docs" % (args.host, args.port))
    try:
        # ⚠️ timeout_graceful_shutdown 不能省（2026-10-10 容器化时才暴露）。
        #    家长端会一直持着一条 SSE 长连接（/api/v1/events），而 events.py 里那个
        #    生成器是 `while True`（靠 keep-alive 帧维持），**永远不会自己结束**。
        #    uvicorn 默认无限等现有连接收尾 → 收到 SIGTERM 后卡在
        #    `Waiting for connections to close`：进程不退、也不再对外服务
        #    （实测：docker 里 ping 直接变 000，而容器状态还是 running）。
        #    给了上限后最多宽限 5 秒就强制收尾；它比 docker 默认的 10s stop
        #    timeout 短，所以容器能自己干净退出，而不是挨一记 SIGKILL。
        uvicorn.run(app, host=args.host, port=args.port, log_level=args.log_level,
                    timeout_graceful_shutdown=5)
    finally:
        if worker:
            worker.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
