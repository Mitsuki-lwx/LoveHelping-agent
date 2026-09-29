#!/bin/bash
# phase25 B2/B3 判据：cline.bot 网关当 primary，真实链路失眠 SSE 探针。
# 只换 OPENAI_* 三个 env，不碰 yml。凭据走 ./.env.local（gitignored）。
# 用法：cd /d/java/lwx-ai-agent && REPEAT=2 bash scripts/probe_cline_primary.sh
# 沙箱会回收后台进程 → 启动/测试/收尾必须在同一条命令内串完。
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
REPEAT=${REPEAT:-2}
STAMP=$(date +%H%M%S)
LOG="logs/app-cline-primary-$STAMP.log"
set -a
# shellcheck disable=SC1091
. ./.env.local >/dev/null 2>&1
set +a
: "${CLINE_API_KEY:?缺 CLINE_API_KEY（应在 ./.env.local）}"
export OPENAI_API_KEY="$CLINE_API_KEY"
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
# ADR-58：provider 由配置列表驱动，用 env 覆盖列表元素（Spring relaxed binding）。
# base 不含 /v1（completionsPath 默认 /v1/chat/completions）；非流式响应带 {data:...} 信封。
export APP_LLM_PROVIDERS_0_NAME=primary
export APP_LLM_PROVIDERS_0_BASE_URL="https://api.cline.bot/api"
export APP_LLM_PROVIDERS_0_API_KEY="$CLINE_API_KEY"
export APP_LLM_PROVIDERS_0_MODEL="deepseek/deepseek-v4.1-flash"
export APP_LLM_PROVIDERS_0_RESPONSE_ENVELOPE=data
export OPENAI_API_KEY="$CLINE_API_KEY"
: "${SF_API_KEY:?缺 SF_API_KEY}"
export MCP_SERVER_URL="http://localhost:8392/mcp"
export ADMIN_API_KEY=lwx-admin-eval-2026
export APP_SCHEDULER_MASTER_ENABLED=false
# 放宽超时预算（cline 网关首字节偏慢；空窗超时已做成可配）
export APP_LLM_FIRST_BYTE_TIMEOUT_MS="${PROBE_FIRST_BYTE_MS:-60000}"
export APP_LLM_STREAM_IDLE_TIMEOUT_MS="${PROBE_STREAM_IDLE_MS:-60000}"
export APP_LLM_CONNECT_TIMEOUT_MS="${PROBE_CONNECT_MS:-10000}"
echo "OPENAI_BASE_URL=$OPENAI_BASE_URL OPENAI_MODEL=$OPENAI_MODEL KEY_LEN=${#OPENAI_API_KEY}"
port_open() { $PY -c "
import socket,sys
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',$1)); sys.exit(0)
except Exception: sys.exit(1)
finally: s.close()"; }
MCP_PORT=8392
port_open $MCP_PORT || { echo "拉起 mcp-server :$MCP_PORT"; $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > "logs/mcp-cline-primary-$STAMP.log" 2>&1 & sleep 20; }
export MCP_SERVER_URL="http://localhost:8392/mcp"
$JAVA -classpath "D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=D:\apache-maven-3.9.11\bin\m2.conf" \
  "-Dmaven.home=D:\apache-maven-3.9.11" \
  "-Dmaven.multiModuleProjectDirectory=D:\java\lwx-ai-agent" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &
PORT=""
for i in $(seq 1 84); do
  PORT=$(grep -ao "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qaE "BUILD FAILURE|cancelling refresh attempt|APPLICATION FAILED TO START" "$LOG" 2>/dev/null \
    && { echo "START FAIL"; grep -aE "Caused by" "$LOG" | tail -5; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "NO PORT"; tail -10 "$LOG"; exit 1; }
echo "PORT=$PORT STAMP=$STAMP"
grep -aoE "primary 级实际生效端点：[^\"']*" "$LOG" | head -1
$PY scripts/probe_answer_stability.py --base "http://127.0.0.1:$PORT/api" --repeat "$REPEAT" \
  --question "我最近总是失眠，怎么调整？" --output "outputs/cline-primary-$STAMP.json" 2>&1 | tail -25
echo "=== 非流式 call() 路径（信封判据）==="
$PY scripts/probe_cline_nonstream.py --base "http://127.0.0.1:$PORT/api" \
  --output "outputs/cline-nonstream-$STAMP.json" 2>&1 | tail -25
P="$PORT" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['P']], capture_output=True, errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
for pid in {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('killed %s' % os.environ['P'])"
echo "DONE STAMP=$STAMP LOG=$LOG"
