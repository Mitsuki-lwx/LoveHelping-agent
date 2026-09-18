#!/bin/bash
# ADR-31 任务 2（补做，2026-09-19）：量化"permit 覆盖整条链路"的**收益与代价**。
#
# 对照：AFTER（permit 覆盖整条链路，当前） vs BEFORE（permit 每次尝试借还）
#   判据 A（收益）：AFTER 假上游峰值 <= 闸门；BEFORE 假上游峰值 > 闸门 —— 证明"账记准了"
#   判据 B（代价）：AFTER 的入口 4003 数量相对 BEFORE 的变化 —— 重试期间不再让出许可的代价
#
# ⚠️ 两个历史缺陷（本次修复；不修则实验必然 INCONCLUSIVE）：
#   1) 假上游 `max` 是**进程内累计最大值** → 必须每轮 /reset。
#      现在 probe 内部强制 reset 并**校验清零成功**，否则拒绝产出数据。
#      （2026-09-18 第一次实验就是这么报废的：两份产物 max 都是 4、`max_at` 完全相同。）
#   2) 原来用 `git checkout HEAD` 当"改造前" → HEAD 早已包含 ADR-31 的修复，
#      两轮实际跑同一份代码。现在用**显式分界提交**：
#      `49ff0c0` 才是引入 permit 上提的提交，故 BEFORE = 49ff0c0^（= fbb3144）。
set -u
cd /d/java/lwx-ai-agent

# 密钥一律走环境变量，**不得硬编码**（本仓库既有惯例，见 scripts/soak.py 用法）。
ADMIN_API_KEY="${ADMIN_API_KEY:?需要设置 ADMIN_API_KEY 环境变量，例如 ADMIN_API_KEY=xxx bash scripts/run_permit_cost_ab.sh}"

PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
GATE="${GATE:-4}"
# ⚠️ 两层闸门必须**拆开**才能观测到网关层的改动：
#   准入层（max-inflight）若 <= 网关层（max-concurrent-calls），网关许可永不 bind，
#   网关侧改什么都是白改、实验必然"无差异"（本项目 ADR-29 的双闸门陷阱，见 skill §7）。
#   故默认让准入比网关宽松：INFLIGHT=8 > GATEWAY=4。
INFLIGHT="${INFLIGHT:-8}"
GATEWAY="${GATEWAY:-4}"
FAKE_PORT=19080
MCP_PORT=8300
SRC=src/main/java/cn/lwx/lwxaiagent/infrastructure/ai/LlmGateway.java
BACKUP=logs/LlmGateway.java.permitcost-backup
ADR31_REF="${ADR31_REF:-49ff0c0^}"
# 退避时长是**本实验的灵敏度旋钮**：permit 在两次尝试之间被让出的窗口长度 = 退避时长。
# 默认 200ms（相对 2s 的尝试耗时只占 ~9%）→ 预期差异低于分辨率；
# 放大到 2000ms 可作为**灵敏度对照**，证明装置确实能区分两版实现。
BACKOFF_MS="${BACKOFF_MS:-200}"
TAG="${TAG:-}"
# 网关争抢线程数：后台任务走网关但**不走准入**，是唯一能让"网关许可 bind"发生的来源。
# 0 = 纯用户流量（此时两版必然无差异，见 docs/09 §8.12）。
CONTENTION="${CONTENTION:-0}"

JVM_ARGS="-Dspring.ai.openai.base-url=http://127.0.0.1:$FAKE_PORT -Dapp.online.max-inflight=$INFLIGHT -Dapp.online.wait-ms=0 -Dapp.llm.max-concurrent-calls=$GATEWAY -Dapp.llm.fallback-enabled=false -Dapp.llm.retry.backoff-ms=$BACKOFF_MS -Dapp.llm.retry.jitter=0 -Dapp.llm.retry.budget-per-minute=1000 -Dapp.llm.circuit.enabled=false -Dapp.llm.adaptive.enabled=false"
echo "准入=$INFLIGHT | 网关=$GATEWAY | 退避=$BACKOFF_MS ms | 争抢=$CONTENTION | 产物后缀='$TAG'"

