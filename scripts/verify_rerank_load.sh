#!/bin/bash
# 量「rerank 默认开启」的运营代价：TTFT 分布、并发上限、以及 maxConcurrent=2 会不会造成降级。
#
# 为什么必须测（而不是"应该没问题"）：
#   默认打开后，**每次检索**都多一次外部调用（热态 ~0.4s）。而重排器是
#   `maxConcurrent=2` + 熔断阈值 3 + 15s 熔断窗口 —— 全是**进程内**状态。
#   并发一高，`permits.tryAcquire()` 会失败 → 抛 "saturated" → postprocessor 捕获 →
#   **静默降级为原顺序**（用户看不到错，但重排白开了）。这正是需要量出来的东西。
#
# 用法：bash scripts/verify_rerank_load.sh [levels] [prompt]
#   默认 levels=8,16,24,32（24 是厂商硬上限，32 用来看超限形态）
set -u
cd /d/java/lwx-ai-agent
LEVELS="${1:-8,16,24,32}"
PROMPT="${2:-我们冷战了，该怎么开口沟通？}"

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:/apache-maven-3.9.11/boot/plexus-classworlds-2.9.0.jar'
CW='D:/apache-maven-3.9.11/bin/m2.conf'
MVN='D:/apache-maven-3.9.11'
MPD='D:/java/lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-rerank-load-$STAMP.log"
OUT="outputs/rerank-load-$STAMP"
ADMIN_KEY=lwx-admin-eval-2026
USERS="$($PY -c "print(max(int(x) for x in '$LEVELS'.split(',')))")"

kill_port() {
  P="$1" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['P']],
                     capture_output=True, errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
pids = {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}
for pid in pids: subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed on %s: %s' % (os.environ['P'], pids or 'none'))"
}
metrics() { # $1=port $2=标签
  P="$1" TAG="$2" $PY -c "
import os, urllib.request
try:
    body = urllib.request.urlopen('http://127.0.0.1:%s/api/actuator/prometheus' % os.environ['P'], timeout=20).read().decode()
    rows = [l for l in body.splitlines() if l.startswith('rag_rerank') and not l.startswith('#')]
    print('  [%s]' % os.environ['TAG'])
    for r in sorted(rows): print('    ' + r)
    if not rows: print('    （无 rag_rerank_* —— 重排没被执行过）')
except Exception as e:
    print('  [%s] 取指标失败: %s %s' % (os.environ['TAG'], type(e).__name__, str(e)[:100]))
"
}

echo "=== 关掉本地 8091（确保只有远端一条路）==="
kill_port 8091

eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: local yml 缺 SF_API_KEY"; exit 1; }

echo "=== 启动（全走 yml 默认值：rerank enabled=true / mode=remote）==="
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qE "BUILD FAILURE|cancelling application|cancelling refresh attempt" "$LOG" 2>/dev/null \
    && { echo "启动失败"; grep -aE "Caused by" "$LOG" | tail -6; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -5 "$LOG"; exit 1; }
echo "端口=$PORT 并发档=$LEVELS 独立用户=$USERS"
grep -a "Rerank configured" "$LOG" | sed 's/.*LocalDocumentReranker *: /  /'

echo
echo "=== ① 前提断言：这条 prompt 必须真的走检索 + 重排（否则后面的延迟数无意义）==="
APP_PORT="$PORT" PROMPT="$PROMPT" $PY - <<'PYEOF'
import os, sys, uuid
sys.path.insert(0, "scripts")
from verification_support import register, sse
base = "http://127.0.0.1:%s/api" % os.environ["APP_PORT"]
user, token = register(base)
resp = sse(base, "/Love_app/chat/sse",
           {"prompt": os.environ["PROMPT"], "chatId": "loadpre_" + uuid.uuid4().hex}, token)
print("  预热聊天: success=%s ttft=%sms chars=%d" % (resp["success"], resp["ttft_ms"], len(resp["text"])))
PYEOF
echo "  日志证据：RAG_RETRIEVAL 出现 $(grep -ac 'RAG_RETRIEVAL' "$LOG") 次；Rerank call 出现 $(grep -ac 'Rerank call' "$LOG") 次"
if [ "$(grep -ac 'Rerank call' "$LOG")" -eq 0 ]; then
  echo "  ❌ 这条 prompt 没触发重排 —— 压测结果无意义，先换 prompt"
  kill_port "$PORT"; exit 1
fi
echo "  冷启动首调用耗时（Rerank ok 第一条）："
grep -a "Rerank ok" "$LOG" | head -1 | grep -oE "in [0-9]+ ms" || echo "    （未取到）"

metrics "$PORT" "压测前"

echo
echo "=== ② 真实 SSE 压测（endpoint=sse, warmup=1 不计入）==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/loadtest.py \
  --base "http://127.0.0.1:$PORT/api" --endpoint sse \
  --users "$USERS" --levels "$LEVELS" --warmup 1 --prompt "$PROMPT" \
  2>&1 | tee "$OUT.loadtest.txt" | tail -20

metrics "$PORT" "压测后"

echo
echo "=== ③ 重排被限流的痕迹（saturated / circuit）==="
echo "  saturated: $(grep -ac 'saturated' "$LOG")"
echo "  circuit open: $(grep -ac 'circuit open' "$LOG")"
echo "  unavailable: $(grep -ac 'unavailable' "$LOG")"
echo "  重排 DEBUG 调用数: $(grep -ac 'Rerank call' "$LOG")"
echo "=== ④ 应用层 ERROR / 5xx ==="
echo "  应用层 ERROR = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"

kill_port "$PORT"
echo "DONE_RERANK_LOAD STAMP=$STAMP LOG=$LOG OUT=$OUT"
