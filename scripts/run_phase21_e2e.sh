#!/bin/bash
#
# ⚠️ 本文件是**入仓副本**（canonical）。logs/ 被 gitignore → 否则会丢。
# 用法： cd /d/java/lwx-ai-agent && bash scripts/run_phase21_e2e.sh
# phase21 端到端验证：范围护栏措辞可切换（ADR-53）。
#
# 与 run_phase20_e2e.sh 的差别（**这几点就是要验的东西**）：
#   1. ⭐ 本轮改了 ChatExecutor / AgentRegistry 的**构造期依赖**
#      （@DependsOn("scopeWording") + 静态生效值 ScopeWording.activeSystemPrompt()）。
#      这正是"单测全绿但 Spring 装配失败 / 顺序错导致配置被静默忽略"的典型形态
#      —— 如果 ScopeWording 晚于 ChatExecutor 构造，配置的对照臂会被静默忽略，
#      且表现与"配置生效"完全一样（都是 adjacent-help），排查时无从下手。
#      所以**启动横幅里 scope-wording 自报的值**是本脚本的核心断言。
#   2. 期望启动日志出现 `[scope-wording] 生效范围护栏措辞 = adjacent-help（生产默认）`。
#   3. 期望 llm_endpoint_configured 只有 level="primary" 一条（零 tier，沿用 ADR-52）。
#   4. 期望 llm.fallback 指标条数 = 0（沿用 ADR-52）。
#
# 判据纪律（本仓已踩过多次同类坑）：
#   ⛔ "日志里 grep 到级别名" ≠ "发生了降级" —— 启动横幅里本来就有这些字。
#   ⛔ 判据只认 /actuator/prometheus 指标。
#   ⛔ 裸 grep 数字必命中噪声（traceId/用户名片段），状态码要带上下文。
#
# 沙箱会回收后台进程 → 启动/测试/收尾必须在**同一条命令**内串完。
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
STAMP=$(date +%H%M%S)
LOG="logs/app-e2e-phase21-$STAMP.log"

# 本地凭据（gitignored）：DeepSeek key 与其余密钥都在这里，不进仓库
set -a
# shellcheck disable=SC1091
. .workbuddy-ai/.env.local
set +a

eval "$($PY scripts/prod_env_from_local.py 2>/dev/null)"

# ⚠️ prod_env_from_local.py 会跳过 ${VAR}（无默认值）的项，
#    所以 OPENAI_API_KEY 必须由我们显式注入 —— 这是**有意的**，不是遗漏。
: "${DEEPSEEK_API_KEY:?缺 DEEPSEEK_API_KEY（应在 .workbuddy-ai/.env.local）}"
export OPENAI_API_KEY="$DEEPSEEK_API_KEY"
: "${SF_API_KEY:?缺 SF_API_KEY}"

echo "凭据就绪（值不回显）："
echo "  OPENAI_BASE_URL = $OPENAI_BASE_URL   ← 应来自 yml 默认值 https://api.deepseek.com"
echo "  OPENAI_MODEL    = $OPENAI_MODEL      ← 应来自 yml 默认值 deepseek-flash"
echo "  OPENAI_API_KEY  = <SET len=${#OPENAI_API_KEY}>"
echo "  SF_API_KEY      = <SET len=${#SF_API_KEY}>"

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

# MCP server：工具面（搜索/天气/图片）。8125 常闭、8392 上已有实例 → 优先复用
MCP_PORT=8392
port_open $MCP_PORT || { echo "拉起 mcp-server :$MCP_PORT"; $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > "logs/mcp-phase21-$STAMP.log" 2>&1 & sleep 20; }
export MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp"
export ADMIN_API_KEY=lwx-admin-eval-2026
# 评测期冻结后台任务：后台萃取会写库，污染检索输入
export APP_SCHEDULER_MASTER_ENABLED=false

echo
echo "=== 启动（配置走 yml 默认值 + 仅密钥走环境变量）==="
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" \
  "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$LOG" 2>&1 &

