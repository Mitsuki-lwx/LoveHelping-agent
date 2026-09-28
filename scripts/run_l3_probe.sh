#!/bin/bash
# L3 流式失效复现装置（ADR-55）。
#
# ⚠️ 本文件是**入仓副本**（canonical）。logs/ 被 gitignore → 长期不可复现。
# 用法： cd /d/java/lwx-ai-agent && REPEAT=2 bash scripts/run_l3_probe.sh
#
# 一次跑完：起应用 → 打 3 组「不含 L3 词但会把回答引向自伤话题」的输入 → 抓 SSE 全文
# → 统计「日志说拦了几次」vs「用户实际拿到几段含 L3 词的内容」。
#
# ⛔ 判据必须**成对**看：
#   只报「日志 L3 blocked=N」→ 那只能说明 CheckNode 判定了；
#   只报「输出含 L3 词」→ 分不清是没触发还是替换失效。
#   **两者同时为真**才证明「替换对用户无效」。
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
REPEAT=${REPEAT:-2}
STAMP=$(date +%H%M%S)
LOG="logs/app-l3-$STAMP.log"

set -a
# shellcheck disable=SC1091
[ -f .env.local ] && . ./.env.local
set +a
: "${OPENAI_API_KEY:?缺 OPENAI_API_KEY（应在根 .env.local）}"
: "${SF_API_KEY:?缺 SF_API_KEY}"

echo "凭据就绪（值不回显）。LOG=$LOG"

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

MCP_PORT=8392
port_open $MCP_PORT || { echo "拉起 mcp-server :$MCP_PORT"; $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > "logs/mcp-l3-$STAMP.log" 2>&1 & sleep 20; }
export MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp"
export ADMIN_API_KEY="${ADMIN_API_KEY:-lwx-admin-eval-2026}"
export APP_SCHEDULER_MASTER_ENABLED=false

echo "=== 启动 ==="
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &
PORT=""
for i in $(seq 1 84); do
  PORT=$(grep -ao "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qaE "BUILD FAILURE|cancelling refresh attempt|APPLICATION FAILED TO START" "$LOG" 2>/dev/null \
    && { echo "❌ 启动失败"; grep -aE "ERROR\] /D|Caused by" "$LOG" | tail -12; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "❌ 未取到端口"; tail -20 "$LOG"; exit 1; }
echo "端口=$PORT"
grep -aoE "\[scope-wording\].*" "$LOG" | head -1 | sed 's/^/  /'

echo
echo "=== 探针：用户侧实际拿到什么 ==="
$PY scripts/probe_l3_stream.py --base "http://127.0.0.1:$PORT/api" --repeat "$REPEAT" \
  --output "outputs/l3-stream-$STAMP.json" 2>&1

echo
echo "=== 服务端：CheckNode 判定了几次（与上面成对看）==="
BLOCKED=$(grep -ac "Final-reply guardrail L3 blocked" "$LOG")
echo "  Final-reply guardrail L3 blocked（CheckNode 事后替换次数）= $BLOCKED"
grep -a "Final-reply guardrail L3 blocked" "$LOG" | sed 's/^/    /' | head -5
echo "  带上下文 402 = $(grep -acE 'HTTP[ /]?402|status[ =:]+402' "$LOG")   应用层 ERROR = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"

echo
echo "=== 收尾 ==="
kill_port "$PORT"
echo "DONE_L3 STAMP=$STAMP LOG=$LOG"
