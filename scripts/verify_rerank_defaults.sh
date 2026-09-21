#!/bin/bash
# 验证 rerank 默认值改为 enabled=true / mode=remote 之后的两个关键性质：
#
#   Run A（防回归，**最重要**）：**不配 SF_API_KEY** 时应用**仍能启动**。
#          改默认值前，重排器在**构造期**抛错 → 缺 key 的机器整个 context 崩。
#          现在密钥校验挪到调用期，启动必须成功。
#   Run B（默认值生效）：**不设任何 RERANK_* 环境变量**，靠 application.yml 的默认值跑：
#          启动回显须为 enabled=true mode=remote endpoint=https://api.siliconflow.cn/v1/rerank；
#          **聊天链路**（RagAdvisorConfig 那条）要真的出现 `Rerank call ->` 实录；
#          真实 E2E 22/22。
#
# 用法：bash scripts/verify_rerank_defaults.sh [A|B|AB]
RUN="${1:-AB}"
set -u
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
ADMIN_KEY=lwx-admin-eval-2026

kill_port() {
  PORT="$1" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['PORT']],
                     capture_output=True, errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
pids = {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}
for pid in pids: subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed on %s: %s' % (os.environ['PORT'], pids or 'none'))"
}

# 本地重排服务必须关掉，否则无法排除"走的是本地"
echo "=== 关掉 8091 ==="
kill_port 8091

echo "=== 确认 shell 里没有任何 RERANK_* 覆盖（否则测的不是默认值）==="
env | grep -c "^RERANK_" || echo "  0 个 RERANK_* 环境变量 OK"