PORT=""
for i in $(seq 1 84); do
  PORT=$(grep -ao "Tomcat started on port [0-9]*" "$LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -qaE "BUILD FAILURE|cancelling refresh attempt|APPLICATION FAILED TO START" "$LOG" 2>/dev/null \
    && { echo "❌ 启动失败（本轮改了构造器签名与 bean 类型，这是最该盯的地方）";
         grep -aE "ERROR\] /D|Caused by|Parameter [0-9]+ of constructor" "$LOG" | tail -15; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "❌ 未取到端口"; tail -20 "$LOG"; exit 1; }
echo "端口=$PORT  LOG=$LOG"
echo "  ✅ F1 通过：应用启动成功（ObjectProvider<LlmFallbackTier> 装配 OK）"

echo
echo "=== 断言 A：生效端点（LlmGateway 自报，不是读 yml 猜的）==="
grep -aoE "\[ADR-48\] 降级链 [a-z-]+ 级实际生效端点：.*" "$LOG" | sed 's/^/  /'
grep -aq "https://api.deepseek.com | deepseek-flash" "$LOG" \
  && echo "  ✅ A 通过：primary 打到 DeepSeek 官方 + deepseek-flash" \
  || echo "  ❌ A 失败：生效端点不是 DeepSeek"

echo
echo "=== 断言 A2（ADR-52 沿用）：启动横幅打印新格式的总闸与链形 ==="
grep -aoE "\[ADR-52\] 网关生效 timeout：.*" "$LOG" | sed 's/^/  /'
grep -aq "degradeEnabled=true" "$LOG" && echo "  ✅ A2-1 通过：总闸 degrade-enabled 生效（true）" \
  || echo "  ❌ A2-1 失败：启动横幅没打 degradeEnabled"
grep -aq "降级链=\[空（单级：主链 + 重试）\]" "$LOG" \
  && echo "  ✅ A2-2 通过：链为空（零 tier），行为退化为「主链 + 重试」" \
  || echo "  ❌ A2-2 失败：链形不是空（是否误注册了降级级？）"

echo
echo "=== 断言 A3（ADR-53 新增，本轮核心）：scope 措辞开关真的生效并自报 ==="
grep -aoE "\[scope-wording\].*" "$LOG" | head -2 | sed 's/^/  /'
SW=$(grep -aoE "\[scope-wording\] 生效范围护栏措辞 = [a-z-]+" "$LOG" | head -1 | grep -oE "[a-z-]+$")
ACTIVE=${APP_CHAT_SCOPE_WRITING:-adjacent-help}
if [ "$SW" = "$ACTIVE" ]; then
  echo "  ✅ A3 通过：scope 措辞生效值（$SW）== 配置值（$ACTIVE）"
else
  echo "  ❌ A3 失败：自报=$SW 配置=$ACTIVE —— 开关被静默忽略（构造顺序问题？）"
fi
[ "$SW" = "adjacent-help" ] && echo "  ✅ A3-1 通过：默认生产值是 adjacent-help（未被误改成 strict）" \
  || echo "  ⚠️ A3-1：本轮生效值是 $SW（若为 strict 说明这是对照臂，不是生产默认）"

echo
echo "=== 断言 B：两个降级级**不得注册**（bean 应被 @ConditionalOnProperty 拦掉）==="
F=$(grep -ac "降级链 fallback 级实际生效端点" "$LOG" || true)
L=$(grep -ac "降级链 last-resort 级实际生效端点" "$LOG" || true)
B=$(grep -ac "bigmodel 兜底已注册" "$LOG" || true)
echo "  fallback 级自报行数=$F   last-resort 级自报行数=$L   bigmodel 兜底注册行数=$B"
[ "$F" = "0" ] && [ "$L" = "0" ] && [ "$B" = "0" ] \
  && echo "  ✅ B 通过：两个降级级都没注册" \
  || echo "  ❌ B 失败：仍有降级级被注册"

