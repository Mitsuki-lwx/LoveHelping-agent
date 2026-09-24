#!/bin/bash
# 洞察路径 trace 碎片：受控实验（当前代码 + Langfuse 开启）
#
# 为什么要分三段而不是只跑一次洞察：碎片是"根 span 被丢、子 span 变孤儿"的产物，
# 而**丢根**的判定逻辑只对 /actuator 流量生效。所以必须分辨：
#   ① 空闲基线（定时任务等）      ② 只抓 /actuator/prometheus（模拟 Prometheus 每 15s 抓）
#   ③ 只调一次 /insight/analyze
# 三段各自前后快照，才能把"碎片归谁"钉死。
#
# 用法：bash scripts/run_trace_fragments.sh
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-tracefrag-$STAMP.log"
LF_HOST=http://localhost:3000
DRIVER="${DRIVER:-trace_fragments_experiment.py}"

# 凭据：优先环境变量；否则从既有（gitignored）脚本里取，不落到本文件里
if [ -z "${LANGFUSE_PUBLIC_KEY:-}" ] && [ -f logs/run_insight_only.sh ]; then
  LANGFUSE_PUBLIC_KEY=$(grep -oP 'LF_PK=\K\S+' logs/run_insight_only.sh)
  LANGFUSE_SECRET_KEY=$(grep -oP 'LF_SK=\K\S+' logs/run_insight_only.sh)
fi
[ -n "${LANGFUSE_PUBLIC_KEY:-}" ] || { echo "缺 LANGFUSE_PUBLIC_KEY"; exit 1; }
export LANGFUSE_PUBLIC_KEY LANGFUSE_SECRET_KEY

kill_port() { P="$1" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['P']], capture_output=True, errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
for pid in {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed %s' % os.environ['P'])"; }

if ! $PY -c "
import socket,sys
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',8300)); sys.exit(0)
except Exception: sys.exit(1)
finally: s.close()"; then
  echo "mcp-server 未起，先拉起"
  $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8300 > logs/mcp-server.log 2>&1 &
  sleep 18
fi

eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: local yml 缺 SF_API_KEY"; exit 1; }

echo "=== 启动（Langfuse 开启）==="
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY=lwx-admin-eval-2026 \
LANGFUSE_ENABLED=true LANGFUSE_PUBLIC_KEY="$LANGFUSE_PUBLIC_KEY" \
LANGFUSE_DROP_SECURITY_SPANS="${LANGFUSE_DROP_SECURITY_SPANS:-true}" \
LANGFUSE_SECRET_KEY="$LANGFUSE_SECRET_KEY" LANGFUSE_HOST="$LF_HOST" \
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
echo "端口=$PORT"
grep -aE "Langfuse|langfuse" "$LOG" | head -3 | cut -c1-160

echo
echo "=== 受控实验（driver=$DRIVER）==="
APP_PORT="$PORT" LF_HOST="$LF_HOST" STAMP="$STAMP" $PY "scripts/$DRIVER"

echo
echo "  应用层 ERROR = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"
kill_port "$PORT"
echo "DONE_TRACEFRAG STAMP=$STAMP LOG=$LOG"
