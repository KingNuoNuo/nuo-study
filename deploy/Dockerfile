# nuo_study 服务端 —— 单家庭私有部署
#
# 设计要点：
#  1. 只把「依赖」装进镜像，**代码走卷挂载**。理由：现有部署流程
#     (tools/deploy_nas.py) 就是往 /vol2/1000/software/nuo_study 传代码，
#     挂载能让它原样复用 —— 改完代码只需重启容器，不重建镜像。
#  2. 依赖单独一层（requirements 先于代码挂载），改代码不触发重装。
#  3. 用 slim 而不是 alpine：Pillow 在 alpine 上要现场编译（缺 wheel），
#     slim 直接拿 manylinux wheel，构建只要十几秒。
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Asia/Shanghai

WORKDIR /app

# 英语「跟读评测」要把浏览器录的 webm/opus 转成腾讯智聆要的 16k 单声道 PCM，
# 这一步只能靠 ffmpeg（标准库没有解码器）。不装的话另外三个英语能力仍然可用，
# 只有 /evaluate 会报「服务端缺少 ffmpeg」——但那样融合就没意义了，所以直接装进镜像。
# 源同样换阿里云：NAS 直连 deb.debian.org 很慢，而且偶尔会卡住整个构建。
RUN set -eux; \
    if [ -f /etc/apt/sources.list.d/debian.sources ]; then \
        sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources; \
    elif [ -f /etc/apt/sources.list ]; then \
        sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends ffmpeg; \
    rm -rf /var/lib/apt/lists/*

# 阿里云源：NAS 在国内，直连 pypi.org 慢且偶发超时
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir \
        -i https://mirrors.aliyun.com/pypi/simple/ \
        -r /tmp/requirements.txt

# 代码不 COPY —— 由 compose 挂载进来（见 docker-compose.yml 的 volumes）
CMD ["python", "run_server.py"]