echo
echo "=== 真实 E2E（注册→SSE 聊天→RAG→Agent→记忆→安全负向）==="
cd scripts
$PY e2e_live.py --base "http://127.0.0.1:$PORT/api" --output "../outputs/e2e-phase21-$STAMP.json" 2>&1 | tail -45
cd /d/java/lwx-ai-agent

echo
echo "=== 断言 D：运行期指标（唯一可信判据）==="
$PY - "$PORT" <<'PYEOF'
import sys, urllib.request
port = sys.argv[1]
try:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/actuator/prometheus", timeout=20) as r:
        m = r.read().decode('utf-8', 'replace')
except Exception as e:
    print("  prometheus 端点不可用:", e); sys.exit(0)
eps = [l for l in m.splitlines() if l.startswith("llm_endpoint")]
calls = [l for l in m.splitlines() if l.startswith("llm_call")]
fb = [l for l in m.splitlines() if l.startswith("llm_fallback")]
print("  llm.endpoint.configured（生效端点自报）:")
for l in eps: print("   ", l[:220])
print("  llm.call（谁在服务 / 结局）:")
for l in calls: print("   ", l[:220])
print(f"  llm.fallback 指标条数 = {len(fb)}" + ("  ✅ 未发生降级" if not fb else "  ⚠️ 发生了降级："))
for l in fb: print("   ", l[:220])
# 判定
ok_ep = any('level="primary"' in l and "api.deepseek.com" in l and "deepseek-flash" in l for l in eps)
levels = sorted({l.split('level="')[1].split('"')[0] for l in eps if 'level="' in l})
only_primary = levels == ["primary"]
ok_call = any('provider="primary"' in l and 'outcome="success"' in l for l in calls)
print()
print("  ✅ D1 primary 端点 = DeepSeek/deepseek-flash" if ok_ep else "  ❌ D1 primary 端点不对")
print(f"  {'✅' if only_primary else '❌'} D2 生效端点只有 primary（零 tier），实际={levels}")
print("  ✅ D4 primary 有成功调用" if ok_call else "  ❌ D4 primary 无成功调用")
print("  ✅ D5 未发生降级" if not fb else "  ❌ D5 发生了降级")
PYEOF

echo
echo "=== 断言 A4（JEV，2026-09-28 启用）：JEV 真在服务 + shadow 分支被走到 ==="
echo "  ⚠️ 本条只对「JEV 已启用」的配置有意义；OFF 时 guardrail.shadow 必然为 0，不算失败"
$PY - "$PORT" <<'PYEOF'
import sys, urllib.request
port = sys.argv[1]
try:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/actuator/prometheus", timeout=20) as r:
        m = r.read().decode('utf-8', 'replace')
except Exception as e:
    print("  prometheus 不可用:", e); sys.exit(0)
sh = [l for l in m.splitlines() if l.startswith("guardrail_shadow")]
print("  guardrail.shadow 条数 =", len(sh))
for l in sh: print("   ", l[:200])
print("  → JEV 第二信号在 shadow 模式下**无条件记账**（不论是否越阈），"
      "所以只要有一条聊天消息走过 ChatEntry，这里就该 >0")
PYEOF
echo "  jev call failed = $(grep -ac 'jev call failed' "$LOG")  （>0 表示结果可能来自 LLM 回退）"

echo
echo "=== 断言 E：402 作为 HTTP 状态（带上下文，不用裸数字）==="
echo "  带上下文命中次数：$(grep -acE 'HTTP[ /]?402|status[ =:]+402|"status" *: *402' "$LOG")"
echo "  应用层 ERROR 数 = $(grep -acE '^[0-9]{4}-.* ERROR' "$LOG")"
grep -aE '^[0-9]{4}-.* ERROR' "$LOG" | tail -5

echo
echo "=== 用户可见结果抽查（必须不是 5000「AI 服务暂时不可用」）==="
grep -ac "AI 服务暂时不可用" "$LOG" | sed 's/^/  出现次数（含配置文案）: /'
grep -a "AI 服务暂时不可用" "$LOG" | tail -3

kill_port "$PORT"
echo
echo "DONE_PHASE21_E2E STAMP=$STAMP LOG=$LOG"
