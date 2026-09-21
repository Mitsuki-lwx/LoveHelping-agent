#!/bin/bash
# 证明「重排确实走远端」：关掉本地 8091，若仍成功后端只可能是硅基流动 8B。
#
# 为什么需要这个脚本：`LocalDocumentReranker` 此前**一行日志都没有**，而实测发现 8091
# 可能恰好在监听 → 单看"重排生效了（命中数收敛到 top-k）"**无法区分本地/远端**。
# 本脚本同时给出三重证据：① 8091 已关；② 启动时的配置回显；③ 每次调用的 DEBUG 实录。
set -u
cd /d/java/lwx-ai-agent

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
APP_LOG="logs/app-rerank-proof-$STAMP.log"
ADMIN_KEY=lwx-admin-eval-2026

eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
[ -n "${SF_API_KEY:-}" ] || { echo "ERROR: SF_API_KEY 缺失"; exit 1; }

kill_port() {
  PORT="$1" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['PORT']],
                     capture_output=True, errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
pids = {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}
for pid in pids:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed on %s: %s' % (os.environ['PORT'], pids or 'none'))"
}

echo "=== ① 关掉本地重排服务 8091 ==="
kill_port 8091

RERANK_ENABLED=true \
RERANK_MODE=remote \
RERANK_URL=https://api.siliconflow.cn/v1/rerank \
RERANK_MODEL=Qwen/Qwen3-Reranker-8B \
LOGGING_LEVEL_CN_LWX_LWXAIAGENT_RAG_RERANK=DEBUG \
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY="$ADMIN_KEY" \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$APP_LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$APP_LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qE "BUILD FAILURE|cancelling refresh attempt" "$APP_LOG" 2>/dev/null \
    && { echo "启动失败"; grep -aE "Caused by" "$APP_LOG" | tail -6; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -5 "$APP_LOG"; exit 1; }
echo "端口=$PORT"

echo "=== ② 再次确认 8091 关闭（本地不可能被走到）==="
$PY -c "
import socket
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',8091)); print('  8091 OPEN -> 证据不成立')
except Exception: print('  8091 closed OK')
finally: s.close()"

echo "=== ③ 启动时的配置回显 ==="
grep -a "Rerank configured" "$APP_LOG" | cut -c1-240 || echo "  (无回显)"

echo "=== ④ 真实检索（rerank=true）==="
$PY -c "
import json,urllib.request,urllib.parse,time
for q in ['我们冷战了怎么办','煤气灯效应怎么办']:
    u='http://127.0.0.1:$PORT/api/admin/rag/retrieve?'+urllib.parse.urlencode({'query':q,'rerank':'true'})
    t0=time.time()
    d=json.loads(urllib.request.urlopen(urllib.request.Request(u,headers={'X-Admin-Key':'$ADMIN_KEY'}),timeout=120).read())
    print('  %-12s %.2fs 命中=%d top2=%s' % (q, time.time()-t0, len(d.get('hits',[])), d.get('hits',[])[:2]))"

echo "=== ⑤ 每次调用的 DEBUG 实录 ==="
grep -aE "Rerank (call|ok)" "$APP_LOG" | tail -6 | cut -c1-210
echo "  调用次数 = $(grep -acE 'Rerank call' "$APP_LOG")"
echo "=== ⑥ 失败/降级痕迹 ==="
grep -aiE "reranker (unavailable|circuit|saturated)|Invalid local reranker" "$APP_LOG" | head -3 || true
echo "  (空=无失败)"

echo "=== ⑦ 45 例检索评测：真·远端 8B 重排 ==="
ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py \
  --base "http://127.0.0.1:$PORT/api" --rerank 2>&1 | tee "outputs/p8-rerank8b-$STAMP.txt" | tail -2

APP_PORT="$PORT" $PY -c "
import os, subprocess
out=subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['APP_PORT']],capture_output=True,errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
for pid in {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('app stopped')"
echo "DONE_RERANK_PROOF STAMP=$STAMP LOG=$APP_LOG"
