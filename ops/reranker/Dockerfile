FROM python:3.13-slim
WORKDIR /app
COPY ops/reranker/requirements.txt .
RUN python -m pip install --no-cache-dir -r requirements.txt
COPY ops/reranker/download_model.py ops/reranker/server.py ./
RUN python download_model.py --directory /app/models
RUN useradd --system --uid 10001 reranker
USER 10001
ENV RERANK_BIND=container
EXPOSE 8091
CMD ["python", "server.py", "--model-dir", "/app/models", "--port", "8091", "--threads", "2"]
