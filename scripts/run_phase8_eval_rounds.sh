#!/bin/bash
# Phase 8 检索质量多轮对照：把"单轮噪声"这件事处理掉。
#
# 为什么必须多轮（docs/phase9-query-rewrite-prompt §S5 实测）：
# 同一配置两轮 MRR 就差 0.012，而 45 例上 0.012 的总位移不到"一个用例挪一个名次"
# → 单轮对照判不出小效应，只有 ~10% 量级的大效应才看得见。
# 排查/决策（要不要开重排、用哪个 embedding）必须多轮取中位。
#
# 用法：SF_API_KEY=xxx bash scripts/run_phase8_eval_rounds.sh [轮数]
set -u
cd /d/java/lwx-ai-agent

ROUNDS="${1:-3}"
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
APP_LOG="logs/app-p8rounds-$STAMP.log"
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
for pid in pids: subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed on %s: %s' % (os.environ['PORT'], pids or 'none'))"
}

# 本地重排服务必须关掉：否则无法区分 remote 走的是哪儿（本轮踩过的坑）
echo "=== 关掉 8091，确保只有远端一条路 ==="
kill_port 8091

RERANK_ENABLED=true RERANK_MODE=remote \
RERANK_URL=https://api.siliconflow.cn/v1/rerank RERANK_MODEL=Qwen/Qwen3-Reranker-8B \
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
[ -z "$PORT" ] && { echo "未取到端口"; exit 1; }
echo "端口=$PORT 轮数=$ROUNDS"

for r in $(seq 1 "$ROUNDS"); do
  echo "--- 第 $r 轮：基线（无重排）---"
  ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py --base "http://127.0.0.1:$PORT/api" \
    2>&1 | tee "outputs/p8r-base-$STAMP-$r.txt" | tail -1
  echo "--- 第 $r 轮：远端 8B 重排 ---"
  ADMIN_API_KEY="$ADMIN_KEY" $PY scripts/retrieval_eval.py --base "http://127.0.0.1:$PORT/api" --rerank \
    2>&1 | tee "outputs/p8r-rerank-$STAMP-$r.txt" | tail -1
done

echo "=== 汇总（中位与极差）==="
$PY - "$STAMP" <<'PYEOF'
import glob, io, re, sys, statistics
stamp = sys.argv[1]
def collect(pat):
    vals = []
    for f in sorted(glob.glob(pat.format(stamp=stamp))):
        t = io.open(f, encoding='utf-8', errors='replace').read()
        m = re.search(r"Recall@5 均值: ([\d.]+) \| MRR@5 均值: ([\d.]+)", t)
        if m: vals.append((float(m.group(1)), float(m.group(2))))
    return vals
for name, pat in (("基线", "outputs/p8r-base-{stamp}-*.txt"),
                  ("远端8B", "outputs/p8r-rerank-{stamp}-*.txt")):
    v = collect(pat)
    if not v:
        print("  %s: 无数据" % name); continue
    rc = [x[0] for x in v]; mr = [x[1] for x in v]
    print("  %-7s n=%d  Recall 中位 %.3f (%.2f~%.2f)  MRR 中位 %.3f (%.3f~%.3f)"
          % (name, len(v), statistics.median(rc), min(rc), max(rc),
             statistics.median(mr), min(mr), max(mr)))
PYEOF

APP_PORT="$PORT" $PY -c "
import os, subprocess
out=subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['APP_PORT']],capture_output=True,errors='replace').stdout or ''
text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
for pid in {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('app stopped')"
echo "DONE_P8ROUNDS STAMP=$STAMP LOG=$APP_LOG"
