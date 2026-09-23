#!/bin/bash
# phase10 复测：源过滤先于截断之后，候选是否恢复、重排是否终于在聊天链路生效、
#           以及「上一轮 MRR 0.751 vs 0.807」的归因到底成不成立。
#
# 关键设计：**同一个 app 实例**同时给出两个口径，避免跨进程差异 ——
#   - `retrieval_eval`            → admin 端点 rerank=false → **不走** postprocessor = 纯检索基线
#   - `retrieval_eval --rerank`   → 走 postprocessor = 重排后
# （重排开启时检索会扩窗到 topN，这正是"宽召回 + 精排"的设计形状。）
#
# 用法：bash scripts/verify_phase10.sh
set -u
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-phase10-$STAMP.log"
ADMIN_KEY=lwx-admin-eval-2026

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

kill_port 8091
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: 缺 SF_API_KEY"; exit 1; }

MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qE "BUILD FAILURE|cancelling refresh attempt" "$LOG" 2>/dev/null \
    && { echo "启动失败"; grep -aE "Caused by" "$LOG" | tail -6; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -5 "$LOG"; exit 1; }
echo "端口=$PORT"
grep -a "Rerank configured" "$LOG" | sed 's/.*LocalDocumentReranker *: /  /'

echo
echo "=== ① 聊天链路：候选是否恢复 + 重排是否终于生效 ==="
APP_PORT="$PORT" $PY - <<'PYEOF'
import os, sys, uuid
sys.path.insert(0, "scripts")
from verification_support import register, sse
base = "http://127.0.0.1:%s/api" % os.environ["APP_PORT"]
user, token = register(base)
resp = sse(base, "/Love_app/chat/sse",
           {"prompt": "我们冷战了，该怎么开口沟通？", "chatId": "p10_" + uuid.uuid4().hex}, token)
print("  聊天: success=%s ttft=%sms chars=%d" % (resp["success"], resp["ttft_ms"], len(resp["text"])))
PYEOF
echo "  RAG_RETRIEVAL: $(grep -aoE 'hits=[0-9]+' "$LOG" | sort | uniq -c | tr '\n' ' ')"
echo "  Rerank call 次数 = $(grep -ac 'Rerank call' "$LOG")   （修复前聊天链路恒为 0）"
echo "  hybrid 融合统计: $(grep -aoE 'fused=[0-9]+' "$LOG" | tail -2 | tr '\n' ' ')"

echo
echo "=== ② 45 例：纯检索基线（admin rerank=false，不走 postprocessor）==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py --base "http://127.0.0.1:$PORT/api" \
  2>&1 | tee "outputs/p10-base-$STAMP.txt" | tail -1
echo "     修复前同口径 = 0.751（记忆挤占下）；迁移前 dashscope 单轮 = 0.807"

echo
echo "=== ③ 45 例：远端 8B 重排（走 postprocessor）==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py --base "http://127.0.0.1:$PORT/api" --rerank \
  2>&1 | tee "outputs/p10-rerank-$STAMP.txt" | tail -1

echo
echo "=== ④ 真实 E2E 回归 ==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/e2e_live.py --base "http://127.0.0.1:$PORT/api" 2>&1 | tail -3

echo
echo "=== ⑤ 向量通道 top-8 构成（记忆条数应不再是多数）==="
# ⚠️ 这一步必须用 envs/default 的解释器：pg8000 只装在那里，托管 python 只有 psycopg2
RV="C:/Users/lwx/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
$RV - <<'PYEOF'
import io, json, os, re, urllib.request, pg8000.native
yml = io.open("target/classes/application-local.yml", encoding="utf-8").read()
KEY = re.search(r"api-key:\s*(sk-\S+)", yml).group(1)
con = pg8000.native.Connection(user="postgres", password="123456", database="postgres",
                               host="127.0.0.1", port=5432)
q = "我们冷战了，该怎么开口沟通？"
req = urllib.request.Request("https://api.siliconflow.cn/v1/embeddings",
    data=json.dumps({"model": "Qwen/Qwen3-Embedding-0.6B", "input": [q], "encoding_format": "float"}).encode(),
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with op.open(req, timeout=60) as r:
    v = sorted(json.loads(r.read())["data"], key=lambda x: x["index"])[0]["embedding"]
lit = "[" + ",".join("%.7g" % x for x in v) + "]"
rows = con.run("SELECT COALESCE(metadata->>'source','(knowledge)') FROM vector_store "
               "ORDER BY embedding <=> CAST(:v AS vector) LIMIT 8", v=lit)
mem = sum(1 for r in rows if r[0] == 'memory')
print("  top-8: memory=%d / knowledge=%d  （修复前 memory=6 / knowledge=2）" % (mem, 8 - mem))
print("  注：向量通道本身仍有记忆竞争 —— 本期修的是「过滤不得吃掉候选位」，")
print("      真正的 SQL 级排除记在 phase10 tasks.md 的『后续』里，未做。")
con.close()
PYEOF

echo
echo "=== ⑥ 应用层 ERROR ==="
echo "  $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"

kill_port "$PORT"
echo "DONE_PHASE10 STAMP=$STAMP LOG=$LOG"
