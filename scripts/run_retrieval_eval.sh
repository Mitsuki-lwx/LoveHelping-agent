#!/bin/bash
#
# 检索质量复测装置（2026-09-28 新增）。
#
# 为什么需要它：README 写「MRR@5 0.714 → 0.885（重排 +0.17）」，而 ADR-41 的记忆里是
#   「45 例三轮 0.751 → 0.856」。**同一件事有两个不同的记录值** → 用一次同库同进程的
#   两臂对照来裁决。
#
# 纪律（本仓硬规矩 ⑫⑬）：
#   ⛔ 评测期必须冻结库（APP_SCHEDULER_MASTER_ENABLED=false），否则后台萃取会写库、污染检索输入。
#   ⛔ 两臂必须在**同一个应用实例**内跑 → 共享同一份冻结的库 → 差异只来自重排。
#   ⛔ 必须断言"重排真的被调用"（逐次读 AdminController 的 rerank= 自报），
#      不能只看"配置为 true"。
#
# 用法： bash scripts/run_retrieval_eval.sh
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-retrieval-$STAMP.log"

set -a
# shellcheck disable=SC1091
. .workbuddy-ai/.env.local
set +a
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
: "${DEEPSEEK_API_KEY:?缺 DEEPSEEK_API_KEY}"
export OPENAI_API_KEY="$DEEPSEEK_API_KEY"
: "${SF_API_KEY:?缺 SF_API_KEY（embedding/rerank 走它）}"

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
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)"; }

MCP_PORT=8392
port_open $MCP_PORT || { echo "拉起 mcp-server :$MCP_PORT"; $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > "logs/mcp-retrieval-$STAMP.log" 2>&1 & sleep 20; }
export MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp"
export ADMIN_API_KEY=lwx-admin-eval-2026
# ⛔ 冻结库：禁止后台萃取/反思写库（否则两次评测之间库会变）
export APP_SCHEDULER_MASTER_ENABLED=false

echo "=== 启动（库冻结 APP_SCHEDULER_MASTER_ENABLED=$APP_SCHEDULER_MASTER_ENABLED）==="
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &

PORT=""
for i in $(seq 1 84); do
  PORT=$(grep -ao "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qaE "BUILD FAILURE|APPLICATION FAILED TO START" "$LOG" 2>/dev/null \
    && { echo "❌ 启动失败"; grep -aE "ERROR\] /D|Caused by" "$LOG" | tail -10; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "❌ 未取到端口"; tail -20 "$LOG"; exit 1; }
BASE="http://localhost:$PORT/api"
echo "端口=$PORT  BASE=$BASE  LOG=$LOG"

echo
echo "=== 库状态与量具健康（冻结是否生效 + 哪个库）==="
echo "  scheduler.master.enabled 生效值: $(grep -aoE 'master.*enabled[= ]+(true|false)' "$LOG" | head -1)"
grep -ao "Guardrail rules loaded.*" "$LOG" | head -1 | sed 's/^/  /'
echo "  已注册的向量/检索相关 bean 自报:"
grep -aoE "(RerankDocumentPostProcessor|MemoryVectorStore|PgVectorStore|SimpleVectorStore)" "$LOG" | sort -u | sed 's/^/    /'
echo "  PKIX=$(grep -ac 'PKIX path building failed' "$LOG")"

# 同臂多轮（硬规矩 ⑫：单轮数字不可作为结论 —— 上游 embedding 不确定，分辨率 ≈ ±0.015 MRR）
ROUNDS="${ROUNDS:-3}"

echo
echo "=== 臂 1：无重排（基线）× $ROUNDS 轮 ==="
for r in $(seq 1 "$ROUNDS"); do
  echo -n "  第 $r 轮: "; $PY scripts/retrieval_eval.py --base "$BASE" 2>&1 | grep "Recall@5 均值" || echo "（未取到）"
done

echo
echo "=== 臂 2：有重排（生产配置）× $ROUNDS 轮 ==="
for r in $(seq 1 "$ROUNDS"); do
  echo -n "  第 $r 轮: "; $PY scripts/retrieval_eval.py --base "$BASE" --rerank 2>&1 | grep "Recall@5 均值" || echo "（未取到）"
done

echo
echo "=== ⭐ 断言：重排**真的被调用**（逐次自报，不是读配置）==="
echo "  rerank=true  次数 = $(grep -ac 'rerank=true' "$LOG")   ← 应 ≈ 45"
echo "  rerank=false 次数 = $(grep -ac 'rerank=false' "$LOG")  ← 应 ≈ 45"
echo "  重排失败回退次数 = $(grep -ac 'LLM rerank call failed' "$LOG")  ← 必须为 0（否则数字被污染）"

echo
echo "=== 收尾 ==="
kill_port "$PORT"
echo "DONE_RETRIEVAL STAMP=$STAMP LOG=$LOG"