# ---- 源码保护：无论怎么退出，都必须把工作区的 LlmGateway.java 还原 ----
cp "$SRC" "$BACKUP"
BEFORE_SUM=$(md5sum "$SRC" | cut -d' ' -f1)
restore() {
  cp "$BACKUP" "$SRC"
  local now; now=$(md5sum "$SRC" | cut -d' ' -f1)
  if [ "$now" = "$BEFORE_SUM" ]; then
    echo "[restore] LlmGateway.java 已还原（md5 $now 与开工前一致）"
  else
    echo "[restore] ⚠️ 还原后 md5 不一致：期望 $BEFORE_SUM 实得 $now —— 请人工检查！"
  fi
}
trap restore EXIT INT TERM

echo "=== 0. mcp-server ($MCP_PORT) ==="
if $PY -c "
import socket,sys
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try: s.bind(('127.0.0.1', $MCP_PORT))
except OSError: sys.exit(1)
finally: s.close()
" 2>/dev/null; then
  $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > logs/mcp-server.log 2>&1 &
  sleep 18; echo "mcp-server 已启动"
else
  echo "$MCP_PORT 已有服务（复用）"
fi

echo "=== 0b. 假上游（slow_error, delay 2s）==="
if $PY -c "
import socket,sys
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try: s.bind(('127.0.0.1', $FAKE_PORT))
except OSError: sys.exit(1)
finally: s.close()
" 2>/dev/null; then
  FAKE_MODE=slow_error FAKE_DELAY=2 FAKE_PORT=$FAKE_PORT $PY scripts/fake_llm.py > logs/fake_llm.log 2>&1 &
  sleep 3
  curl -s -m 5 "http://127.0.0.1:$FAKE_PORT/stats"; echo
else
  echo "$FAKE_PORT 已有服务（复用；若为旧版无 /reset，请先杀掉）"
fi
curl -s -m 5 "http://127.0.0.1:$FAKE_PORT/reset" | head -c 200; echo

run_case() {
  local label="$1"; local out="$2"; local log="logs/app-permit-$label.log"
  echo
  echo "############ 用例 $label ############"
  MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp" ADMIN_API_KEY="$ADMIN_API_KEY" \
  $JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" "-Dmaven.multiModuleProjectDirectory=$MPD" \
    org.codehaus.plexus.classworlds.launcher.Launcher -o \
    -Dspring-boot.run.jvmArguments="$JVM_ARGS" spring-boot:run > "$log" 2>&1 &
  local PORT=""
  for i in $(seq 1 72); do
    PORT=$(grep -o "Tomcat started on port [0-9]*" "$log" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
    [ -n "$PORT" ] && break
    if grep -q "BUILD FAILURE\|APPLICATION FAILED TO START" "$log" 2>/dev/null; then
      echo "启动失败："; $PY -c "
import io
t=io.open('$log',encoding='utf-8',errors='replace').read().splitlines()
ls=[l for l in t if 'Progress' not in l and l.strip()]
print('\n'.join(l[:170] for l in ls[-18:]))
"; return 1
    fi
    sleep 5
  done
  [ -z "$PORT" ] && { echo "未取到端口"; return 1; }
  echo "端口=$PORT  容量: $(grep -o 'capacity:.*' "$log" | tail -1 | cut -c1-140)"

  BASE="http://127.0.0.1:$PORT/api" GATE=$GATEWAY DURATION=24 RATE_S=0.35 USERS=8 \
  CONTENTION=$CONTENTION \
  LABEL="$label" OUT="$out" FAKE_STATS="http://127.0.0.1:$FAKE_PORT/stats" \
    $PY scripts/probe_permit_cost.py

  export APP_PORT="$PORT"
  $PY -c "
import os, subprocess
port = os.environ['APP_PORT']
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+port], capture_output=True, errors='replace').stdout or ''
for pid in {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('app stopped')
"
  sleep 3
}

echo "=== 1. 用例 AFTER（当前：permit 覆盖整条链路）==="
run_case after "outputs/adr31-permit-cost-after$TAG.json"

echo "=== 2. 用例 BEFORE（permit 每次尝试借还；源码取自 $ADR31_REF）==="
git show "$ADR31_REF:$SRC" > "$SRC"
echo "已换入 $ADR31_REF 版本；permit 在尝试内借还的标记行数: $(grep -c 'limiter.release()' "$SRC")"
run_case before "outputs/adr31-permit-cost-before$TAG.json"

