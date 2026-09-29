set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
STAMP=$(date +%H%M%S); LOG="logs/app-vision-$STAMP.log"
set -a; . ./.env.local >/dev/null 2>&1; set +a
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
export MCP_SERVER_URL="http://localhost:8392/mcp"; export ADMIN_API_KEY=lwx-admin-eval-2026
export APP_SCHEDULER_MASTER_ENABLED=false
port_open() { $PY -c "
import socket,sys
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',$1)); sys.exit(0)
except Exception: sys.exit(1)
finally: s.close()"; }
port_open 8392 || { $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8392 > "logs/mcp-vision-$STAMP.log" 2>&1 & sleep 20; }
$JAVA -classpath "D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=D:\apache-maven-3.9.11\bin\m2.conf" "-Dmaven.home=D:\apache-maven-3.9.11" \
  "-Dmaven.multiModuleProjectDirectory=D:\java\lwx-ai-agent" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &
PORT=""
for i in $(seq 1 84); do
  PORT=$(grep -ao "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break; sleep 5
done
[ -z "$PORT" ] && { echo "NO PORT"; tail -10 "$LOG"; exit 1; }
echo "PORT=$PORT  WINDOW_START=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
$PY scripts/probe_vision_span.py --base "http://127.0.0.1:$PORT/api" 2>&1 | tail -12
# ⛔ 导出器留 flush 窗口：BatchSpanProcessor 有 schedule-delay(1s)/批量，硬杀会丢掉
#    最后一批 span（2026-09-29 实测：vision/check 的 span 就是这样"消失"的，
#    看起来像产品缺陷，其实是**装置自己把证据杀了**）。
echo "  等导出器 flush（6s）…"; sleep 6
P="$PORT" $PY -c "
import os,subprocess
o=subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['P']],capture_output=True,errors='replace').stdout or ''
t=o.decode('utf-8','replace') if isinstance(o,bytes) else o
for pid in {l.split()[-1] for l in t.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid],capture_output=True)
print('killed %s' % os.environ['P'])"
echo "DONE"
