#!/bin/bash
# Phase 8 收尾验证：重嵌入后的检索质量 + 远端 8B 重排对照 + 真实 E2E 回归
#
# 前置：mcp-server 已在 8300（本脚本会自检并自举）、vector_store 已重嵌入为
#       Qwen/Qwen3-Embedding-0.6B（scripts/reembed_knowledge_base.py）。
#
# 全程用**运行态环境变量**打开远端通道，不改 application.yml 的默认值——
# 默认值等本脚本的结论出来再定（见 docs/phase8-embedding-rerank-migration）。
#
# 用法：SF_API_KEY=xxx bash scripts/run_phase8_verify.sh
set -u
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
RV="C:/Users/lwx/.workbuddy/binaries/python/envs/reranker/Scripts/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
APP_LOG=logs/app-phase8-$STAMP.log
ADMIN_KEY=lwx-admin-eval-2026

# 凭据一律从 gitignored 的 local yml 提取（含 SF_API_KEY）
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: SF_API_KEY 不在 local yml（app.siliconflow.api-key）"; exit 1; }

port_open() { $PY -c "
import socket,sys
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',$1)); sys.exit(0)
except Exception: sys.exit(1)
finally: s.close()"; }

# ---------- mcp-server（主 app 强依赖，缺了整个 context 崩）----------
if port_open 8300; then
  echo "mcp-server 就绪（8300）"
else
  echo "自举 mcp-server"
  $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar \
      --server.address=127.0.0.1 --server.port=8300 > logs/mcp-server.log 2>&1 &
  for i in $(seq 1 40); do port_open 8300 && break; sleep 3; done
  port_open 8300 || { echo "mcp-server 起不来"; exit 1; }
fi

# ---------- 主 app：embedding=siliconflow（默认） + 重排=远端的 8B ----------
echo "=== 启动（embedding=siliconflow / rerank=remote 8B）==="
RERANK_ENABLED=true \
RERANK_MODE=remote \
RERANK_URL=https://api.siliconflow.cn/v1/rerank \
RERANK_MODEL=Qwen/Qwen3-Reranker-8B \
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$APP_LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$APP_LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qE "BUILD FAILURE|APPLICATION FAILED TO START|cancelling refresh attempt" "$APP_LOG" 2>/dev/null \
    && { echo "启动失败"; grep -aE "Caused by" "$APP_LOG" | tail -6; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -5 "$APP_LOG"; exit 1; }
echo "端口=$PORT"
echo "embedding bean: $(grep -aoE 'SiliconFlowEmbeddingModel|EPECTED_DIMENSIONS=1024|ragEmbeddingModel' "$APP_LOG" | sort -u | tr '\n' ' ')"
echo "rerank bean:    $(grep -aoE 'RerankDocumentPostProcessor|rerank mode=[a-z]*' "$APP_LOG" | sort -u | tail -2 | tr '\n' ' ')"

# ---------- A. 远端重排是否真的被调用（基线/开重排各一次，看日志与延迟）----------
echo "=== A. 重排链路冒烟（同一 query，开/关各一次）==="
$PY -c "
import json,urllib.request,urllib.parse,time
def call(rr):
    u='http://127.0.0.1:$PORT/api/admin/rag/retrieve?'+urllib.parse.urlencode(
        {'query':'我们冷战了怎么办','rerank':str(rr).lower()})
    t0=time.time()
    d=json.loads(urllib.request.urlopen(urllib.request.Request(u,headers={'X-Admin-Key':'$ADMIN_KEY'}),timeout=120).read())
    return d, time.time()-t0
for rr in (False,True):
    d,ms=call(rr)
    print('  rerank=%-5s  用时=%.2fs  命中=%d  top3=%s' % (rr, ms, len(d.get('hits',[])), d.get('hits',[])[:3]))
"

# ---------- B. 45 例检索评测：新 embedding 基线 ----------
echo "=== B. 新 embedding 基线（无改写、无重排）==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py \
  --base "http://127.0.0.1:$PORT/api" 2>&1 | tee "outputs/p8-base-$STAMP.txt" | tail -3

# ---------- C. 45 例检索评测：开远端 8B 重排 ----------
echo "=== C. 新 embedding + 远端 Qwen3-Reranker-8B ==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py \
  --base "http://127.0.0.1:$PORT/api" --rerank 2>&1 | tee "outputs/p8-rerank-$STAMP.txt" | tail -3

# ---------- D. 真实 E2E 回归 ----------
echo "=== D. 真实 E2E（22 项）==="
ADMIN_API_KEY="$ADMIN_KEY" SF_API_KEY="$SF_API_KEY" \
  $PY scripts/e2e_live.py --base "http://127.0.0.1:$PORT/api" 2>&1 | tail -6

echo "=== E. 日志痕迹 ==="
echo "  embedding 失败:  $(grep -ac 'SiliconFlowEmbedding\|embedding failed' "$APP_LOG" 2>/dev/null || echo 0)"
echo "  rerank 相关行:   $(grep -aciE 'rerank' "$APP_LOG" 2>/dev/null || echo 0)"
echo "  应用层 ERROR:    $(grep -acE '^[0-9]{4}-.* ERROR' "$APP_LOG" 2>/dev/null || echo 0)"

# ---------- 收尾 ----------
APP_PORT="$PORT" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['APP_PORT']],
                     capture_output=True, errors='replace').stdout or ''
for pid in {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('app stopped')"
echo "DONE_PHASE8_VERIFY STAMP=$STAMP LOG=$APP_LOG"
