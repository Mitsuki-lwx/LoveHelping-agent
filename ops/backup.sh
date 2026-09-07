#!/usr/bin/env bash
# ============================================================
# 3-2-1 备份（2026-09-07 课4）：每日 dump MySQL + PostgreSQL
# - 面向 docker compose 部署（生产）：经容器内 mysqldump/pg_dump，单文件可传输
# - 3 份副本/2 介质/1 异地：本脚本产出"本机副本"，异地副本靠对象存储/rsync 同步本目录
# - 保留策略：7 日轮转（.sql 保留 7 天）
# 用法：cd /opt/lovehelping && set -a && source .env && set +a && ./ops/backup.sh
# 定时（crontab 示例，每日 03:30）：
#   30 3 * * * cd /opt/lovehelping && set -a && source .env && set +a && bash ops/backup.sh >> /var/log/lovehelping-backup.log 2>&1
# ============================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"

BACKUP_DIR="${BACKUP_DIR:-$DIR/backups}"
KEEP_DAYS="${KEEP_DAYS:-7}"
STAMP="$(date +%F-%H%M%S)"
OK=1

mkdir -p "$BACKUP_DIR"
echo "==> $(date '+%F %T') 备份开始 → $BACKUP_DIR"

# ---- MySQL（agentdb 业务库：用户/记忆/审计/会话） ----
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
if docker compose exec -T mysql mysqladmin ping -uroot -p"$MYSQL_PASSWORD" -h 127.0.0.1 >/dev/null 2>&1; then
  docker compose exec -T mysql mysqldump -uroot -p"$MYSQL_PASSWORD" \
    --single-transaction --quick --routines --triggers agentdb \
    > "$BACKUP_DIR/mysql-agentdb-$STAMP.sql" || OK=0
  echo "  mysql: $BACKUP_DIR/mysql-agentdb-$STAMP.sql ($(wc -c < "$BACKUP_DIR/mysql-agentdb-$STAMP.sql") bytes)"
else
  echo "  !! mysql 不可达，跳过" >&2; OK=0
fi

# ---- PostgreSQL（pgvector 向量库：RAG 知识库。启动可自举重建，但仍备份） ----
PGVECTOR_PASSWORD="${PGVECTOR_PASSWORD:-}"
if docker compose exec -T postgres pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1; then
  PGPASSWORD="$PGVECTOR_PASSWORD" docker compose exec -T postgres \
    pg_dump -U postgres -d postgres -Fc \
    > "$BACKUP_DIR/pg-postgres-$STAMP.dump" || OK=0
  echo "  postgres: $BACKUP_DIR/pg-postgres-$STAMP.dump ($(wc -c < "$BACKUP_DIR/pg-postgres-$STAMP.dump") bytes)"
else
  echo "  !! postgres 不可达，跳过" >&2; OK=0
fi

# ---- 轮转：保留 N 日 ----
find "$BACKUP_DIR" -type f \( -name '*.sql' -o -name '*.dump' \) -mtime +"$KEEP_DAYS" -delete 2>/dev/null || true
echo "==> 轮转完成（保留 ${KEEP_DAYS} 天）"

# ---- 异地副本提示（3-2-1 的"1 份异地"） ----
echo "==> 提醒：请将 backups/ 同步到对象存储/异地（如 rclone sync backups/ remote:lovehelping-backups/）"
[ "$OK" = 1 ] && echo "==> 备份完成 ✅" || { echo "==> 备份部分失败，请检查" >&2; exit 1; }
