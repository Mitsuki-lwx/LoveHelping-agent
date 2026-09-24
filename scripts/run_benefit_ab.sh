#!/bin/bash
# ADR-43 受益场景 A/B：同一批查询跑两个臂
#   after  = 当前实现（过滤下推 SQL）
#   before = bd165ce~1（ADR-40 状态：过滤在截断之前，但**未下推**）→ 只差"是否下推"这一个变量
# 用法：bash logs/run_benefit_ab.sh <before|after>
set -u
ARM="${1:-after}"
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-benefit-$ARM-$STAMP.log"
ADMIN_KEY=lwx-admin-eval-2026
PCR="src/main/java/cn/lwx/lwxaiagent/rag/ParentChildDocumentRetriever.java"
PCRT="src/test/java/cn/lwx/lwxaiagent/rag/ParentChildDocumentRetrieverTest.java"

port_open() { $PY -c "
import socket,sys
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',$1)); sys.exit(0)
except Exception: sys.exit(1)
finally: s.close()"; }

kill_port() { P="$1" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['P']], capture_output=True, errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
for pid in {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed %s' % os.environ['P'])"; }

# ---- 臂切换 ----
if [ "$ARM" = "before" ]; then
  cp "$PCR" logs/benefit-PCR.kept && cp "$PCRT" logs/benefit-PCRT.kept
  git show bd165ce~1:"$PCR"  > "$PCR"
  git show bd165ce~1:"$PCRT" > "$PCRT"
  echo "已切到 before 臂（bd165ce~1 = ADR-40 状态，未下推）"
fi

if ! port_open 8300; then
  echo "mcp-server 未起，先拉起（jar）"
  $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8300 > logs/mcp-server.log 2>&1 &
  sleep 18
fi

eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: local yml 缺 SF_API_KEY"; exit 1; }

echo "=== 启动（$ARM 臂，走 yml 默认值）==="
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qE "BUILD FAILURE|cancelling refresh attempt|ERROR\] /D" "$LOG" 2>/dev/null \
    && { echo "启动失败"; grep -aE "ERROR\] /D|Caused by" "$LOG" | tail -8; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -5 "$LOG"; exit 1; }
echo "端口=$PORT 臂=$ARM"

ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/probe_retrieval_benefit.py \
  --base "http://127.0.0.1:$PORT/api" --tag "$ARM" \
  --out "outputs/benefit-$ARM-$STAMP.json" 2>&1 | tail -34

echo "  应用层 ERROR = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"
kill_port "$PORT"

if [ "$ARM" = "before" ]; then
  cp logs/benefit-PCR.kept "$PCR" && cp logs/benefit-PCRT.kept "$PCRT"
  diff logs/benefit-PCR.kept "$PCR" >/dev/null && diff logs/benefit-PCRT.kept "$PCRT" >/dev/null \
    && echo "RESTORE_OK 两份均与备份一致"
fi
echo "DONE_BENEFIT ARM=$ARM STAMP=$STAMP LOG=$LOG"
