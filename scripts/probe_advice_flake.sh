#!/bin/bash
# 归因探针：三牌 advice 协议偶发缺失，是 phase13 改动引入的，还是模型/上游本来就抖？
#
# 机制（已读代码确认）：NormalChatNode 从模型输出里找 @@ADVICE@@。
# 找不到 → ADVICE_TIERS 不产出 → SSE 无 advice 事件 → E2E 两条断言同时失败（对应两次独立请求）。
# 所以要看的是"模型在多少次请求里没吐协议"，并且必须与改动前对照。
#
# 用法：bash scripts/probe_advice_flake.sh <phase13|head> [N]
set -u
cd /d/java/lwx-ai-agent
VARIANT="${1:-phase13}"
N="${2:-8}"

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-flake-$VARIANT-$STAMP.log"
PCR="src/main/java/cn/lwx/lwxaiagent/rag/ParentChildDocumentRetriever.java"
PCRT="src/test/java/cn/lwx/lwxaiagent/rag/ParentChildDocumentRetrieverTest.java"

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

# ---------- 变体切换 ----------
if [ "$VARIANT" = "head" ]; then
  cp "$PCR" logs/flake-PCR.kept
  cp "$PCRT" logs/flake-PCRT.kept
  git show HEAD:"$PCR"  > "$PCR"
  git show HEAD:"$PCRT" > "$PCRT"
  echo "已切到 HEAD 版本（改动前）"
fi

# ---------- 前置：mcp-server ----------
if ! $PY -c "
import socket,sys
s=socket.socket(); s.settimeout(0.6)
try: s.connect(('127.0.0.1',8300)); sys.exit(0)
except Exception: sys.exit(1)
finally: s.close()"; then
  echo "mcp-server 未起，自举"
  ($JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=8300 > logs/mcp-server.log 2>&1 &)
  sleep 18
fi

eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"

echo "=== 启动（variant=$VARIANT）==="
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY=lwx-admin-eval-2026 \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qE "BUILD FAILURE|cancelling refresh attempt|ERROR\] /D" "$LOG" 2>/dev/null && {
    echo "启动失败"; grep -aE "ERROR\] /D|Caused by" "$LOG" | tail -6; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -5 "$LOG"; exit 1; }
echo "端口=$PORT"

echo "=== 探针：同一 advice 请求 × $N（与 E2E 里那条用例完全相同的 prompt）==="
APP_PORT="$PORT" PROBE_N="$N" VARIANT="$VARIANT" $PY - <<'PYEOF'
import json, os, sys, uuid
sys.path.insert(0, "scripts")
from verification_support import register, sse

base = "http://127.0.0.1:%s/api" % os.environ["APP_PORT"]
n = int(os.environ["PROBE_N"])
PROMPT = "我们恋爱三个月，昨天约会看我一直在看工作手机，她说我自私然后冷战，我该怎么回复她道歉？"
user, token = register(base)

ok = bad = err = 0
for i in range(n):
    cid = "flake_" + uuid.uuid4().hex
    r = sse(base, "/Love_app/chat/sse", {"prompt": PROMPT, "chatId": cid}, token)
    cards = [e for e in r["events"] if e["event"] == "advice"]
    tiers = []
    if cards:
        try:
            tiers = json.loads(cards[0]["data"]).get("tiers", []) or []
        except Exception:
            tiers = []
    names = {t.get("name") for t in tiers}
    complete = {"安全牌", "进击牌", "后撤牌"} <= names and all(t.get("content") for t in tiers)
    shielded = "\U0001F6E1" in r["text"]
    if not r["success"]:
        err += 1
        verdict = "ERR"
    elif complete and shielded:
        ok += 1
        verdict = "OK"
    else:
        bad += 1
        verdict = "BAD"
    print("  #%d %-4s advice事件=%d tiers=%s 盾=%s chars=%d ttft=%sms errors=%s"
          % (i + 1, verdict, len(cards), sorted(names) or "-", shielded,
             len(r["text"]), r["ttft_ms"], r["errors"][:1]))
print()
print("  汇总[%s]: 正常 %d / 协议缺失 %d / 请求失败 %d  （n=%d）"
      % (os.environ["VARIANT"], ok, bad, err, n))
PYEOF

echo "=== 应用层 ERROR = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG") ==="
kill_port "$PORT"

# ---------- 恢复变体 ----------
if [ "$VARIANT" = "head" ]; then
  cp logs/flake-PCR.kept "$PCR"
  cp logs/flake-PCRT.kept "$PCRT"
  diff logs/flake-PCR.kept "$PCR" && diff logs/flake-PCRT.kept "$PCRT" && echo "RESTORE_OK 两份均与备份一致"
fi
echo "DONE_ADVICE_FLAKE VARIANT=$VARIANT STAMP=$STAMP LOG=$LOG"
