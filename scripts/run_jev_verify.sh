#!/bin/bash
# JEV（TypeSafe AI / System One）启用验证装置（2026-09-28）。
#
# ⚠️ 本文件是**入仓副本**（canonical）。logs/ 被 gitignore → 长期不可复现。
# 用法： cd /d/java/lwx-ai-agent && bash scripts/run_jev_verify.sh
# 前置： 根 .env.local 里有 JEV_API_KEY；target/classes/application-local.yml 里 app.jev.enabled=true
#
# 要验什么：`app.jev.enabled=true` 之后，**JEV 真的在服务**，而不是静默回退到 LLM。
#
# 判据（实现前写死）：
#   J1 启动日志里 JEV 相关配置生效（无 jev 装配失败）
#   J2 `POST /sentiment/score` 之后 `GET /sentiment/timeline` 的 **reason 精确等于
#      JevClient.MOOD_LEVELS 之一**（JEV 路径写的是"档位描述"；
#      LLM 回退路径写的是模型自由生成的一句话）→ 这是区分"JEV 生效"与"静默回退"的唯一硬判据
#   J3 日志 `jev call failed` 次数 = 0（量具健康：不能拿回退结果冒充 JEV 结果）
#   J4 score 落在 -2..2 且与档位自洽（第 N 档 → N-2）
#   J5 shadow 分支：`guardrail.shadow` 指标（词典 L3 先命中则走不到，为 0 也如实报）
#
# ⚠️ 装置自身纪律：沙箱回收后台进程 → 启动/测试/收尾必须在**同一条命令内**串完。
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-jev-$STAMP.log"

# 凭据：根 .env.local 现在是集中清单（gitignored）
set -a
# shellcheck disable=SC1091
[ -f .env.local ] && . ./.env.local
set +a
: "${JEV_API_KEY:?缺 JEV_API_KEY（应在根 .env.local）}"
: "${SF_API_KEY:?缺 SF_API_KEY}"
: "${OPENAI_API_KEY:?缺 OPENAI_API_KEY}"
echo "凭据就绪（值不回显）：JEV_API_KEY=<SET len=${#JEV_API_KEY}>  SF_API_KEY=<SET>  OPENAI_API_KEY=<SET>"
echo "期望：app.jev.enabled=true  guardrail.mode=shadow（来自 target/classes/application-local.yml）"

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
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('  killed %s' % os.environ['P'])"; }

MCP_PORT=8392
port_open $MCP_PORT || { echo "拉起 mcp-server :$MCP_PORT"; $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > "logs/mcp-jev-$STAMP.log" 2>&1 & sleep 20; }
export MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp"
export ADMIN_API_KEY="${ADMIN_API_KEY:-lwx-admin-eval-2026}"
export APP_SCHEDULER_MASTER_ENABLED=false

echo
echo "=== 启动 ==="
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &
PORT=""
for i in $(seq 1 84); do
  PORT=$(grep -ao "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qaE "BUILD FAILURE|cancelling refresh attempt|APPLICATION FAILED TO START" "$LOG" 2>/dev/null \
    && { echo "❌ 启动失败"; grep -aE "ERROR\] /D|Caused by|Parameter [0-9]+ of constructor" "$LOG" | tail -12; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "❌ 未取到端口"; tail -20 "$LOG"; exit 1; }
BASE="http://127.0.0.1:$PORT/api"
echo "端口=$PORT  BASE=$BASE  LOG=$LOG"

echo
echo "=== J1：JEV 装配与配置生效（无异常即通过）==="
grep -aiE "jev" "$LOG" | head -5 | sed 's/^/  /'
echo "  JevProperties 装配异常数 = $(grep -acE 'JevProperties.*(failed|error)' "$LOG")"

echo
echo "=== J2/J3/J4：情绪打分走 JEV（reason 精确等于档位枚举）==="
$PY - "$BASE" <<'PYEOF'
import json, sys, uuid, urllib.request, urllib.error
base = sys.argv[1]
sys.path.insert(0, 'scripts')
from verification_support import register, json_request

MOOD_LEVELS = ["非常糟糕或处于危机：绝望、想不开、崩溃",
               "低落、难过、沮丧",
               "平静、中立、没有明显起伏",
               "有起色、稍微好一些",
               "明显变好、积极、有希望"]

CASES = [("今天我和女朋友大吵了一架，特别难过，晚上一直睡不着", "低落/难过"),
         ("我们和好了，心里踏实多了，觉得有希望", "转好/有希望")]

user, token = register(base)
print("  已注册测试用户（值不回显）")
for text, expect in CASES:
    cid = "jevpt_" + uuid.uuid4().hex
    st, resp = json_request(base, "/sentiment/score", {"chatId": cid, "text": text}, token)
    if st != 200:
        print(f"  ❌ POST /sentiment/score 失败 HTTP {st}: {resp}"); continue
    st2, tl = json_request(base, "/sentiment/timeline", None, token, method="GET")
    rows = (tl or {}).get("data") or []
    hit = [r for r in rows if r.get("chatId") == cid]
    if not hit:
        print(f"  ❌ timeline 里没有 {cid}"); continue
    r = hit[0]
    reason = (r.get("reason") or "")
    via_jev = reason in MOOD_LEVELS
    print(f"  文本「{text[:14]}…」（期望 {expect}）")
    print(f"    score={r.get('score')}  reason={reason!r}")
    print(f"    → {'✅ 走 JEV（reason 精确等于档位枚举）' if via_jev else '⚠️ 非 JEV 路径（reason 是自由文本=LLM 回退）'}")
PYEOF

echo
echo "=== J3：量具健康（jev 失败次数；>0 表示结果可能来自回退）==="
echo "  jev call failed = $(grep -ac 'jev call failed' "$LOG")"
grep -a "jev call failed" "$LOG" | tail -3 | sed 's/^/    /'

echo
echo "=== J5：shadow 分支指标（词典 L3 先命中则走不到 JEV）==="
$PY - "$PORT" <<'PYEOF'
import sys, urllib.request
port = sys.argv[1]
try:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/actuator/prometheus", timeout=15) as r:
        m = r.read().decode('utf-8','replace')
except Exception as e:
    print("  prometheus 不可用:", e); sys.exit(0)
for pref in ("guardrail_shadow", "guardrail_trigger", "guardrail_shadow", "jev"):
    for l in m.splitlines():
        if l.startswith(pref):
            print("   ", l[:200])
print("  （无 guardrail.shadow 行 = 本次没有消息走到 JEV 第二信号；见下方说明）")
PYEOF

echo
echo "=== 收尾 ==="
kill_port "$PORT"
echo "DONE_JEV STAMP=$STAMP LOG=$LOG"
