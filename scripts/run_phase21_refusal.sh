#!/bin/bash
# phase21：范围护栏（拒答修复）复验装置。
#
# ⚠️ 本文件是**入仓副本**（canonical）。logs/ 下曾有一份运行实例，但 logs/ 被 gitignore
#    → 长期不可复现（ADR-53 §局限）。以本文件为准。
# 用法： cd /d/java/lwx-ai-agent && REPEAT=6 ARMS="strict adjacent-help" bash scripts/run_phase21_refusal.sh
#
# 臂由环境变量 ARM_W 选 scope 措辞版本（读 env var 注入到 Spring）：
#   strict        = 修复前措辞（"遇到明显无关的请求必须礼貌拒绝"）  —— 对照臂
#   adjacent-help = 修复后措辞（A/B 两类边界，5d8c4c3 引入）        —— 实验臂
#   <未设置>      = 生产默认
#
# ⚠️ 前提：ChatExecutor 的 scope 段落必须可切换。若尚未接线，本脚本只跑当前代码
#    （等价 adjacent-help），此时**只能测现状，不能做对照** —— 会在输出里显式标注。
#
# 为什么必须对照（不能只跑新措辞）：
#   ADR-48 原文：拒答 3/6 轮那次是 space-bunny vs glm-4-flash **两个模型**的差异。
#   现在只剩 DeepSeek 官方一个 provider，若只跑新措辞看到 0 拒答，
#   **分不清是"修复有效"还是"deepseek-flash 本来就不拒"** —— 这正是 ADR-47 那个坑。
#
# 口径纪律：
#   - 两臂只差 scope 措辞（base-url/model/key 全同）
#   - 评测期冻结库：APP_SCHEDULER_MASTER_ENABLED=false
#   - 留存每轮原文（记忆第 13 条）
#   - 沙箱回收后台进程 → 启动/测试/收尾必须在同一条命令内串完
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
REPEAT=${REPEAT:-6}
ARMS=${ARMS:-"strict adjacent-help"}
STAMP=$(date +%H%M%S)

set -a
# shellcheck disable=SC1091
. .workbuddy-ai/.env.local
set +a
eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"
: "${DEEPSEEK_API_KEY:?缺 DEEPSEEK_API_KEY（应在 .workbuddy-ai/.env.local）}"
export OPENAI_API_KEY="$DEEPSEEK_API_KEY"
: "${SF_API_KEY:?缺 SF_API_KEY}"

echo "凭据就绪（值不回显）："
echo "  OPENAI_BASE_URL = $OPENAI_BASE_URL"
echo "  OPENAI_MODEL    = $OPENAI_MODEL"
echo "  ARM_W           = ${ARM_W:-<未设置→生产默认>}"
echo "  ARMS            = $ARMS   REPEAT=$REPEAT"

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
port_open $MCP_PORT || { echo "拉起 mcp-server :$MCP_PORT"; $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > "logs/mcp-phase21-$STAMP.log" 2>&1 & sleep 20; }
export MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp"
export ADMIN_API_KEY=lwx-admin-eval-2026
export APP_SCHEDULER_MASTER_ENABLED=false