start_and_wait() { # $1=log 返回端口到全局 PORT
  PORT=""
  for i in $(seq 1 72); do
    PORT=$(grep -o "Tomcat started on port [0-9]*" "$1" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
    [ -n "$PORT" ] && break
    grep -qE "BUILD FAILURE|APPLICATION FAILED TO START|cancelling refresh attempt" "$1" 2>/dev/null \
      && { echo "  ** 启动失败 **"; grep -aE "Caused by|Error creating bean" "$1" | tail -6; PORT=""; break; }
    sleep 5
  done
}

stop_port() {
  P="$1" $PY -c "
import os, subprocess
out=subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['P']],capture_output=True,errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
for pid in {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  app stopped')"
}

# ============================ Run A：缺 key 仍能启动 ============================
if [ "$RUN" = "A" ] || [ "$RUN" = "AB" ]; then
echo
echo "############ Run A：**真正缺 SF_API_KEY** 时能否启动 ############"
LOG_A="logs/app-nokey-$STAMP.log"
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
# ⚠️ 光把 SF_API_KEY 置空**不够**：`target/classes/application-local.yml` 里存了
# `app.siliconflow.api-key` 字面值，而 profile yml **优先于** application.yml 的
# `${SF_API_KEY:}` 占位符 → 置空 env 仍会拿到字面值，测出来是假绿灯。
# 实测踩到：首版这么写，日志里 "SF_API_KEY" 出现 0 次 —— 根本没走到缺 key 分支。
# 用优先级更高的环境变量覆盖该配置项本身（relaxed binding: APP_SILICONFLOW_API_KEY）。
APP_SILICONFLOW_API_KEY='' MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG_A" 2>&1 &
start_and_wait "$LOG_A"
if [ -n "${PORT:-}" ]; then
  echo "  ✅ 启动成功（端口=$PORT）—— 缺 key 不再阻断启动"
  grep -a "Rerank configured" "$LOG_A" | sed 's/.*LocalDocumentReranker *: /  /'
  echo "  日志提到 SF_API_KEY 的次数 = $(grep -ac 'SF_API_KEY' "$LOG_A")"
  echo "  --- 真发一条聊天：重排应降级、对话必须照常成功 ---"
  APP_PORT="$PORT" $PY - <<'PYEOF'
import os, sys, uuid
sys.path.insert(0, "scripts")
from verification_support import register, sse
base = "http://127.0.0.1:%s/api" % os.environ["APP_PORT"]
user, token = register(base)
resp = sse(base, "/Love_app/chat/sse",
           {"prompt": "我们冷战了，怎么开口沟通？", "chatId": "nokey_" + uuid.uuid4().hex}, token)
print("  聊天: success=%s chars=%d errors=%s" % (resp["success"], len(resp["text"]), resp["errors"]))
PYEOF
  echo "  --- 独有指标：rag_rerank_fallback 是否真的涨了（证明'降级发生了'而非'没走这条路'）---"
  $PY -c "
import urllib.request, re
try:
    body = urllib.request.urlopen('http://127.0.0.1:$PORT/api/actuator/prometheus', timeout=20).read().decode()
    hits = [l for l in body.splitlines() if 'rag_rerank' in l and not l.startswith('#')]
    print('  ' + ('\n  '.join(hits) if hits else '（无 rag_rerank_* 指标）'))
except Exception as e:
    print('  取指标失败:', type(e).__name__, str(e)[:120])
"
  stop_port "$PORT"
else
  echo "  ❌ 启动失败 —— 这就是改默认值前会发生的回归"
fi
fi

# ============================ Run B：默认值生效 ============================
if [ "$RUN" = "B" ] || [ "$RUN" = "AB" ]; then
echo
echo "############ Run B：不设任何 RERANK_*，走 yml 默认值 ############"
LOG_B="logs/app-defrerank-$STAMP.log"
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: local yml 里没有 SF_API_KEY，Run B 无法进行"; exit 1; }
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG_B" 2>&1 &
start_and_wait "$LOG_B"
[ -z "${PORT:-}" ] && { echo "未取到端口"; tail -5 "$LOG_B"; exit 1; }
echo "端口=$PORT"
echo "=== 启动回显（须为 enabled=true mode=remote endpoint=https://…siliconflow…）==="
grep -a "Rerank configured" "$LOG_B" | cut -c1-230

echo "=== 聊天链路是否真的走了重排：发一条真实聊天（SSE）==="
# 聊天链路（RagAdvisorConfig 那条）**不经过 admin 端点**，所以"默认开着"只有这里能证明。
# 复用 e2e_live.py 依赖的 verification_support（register / sse），别手写接口契约。
APP_PORT="$PORT" $PY - <<'PYEOF'
import os, sys, uuid
sys.path.insert(0, "scripts")
from verification_support import register, sse

base = "http://127.0.0.1:%s/api" % os.environ["APP_PORT"]
user, token = register(base)
cid = "def_" + uuid.uuid4().hex
resp = sse(base, "/Love_app/chat/sse",
           {"prompt": "我们冷战了，怎么开口沟通？", "chatId": cid}, token)
print("  真实聊天: success=%s ttft=%sms chars=%d errors=%s"
      % (resp["success"], resp["ttft_ms"], len(resp["text"]), resp["errors"]))
PYEOF

echo "=== 重排调用实录（来自聊天链路）==="
grep -aE "Rerank (call|ok)" "$LOG_B" | tail -4 | cut -c1-210
echo "  调用次数 = $(grep -acE 'Rerank call' "$LOG_B")"
echo "  失败/降级 = $(grep -acE 'reranker (unavailable|circuit|saturated)' "$LOG_B")"

echo "=== 评测端点（--rerank 显式开启，与上面同一条引擎）==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py --base "http://127.0.0.1:$PORT/api" --rerank \
  2>&1 | tee "outputs/p8-defaults-rerank-$STAMP.txt" | tail -2

echo "=== 真实 E2E（默认开启重排后的全量回归）==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/e2e_live.py --base "http://127.0.0.1:$PORT/api" 2>&1 | tail -3

echo "=== 应用层 ERROR ==="
grep -acE "^[0-9]{4}-.* ERROR" "$LOG_B"

stop_port "$PORT"
fi

# ==================== Run C：默认开着但重排端点坏掉 —— 运营风险 ====================
# 这是"把默认值改开"真正要担心的场景：重排挂了会不会拖着对话一起挂？
# 用**连接立即被拒**的地址（不是超时），确保测的是"异常→降级"，不掺超时。
if [ "$RUN" = "C" ] || [ "$RUN" = "AB" ]; then
echo
echo "############ Run C：defaults + 重排端点不可达（http://127.0.0.1:9/rerank）############"
LOG_C="logs/app-badrerank-$STAMP.log"
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: 缺 SF_API_KEY，Run C 无法进行"; exit 1; }
# embedding 仍用有效 key（同一条配置项），只把重排指向一个不可达端点
RERANK_URL="http://127.0.0.1:9/rerank" \
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG_C" 2>&1 &
start_and_wait "$LOG_C"
if [ -z "${PORT:-}" ]; then echo "  ❌ 启动失败"; tail -5 "$LOG_C"; exit 1; fi
echo "端口=$PORT（enabled/mode 仍走默认值，只覆盖 RERANK_URL）"

APP_PORT="$PORT" $PY - <<'PYEOF'
import os, sys, uuid
sys.path.insert(0, "scripts")
from verification_support import register, sse
base = "http://127.0.0.1:%s/api" % os.environ["APP_PORT"]
user, token = register(base)
resp = sse(base, "/Love_app/chat/sse",
           {"prompt": "我们冷战了，怎么开口沟通？", "chatId": "badrerank_" + uuid.uuid4().hex}, token)
print("  聊天: success=%s chars=%d errors=%s" % (resp["success"], len(resp["text"]), resp["errors"]))
PYEOF

echo "  --- 独有指标：executions 与 fallback 都应 > 0（证明'重排跑了但降级了'）---"
APP_PORT="$PORT" $PY - <<'PYEOF'
import os, urllib.request
try:
    body = urllib.request.urlopen(
        "http://127.0.0.1:%s/api/actuator/prometheus" % os.environ["APP_PORT"], timeout=20
    ).read().decode()
    rows = [l for l in body.splitlines()
            if l.startswith("rag_rerank") and not l.startswith("#")]
    for r in rows:
        print("  " + r)
    if not rows:
        print("  （没有 rag_rerank_* 指标——重排可能压根没被执行，需另查）")
except Exception as e:
    print("  取指标失败:", type(e).__name__, str(e)[:140])
PYEOF
echo "  应用层 ERROR = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG_C")"
stop_port "$PORT"
fi

echo "DONE_RERANK_DEFAULTS RUN=$RUN STAMP=$STAMP LOG_A=${LOG_A:-n/a} LOG_B=${LOG_B:-n/a} LOG_C=${LOG_C:-n/a}"
