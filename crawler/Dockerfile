# gptr-pycrawler：自包含无状态爬虫服务（自 gpt-researcher 提取精简，见 NOTICE）
# 基础镜像与 pip 源均 ARG 化：默认官方源（发布友好）；中国大陆网络可覆盖：
#   docker compose build --build-arg BASE_IMAGE=docker.m.daocloud.io/library/python:3.11-slim \
#                        --build-arg PIP_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple crawler
ARG BASE_IMAGE=python:3.11-slim
FROM ${BASE_IMAGE}

WORKDIR /app

ARG PIP_INDEX=https://pypi.org/simple
COPY requirements.txt .
RUN pip install --no-cache-dir -i ${PIP_INDEX} -r requirements.txt

COPY pycrawler/ ./pycrawler/

ENV PYTHONPATH=/app
EXPOSE 8000

# 单实例 uvicorn（无状态服务，随时可重启；Java worker 负责重试接管）
CMD ["uvicorn", "pycrawler.api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