run_arm() {
  ARM_NAME="$1"; ARM_W="$2"
  LOG="logs/app-rs-$ARM_NAME-$STAMP.log"
  echo
  echo "################ 臂 $ARM_NAME （ARM_W=$ARM_W）################"
  export ARM_W
  # ⚠️ 环境变量名是 APP_CHAT_SCOPE_WRITING（对应 yml 的 ${APP_CHAT_SCOPE_WRITING:adjacent-help}）。
  #    本仓纪律：profile yml 字面值优先于环境变量 → 已 grep 确认无 profile 覆盖此键。
  export APP_CHAT_SCOPE_WRITING="$ARM_W"
  $JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
    "-Dmaven.multiModuleProjectDirectory=$MPD" \
    org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &
  PORT=""
  for i in $(seq 1 84); do
    PORT=$(grep -ao "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
    [ -n "$PORT" ] && break
    grep -qaE "BUILD FAILURE|cancelling refresh attempt|APPLICATION FAILED TO START" "$LOG" 2>/dev/null \
      && { echo "  ❌ 启动失败"; grep -aE "ERROR\] /D|Caused by" "$LOG" | tail -12; return 1; }
    sleep 5
  done
  [ -z "$PORT" ] && { echo "  ❌ 未取到端口"; tail -20 "$LOG"; return 1; }
  echo "  端口=$PORT"
  grep -aoE "\[ADR-48\] 降级链 [a-z-]+ 级实际生效端点：.*" "$LOG" | head -1 | sed 's/^/  生效端点：/'
  # 关键：自报 scope 措栏版本，证明臂真的切了（不是猜的）
  grep -aoE "\[scope-wording\].*" "$LOG" | head -2 | sed 's/^/  /'
  cd scripts
  $PY probe_refusal_scope.py --base "http://127.0.0.1:$PORT/api" --repeat "$REPEAT" \
    --group "${GROUPS:-all}" \
    --arm "$ARM_NAME" --output "../outputs/refusal-$ARM_NAME-$STAMP.json" 2>&1
  cd /d/java/lwx-ai-agent
  echo "  402(带上下文)=$(grep -acE 'HTTP[ /]?402|status[ =:]+402|"status" *: *402' "$LOG")  应用层ERROR=$(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"
  # ⭐ 量具健康判据（J5）：本轮第一次跑就是栽在这里 —— strict 臂 15 次 TLS 证书失败
  #    （PKIX path building failed），adjacent-help 臂 0 次 → 两臂条件不等价，对照失效。
  #    必须逐臂报告，不能只报应用层 ERROR 就算数。
  echo "  ⚠️ TLS证书失败(PKIX)=$(grep -ac 'PKIX path building failed' "$LOG")  " \
       "上游超时=$(grep -acE 'llm_call.*outcome="timeout"' "$LOG")  " \
       "embedding失败=$(grep -ac 'embeddings failed' "$LOG")  " \
       "L3护栏替换=$(grep -ac 'guardrail L3 blocked' "$LOG")"
  kill_port "$PORT"
}

for a in $ARMS; do run_arm "$a" "$a"; done

echo
echo "################ 汇总 ################"
$PY - "$STAMP" "$ARMS" <<'PYEOF'
import json, statistics, sys, glob, os
stamp, arms = sys.argv[1], sys.argv[2].split()
data = {}
for arm in arms:
    p = f"outputs/refusal-{arm}-{stamp}.json"
    if not os.path.exists(p):
        print(f"{arm}: 缺 {p}"); continue
    d = json.load(open(p, encoding="utf-8")); data[arm] = d
    print(f"\n===== 臂 {arm} （每行一个问题）=====")
    byq = {}
    for r in d["rows"]: byq.setdefault((r["group"], r["question"]), []).append(r)
    for (g, q), rows in byq.items():
        ok = [r for r in rows if r["ok"]]
        if not ok: print(f"  [{g}] {q[:24]}: 全轮失败"); continue
        lens = [r["chars"] for r in ok]
        lab = {}
        for r in ok: lab[r["label"]] = lab.get(r["label"], 0) + 1
        print(f"  [{g}] {q[:26]:<26} n={len(ok)} 去重={len({r['text'] for r in ok})} "
              f"中位={int(statistics.median(lens))} 短(<150)={sum(1 for l in lens if l<150)} {lab}")
print("\n⚠️ 数字只作线索。两臂差异必须人工读原文复核（outputs/refusal-*.json 含全部原文）。")
if len(data) < 2:
    print("⛔ 只跑成 1 臂 → **不能得结论**（分不清'修复有效'与'本模型本来就不拒'）。")
PYEOF
echo "DONE_PHASE21_REFUSAL STAMP=$STAMP"