echo "=== 3. 汇总对比 ==="
$PY -c "
import json, os
rows = []
for label, p in (('AFTER', 'outputs/adr31-permit-cost-after$TAG.json'), ('BEFORE', 'outputs/adr31-permit-cost-before$TAG.json')):
    if not os.path.exists(p):
        rows.append((label, None)); continue
    rows.append((label, json.load(open(p, encoding='utf-8'))))
print('%-7s %-9s %-8s %-13s %-30s %-8s %-8s' % (
    '用例', '上游峰值', '网关', '准入进/拒', '结果分布', '4003占比', '上游调用'))
for label, d in rows:
    if not d:
        print('%-7s (缺失)' % label); continue
    fs = d['fake_stats']
    n = d.get('requests_fired') or 1
    g4 = d['distribution'].get('gate_4003', 0)
    print('%-7s %-9s %-8s %-13s %-30s %-8s %-8s' % (
        label, fs.get('max'), d['gate'],
        '%s/%s' % (d.get('gate_entered'), d.get('gate_rejected')),
        json.dumps(d['distribution'], ensure_ascii=False),
        '%.0f%%' % (100.0 * g4 / n), fs.get('total')))
print()
if all(d for _, d in rows):
    a, b = rows[0][1], rows[1][1]
    pa, pb = a['fake_stats'].get('max') or 0, b['fake_stats'].get('max') or 0
    g = a['gate']
    ra, rb = a['distribution'].get('gate_4003', 0), b['distribution'].get('gate_4003', 0)
    u5a, u5b = a['distribution'].get('upstream_5000', 0), b['distribution'].get('upstream_5000', 0)
    ea, eb = a.get('gate_entered') or 0, b.get('gate_entered') or 0
    print('准入=$INFLIGHT | 网关=$GATEWAY | 退避=$BACKOFF_MS ms | 争抢=$CONTENTION')
    print()
    print('判据 A1（收益）AFTER 上游峰值 <= 网关(%d) → %s (实测 %d)' % (g, 'PASS' if pa <= g else 'FAIL', pa))
    print('判据 A2（对照）BEFORE 上游峰值 >  网关(%d) → %s (实测 %d)' % (g, 'PASS' if pb > g else '无差异', pb))
    print('判据 B1（代价）入口 4003:  BEFORE %d → AFTER %d (%+d)' % (rb, ra, ra - rb))
    print('判据 B2（副作用）上游 5000: BEFORE %d → AFTER %d (%+d)' % (u5b, u5a, u5a - u5b))
    print('辅助观测      准入总数:    BEFORE %s → AFTER %s (%+.0f)'
          % (eb, ea, ea - eb))
    ca, cb = a.get('contention_distribution') or {}, b.get('contention_distribution') or {}
    if ca or cb:
        na = sum(ca.values()) or 1
        nb = sum(cb.values()) or 1
        print('判据 C（让路）后台争抢 4003: BEFORE %d/%d (%.0f%%) → AFTER %d/%d (%.0f%%)'
              % (cb.get('gate_4003', 0), nb, 100.0 * cb.get('gate_4003', 0) / nb,
                 ca.get('gate_4003', 0), na, 100.0 * ca.get('gate_4003', 0) / na))
        print('            后台成功:        BEFORE %d → AFTER %d'
              % (cb.get('ok', 0), ca.get('ok', 0)))
    print()
    # 判定口径：
    #   旧版在退避期让出网关许可 → 其他请求可趁隙取走 → 本请求的重试可能被自家 4003 拒掉。
    #   故"旧版 4003 更多 / 上游 5000 更少"即是该缺陷的可观测特征。
    if pb > g:
        print('PERMIT_COST=PASS（两版可区分，且 BEFORE 确实越界）')
    elif abs(ra - rb) >= 3 or abs(u5a - u5b) >= 3:
        print('PERMIT_COST=DIFFERENT（两版可区分：BEFORE 的 4003/5000 结构与 AFTER 不同）')
    else:
        print('PERMIT_COST=INCONCLUSIVE（差异低于本次负载/退避下的分辨率）')
"
echo "=== 4. 停假上游 ==="
$PY -c "
import subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :$FAKE_PORT'], capture_output=True, errors='replace').stdout or ''
for pid in {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('fake upstream stopped')
"
echo DONE_PERMIT
