#!/bin/bash
# phase13 验证：起应用 → 跑 N 轮真实检索评测 → 关应用。
# 用法：bash scripts/verify_phase13.sh [轮数] [标签]
#   标签用于产物命名（before / after），方便 A/B 对照。
set -u
ROUNDS="${1:-3}"
TAG="${2:-after}"
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-p13-$TAG-$STAMP.log"
ADMIN_KEY=lwx-admin-eval-2026

port_open() {
  $PY -c "
import socket,sys
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',$1)); sys.exit(0)
except Exception: sys.exit(1)
finally: s.close()"
}

kill_port() {
  P="$1" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['P']],
                     capture_output=True, errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
for pid in {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed on %s' % os.environ['P'])"
}

# 主 app 强依赖 mcp-server（缺了整个 context 崩，不是降级）
if ! port_open 8300; then
  echo "mcp-server 未起，先拉起（jar）"
  $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8300 > logs/mcp-server.log 2>&1 &
  sleep 18
fi

eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: local yml 缺 SF_API_KEY"; exit 1; }

echo "=== 启动（走 yml 默认值）==="
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qE "BUILD FAILURE|cancelling refresh attempt|Error creating bean" "$LOG" 2>/dev/null \
    && { echo "启动失败"; grep -aE "Caused by" "$LOG" | tail -6; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -5 "$LOG"; exit 1; }
echo "端口=$PORT 标签=$TAG 轮数=$ROUNDS"
grep -a "Rerank configured" "$LOG" | sed 's/.*LocalDocumentReranker *: /  /'

echo
echo "=== 每轮：真实检索（45 例，纯检索口径 = 不带 --rerank）==="
for r in $(seq 1 "$ROUNDS"); do
  echo "--- 第 $r 轮 ---"
  ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py \
    --base "http://127.0.0.1:$PORT/api" 2>&1 | tee "outputs/p13-$TAG-r$r.txt" | tail -3
done

echo
echo "=== 链路自检：候选里不得出现记忆块（file=? 是记忆块的标志）==="
echo "  RAG_RETRIEVAL 行数 = $(grep -ac 'RAG_RETRIEVAL' "$LOG")"
echo "  带 file=? 的候选 = $(grep -ao 'file=?' "$LOG" | wc -l)"
echo "  应用层 ERROR = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"

kill_port "$PORT"
echo "DONE_P13 TAG=$TAG STAMP=$STAMP LOG=$LOG"
