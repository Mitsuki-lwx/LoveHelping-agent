#!/usr/bin/env bash
# ============================================================
# 服务器端部署脚本（配合 .github/workflows/release.yml）
# 服务器预置：/opt/lovehelping/ 下有 docker-compose.yml 与 .env
# 用法：TAG=v1.0.0 ./deploy.sh   （缺省 latest）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${TAG:-latest}"
export TAG

echo "==> 拉取镜像 tag=$TAG"
docker compose pull

echo "==> 滚动升级"
docker compose up -d

echo "==> 清理悬空镜像"
docker image prune -f

echo "==> 等待应用健康（最长 300s，首启向量化较久）"
for i in $(seq 1 75); do
  if curl -sf -m 3 http://localhost:8088/api/actuator/health | grep -q '"status":"UP"'; then
    echo "==> 健康检查通过 ✅"
    exit 0
  fi
  sleep 4
done
echo "==> 健康检查超时——查看日志: docker compose logs app" >&2
exit 1
