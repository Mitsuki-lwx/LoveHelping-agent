#!/bin/bash
# 生产模拟（docs/phase7-prod-sim）—— 按轮次跑，每轮：起应用(prod profile) → 压测 → 收数 → 停应用
#
# 用法： JEV_KEY=apikey_xxx bash scripts/run_prod_sim.sh <round>
#   round = off      | 真实 Jev，护栏关（基线）
#           shadow   | 真实 Jev，护栏影子（判但不拦）
#           slow     | 假 Jev「慢」（延迟 30s >> 客户端 timeout 3s）
#           hang     | 假 Jev「挂」（永不返回）
#           error    | 假 Jev「立即 500」
#
# 要点：
#   * SPRING_PROFILES_ACTIVE=prod —— tracing 关、日志 INFO。用 local（tracing on + DEBUG）
#     测出来的延迟不是生产延迟。
#   * 凭据运行时从 gitignored 的 local yml 读（scripts/prod_env_from_local.py），仓库里无密钥。
#   * --users == 最大并发，否则测的是每用户突发桶而非全局容量。
set -u
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'

ROUND="${1:-off}"
STAMP=$(date +%H%M%S)
APP_LOG="logs/prodsim-$ROUND-$STAMP.log"
FAKE_LOG="logs/fake-jev-$ROUND-$STAMP.log"
OUT="outputs/prod-sim-$ROUND-$STAMP"

case "$ROUND" in
  off)    MODE=off;    JEVMODE=real;  LEVELS="8,16,24" ;;
  shadow) MODE=shadow; JEVMODE=real;  LEVELS="8,16,24" ;;
  slow)   MODE=shadow; JEVMODE=slow;  LEVELS="8" ;;
  hang)   MODE=shadow; JEVMODE=hang;  LEVELS="8" ;;
  error)  MODE=shadow; JEVMODE=error; LEVELS="8" ;;
  *) echo "未知轮次: $ROUND"; exit 1 ;;
esac
USERS="$($PY -c "print(max(int(x) for x in '$LEVELS'.split(',')))")"
FAKE_PORT=19090

# ---------- 凭据（不落仓库） ----------
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
if [ -z "${OPENAI_API_KEY:-}" ]; then echo "凭据提取失败：OPENAI_API_KEY 为空"; exit 1; fi

# 按端口杀进程：Git Bash 的 $! 是 **MSYS PID**，taskkill /PID 杀不动它
# （2026-09-20 实测：slow/hang/error 三轮的假 Jev 统计全是 by_mode={'slow':...}，
#  因为后两轮的进程根本没绑上端口，一直是第一轮那个在服务）。
kill_port() {
  PORT="$1" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['PORT']],
                     capture_output=True, errors='replace').stdout or ''
pids = {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}
for pid in pids:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('killed on port %s: %s' % (os.environ['PORT'], pids or 'none'))
"
}

# ---------- 假 Jev（仅故障轮） ----------
FAKE_PID=""
if [ "$JEVMODE" != "real" ]; then
  kill_port "$FAKE_PORT" >/dev/null 2>&1   # 清掉上一轮的残留，确保本轮真的是本模式在服务
  FAKE_JEV_MODE="$JEVMODE" FAKE_JEV_DELAY=30 FAKE_JEV_PORT="$FAKE_PORT" \
    $PY scripts/fake_jev.py > "$FAKE_LOG" 2>&1 &
  FAKE_PID=$!
  sleep 1
  JEV_BASE_URL="http://127.0.0.1:$FAKE_PORT"
  sleep 1
  # 断言"确实是本模式在服务"：否则端口被残留进程占着时，整轮测得的是别的模式
  $PY -c "
import json, urllib.request, sys
s = json.loads(urllib.request.urlopen('http://127.0.0.1:$FAKE_PORT/stats', timeout=5).read())
print('假 Jev 就绪: mode=$JEVMODE stats=%s' % s)
" || { echo "假 Jev 未就绪（端口可能被占）"; exit 1; }
else
  JEV_BASE_URL="https://api.typesafe.ai"
