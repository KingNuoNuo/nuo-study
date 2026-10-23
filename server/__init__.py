"""识别 Worker 服务端包。

单家庭私有部署：一个进程同时承担 HTTP 接口、识别队列消费、家长确认流。
不引入 Redis / Celery / 消息队列 —— SQLite 表本身就是队列（见 queue.py）。
"""
