#!/bin/bash
# ADR-31 任务 3：触发并验证反思任务的"容量让路"分支。
#   gate=1 + wait-ms=0 + yield-to-online=false → 反思撞上网关 4003 → WARN 单行（不打 ERROR 全栈）
#
# ⚠️ 本脚本原在 `logs/`（被 gitignore，无法复现），2026-09-19 迁到 `scripts/`。
#    实测记录见 docs/09-测试策略.md §8.11。
set -u
cd /d/java/lwx-ai-agent

# 密钥一律走环境变量，**不得硬编码**（本仓库既有惯例，见 scripts/soak.py 用法）。
ADMIN_API_KEY="${ADMIN_API_KEY:?需要设置 ADMIN_API_KEY 环境变量，例如 ADMIN_API_KEY=xxx bash scripts/run_reflection_defer.sh}"
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
FAKE_PORT=19080
MCP_PORT=8300
APP_LOG=logs/app-reflect-defer.log

echo "=== 0. mcp-server ($MCP_PORT) ==="
if $PY -c "
import socket,sys
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try: s.bind(('127.0.0.1', $MCP_PORT))
except OSError: sys.exit(1)
finally: s.close()
" 2>/dev/null; then
  $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > logs/mcp-server.log 2>&1 &
  sleep 18; echo "mcp-server 已启动"
else
  echo "$MCP_PORT 已有服务（复用）"
fi

echo "=== 0b. 假上游（hang：requests 永不返回，占位用）==="
if $PY -c "
import socket,sys
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try: s.bind(('127.0.0.1', $FAKE_PORT))
except OSError: sys.exit(1)
finally: s.close()
" 2>/dev/null; then
  FAKE_MODE=hang FAKE_PORT=$FAKE_PORT $PY scripts/fake_llm.py > logs/fake_llm.log 2>&1 &
  sleep 3; curl -s -m 5 "http://127.0.0.1:$FAKE_PORT/stats"; echo
else
  echo "$FAKE_PORT 已有服务（复用）"
fi

echo "=== 1. 启动应用（gate=1, wait-ms=0, yield-to-online=false, 反思参数调小）==="
JVM_ARGS="-Dspring.ai.openai.base-url=http://127.0.0.1:$FAKE_PORT -Dapp.online.max-inflight=1 -Dapp.online.wait-ms=0 -Dapp.llm.max-concurrent-calls=1 -Dapp.llm.adaptive.enabled=false -Dapp.llm.circuit.enabled=false -Dapp.llm.attempt-timeout-ms=180000 -Dapp.llm.total-timeout-ms=180000 -Dapp.llm.stream-idle-timeout-ms=180000 -Dapp.scheduler.yield-to-online=false -Devolution.extract-delay-seconds=30 -Devolution.quality-threshold=101 -Dapp.scheduler.reflect.fixed-delay-ms=15000"
MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp" ADMIN_API_KEY="$ADMIN_API_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o \
  -Dspring-boot.run.jvmArguments="$JVM_ARGS" spring-boot:run > "$APP_LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$APP_LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  if grep -q "BUILD FAILURE\|APPLICATION FAILED TO START" "$APP_LOG" 2>/dev/null; then
    echo "启动失败："; $PY -c "
import io
t=io.open('$APP_LOG',encoding='utf-8',errors='replace').read().splitlines()
ls=[l for l in t if 'Progress' not in l and l.strip()]
print('\n'.join(l[:170] for l in ls[-18:]))
"; exit 1
  fi
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; exit 1; }
echo "端口=$PORT"
echo "容量: $(grep -o 'capacity:.*' "$APP_LOG" | tail -1 | cut -c1-160)"

echo "=== 2. 造会话 + 占位 + 观察反思 ==="
BASE="http://127.0.0.1:$PORT/api" APP_LOG="$APP_LOG" $PY scripts/probe_reflection_deferral.py

echo "=== 3. 日志中的相关行 ==="
grep -oE "Reflection scan: [0-9]+ session\(s\) ready.*|Reflection for session [^ ]+ deferred: LLM gateway at capacity|Failed to reflect session [^ ]+|No valid skills reflected from session [^ ]+" "$APP_LOG" | sort | uniq -c | sort -rn | head -10

echo "=== 4. 收尾 ==="
export APP_PORT="$PORT"
$PY -c "
import os, subprocess
port = os.environ['APP_PORT']
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+port], capture_output=True, errors='replace').stdout or ''
for pid in {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('app stopped')
"
$PY -c "
import subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :$FAKE_PORT'], capture_output=True, errors='replace').stdout or ''
for pid in {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('fake upstream stopped')
"
echo DONE_DEFERRAL