fi

stop_all() {
  if [ -n "${APP_PORT:-}" ]; then
    APP_PORT="$APP_PORT" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['APP_PORT']], capture_output=True, errors='replace').stdout or ''
for pid in {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
" 2>/dev/null
  fi
  [ -n "$FAKE_PID" ] && kill_port "$FAKE_PORT" >/dev/null 2>&1
  return 0
}

# ---------- 起应用（prod profile） ----------
echo "=== 启动（prod profile, guardrail.mode=$MODE, jev=${JEVMODE}） ==="
SPRING_PROFILES_ACTIVE=prod \
JEV_ENABLED=true JEV_API_KEY="${JEV_KEY:-}" JEV_GUARDRAIL_MODE="$MODE" JEV_BASE_URL="$JEV_BASE_URL" \
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY=lwx-admin-eval-2026 \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$APP_LOG" 2>&1 &

APP_PORT=""
for i in $(seq 1 72); do
  APP_PORT=$(grep -o "Tomcat started on port [0-9]*" "$APP_LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$APP_PORT" ] && break
  # 启动失败要早退：Spring 的 "APPLICATION FAILED TO START" 只在装了 FailureAnalyzer 时打，
  # 直接被 Bean 异常打断时只有 "cancelling refresh attempt" —— 只认前者会白等满 6 分钟。
  if grep -qE "BUILD FAILURE|APPLICATION FAILED TO START|cancelling refresh attempt|Error creating bean" "$APP_LOG" 2>/dev/null; then
    echo "启动失败"; grep -aE "ERROR|Caused by" "$APP_LOG" | tail -12; stop_all; exit 1
  fi
  sleep 5
done
if [ -z "$APP_PORT" ]; then echo "未取到端口"; tail -20 "$APP_LOG"; stop_all; exit 1; fi
echo "端口=$APP_PORT"
echo "profile 确认: $(grep -o 'The following 1 profile is active: "[a-z]*"' "$APP_LOG" | head -1)"
echo "tracing: $(grep -a 'management.tracing\|tracing.enabled' "$APP_LOG" | head -1)"

# ---------- 压测 ----------
if [ "$JEVMODE" != "real" ]; then
  $PY -c "
import urllib.request
print('reset:', urllib.request.urlopen('http://127.0.0.1:$FAKE_PORT/reset', timeout=5).read().decode()[:120])
"
fi

echo "=== 压测（endpoint=sse levels=$LEVELS users=$USERS） ==="
ADMIN_API_KEY=lwx-admin-eval-2026 $PY scripts/loadtest.py \
  --base "http://127.0.0.1:$APP_PORT/api" --endpoint sse \
  --users "$USERS" --levels "$LEVELS" --warmup 1 \
  2>&1 | tee "$OUT.loadtest.txt"

if [ "$JEVMODE" != "real" ]; then
  $PY -c "
import json, urllib.request
s = json.loads(urllib.request.urlopen('http://127.0.0.1:$FAKE_PORT/stats', timeout=5).read())
open('$OUT.fakejev.json','w',encoding='utf-8').write(json.dumps(s, ensure_ascii=False, indent=2))
print('假 Jev 上游侧统计:', s)
"
fi

# ---------- 应用侧痕迹 ----------
JEV_FIRE=$(grep -ac "jev second signal" "$APP_LOG" 2>/dev/null || echo 0)
JEV_WARN=$(grep -ac "jev" "$APP_LOG" 2>/dev/null || echo 0)
ERR=$(grep -ac "ERROR" "$APP_LOG" 2>/dev/null || echo 0)
echo "应用日志：jev 相关行=$JEV_WARN  含 'jev second signal'=$JEV_FIRE  ERROR 行=$ERR"

stop_all
echo "DONE_PRODSIM_$ROUND STAMP=$STAMP OUT=$OUT APP_LOG=$APP_LOG"
