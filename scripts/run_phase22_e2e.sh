#!/bin/bash
#
# ⚠️ 本文件是**入仓副本**（canonical）。logs/ 被 gitignore → 否则会丢。
# 用法： cd /d/java/lwx-ai-agent && bash scripts/run_phase22_e2e.sh
# phase22 端到端验证：护栏"适用范围"维度 + 流式拦截（ADR-55）。
#
# 与 run_phase21_e2e.sh 的差别（**这几点就是要验的东西**）：
#   1. ⭐ 新增 **V25 migration**（guardrail_rule 加 scope 列）→ 应用能不能起来本身就是断言。
#   2. ⭐ 新增断言 A5：**输入侧未被放松** —— 用户输入含自伤词仍必须被拦（4001）。
#      这是本轮"放宽输出侧"的回归底线：**门不能一起开**。
#   3. ⭐ 新增断言 A6：**输出侧不再误伤** —— 正常求助不被替换成转介文案。
#      （装置：scripts/probe_l3_stream.py，判据 J1/J3 见 docs/phase22-l3-stream-guardrail）
#   4. 沿用 phase21 的 A3（scope 措辞自报）与 A4（JEV shadow）。
#
# 与 run_phase20_e2e.sh 的差别（**这几点就是要验的东西**）：
#   1. ⭐ 本轮改了 ChatExecutor / AgentRegistry 的**构造期依赖**
#      （@DependsOn("scopeWording") + 静态生效值 ScopeWording.activeSystemPrompt()）。
#      这正是"单测全绿但 Spring 装配失败 / 顺序错导致配置被静默忽略"的典型形态
#      —— 如果 ScopeWording 晚于 ChatExecutor 构造，配置的对照臂会被静默忽略，
#      且表现与"配置生效"完全一样（都是 adjacent-help），排查时无从下手。
#      所以**启动横幅里 scope-wording 自报的值**是本脚本的核心断言。
#   2. 期望启动日志出现 `[scope-wording] 生效范围护栏措辞 = anchored-help（生产默认，ADR-61）`。
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
LOG="logs/app-e2e-phase22-$STAMP.log"

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
port_open $MCP_PORT || { echo "拉起 mcp-server :$MCP_PORT"; $JAVA -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=$MCP_PORT > "logs/mcp-phase22-$STAMP.log" 2>&1 & sleep 20; }
export MCP_SERVER_URL="http://localhost:$MCP_PORT/mcp"
export ADMIN_API_KEY=lwx-admin-eval-2026
# 评测期冻结后台任务：后台萃取会写库，污染检索输入
export APP_SCHEDULER_MASTER_ENABLED=false

# phase33 ③：情绪刹车片用例需要「深夜时段」，而"跑测试的时刻"不可控 →
# 把窗口强制为 0-24（isLateNight: start<=end ? hour>=0 && hour<24 : ... → **恒真**）。
# ⛔ 这是**评测装置**，不是生产值；生产默认 23:00-06:00（application.yml）。
export APP_EMOTION_BRAKE_START_HOUR=0
export APP_EMOTION_BRAKE_END_HOUR=24

echo
echo "=== 启动（配置走 yml 默认值 + 仅密钥走环境变量）==="
# ⛔ 预检：上一轮残留的应用 JVM（尤其**带活跃 SSE 连接被 kill** 的那种）会让端口处于
#    TIME_WAIT / 仍被占用（Windows 默认 ~240s）→ 新实例启动报「Port 8088 already in use」，
#    **看着像代码回归，其实是装置不干净**（本轮实测连踩两次，白排查两轮）。
#    所以：先杀，再**轮询等端口真正释放**才启动；等不到就明确报错，别让它变成"神秘的启动失败"。
#    只清**应用端口**；被复用的 mcp-server(8392) 不动。
kill_port 8088
$PY - <<'PYEOF_WAIT'
import os, subprocess, time, sys
port = "8088"
for i in range(30):
    out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+port], capture_output=True,
                         errors='replace').stdout or ''
    text = out.decode('utf-8','replace') if isinstance(out, bytes) else out
    holders = {l.split()[-1] for l in text.splitlines() if 'LISTENING' in l}
    if not holders:
        print(f"  端口 {port} 已释放（第 {i+1} 次探测）"); sys.exit(0)
    for pid in holders:
        subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
    time.sleep(1)
print(f"  ⛔ 端口 {port} 30s 内仍未释放 —— 启动必然失败，先解决占用再跑"); sys.exit(1)
PYEOF_WAIT

# ⛔ 端口**不是**"没人 LISTENING 就一定能绑"。Windows 会动态**保留端口段**
#    （`netsh int ipv4 show excludedportrange protocol=tcp`，Hyper-V/WSL/Docker 引起）——
#    落在保留段里的端口**看着空闲、绑不上**，Spring 报 `Port N was already in use`。
#    本轮实测：某时刻起 **8083-8182 被保留**（netsh 直接可见），8088 落在里面 → 连踩 4 轮"启动失败"，
#    而 netstat 里 8088 干干净净；实绑测试显示 **8088-8120 全不可绑**（=落在保留段内）。
#    ⛔ 教训：**"没人 LISTENING" ≠ "能绑"**（**看着像代码回归，其实是宿主环境变了**）。
#    所以：启动前**真的试绑**一个端口，把选中的端口用 SERVER_PORT 交给应用（Spring relaxed binding）。
APP_PORT=$($PY - <<'PYEOF_PORT'
import socket
for p in range(9000, 9100):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", p)); s.close(); print(p); break
    except OSError:
        s.close()
PYEOF_PORT
)
: "${APP_PORT:?没有可绑端口（9000-9099 全被占用或保留）}"
export SERVER_PORT="$APP_PORT"
echo "  应用端口 = $APP_PORT（实绑测试通过，避开 Windows 保留段）"
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

echo "=== 真实 E2E（注册→SSE 聊天→RAG→Agent→记忆→安全负向）==="
cd scripts
$PY e2e_live.py --base "http://127.0.0.1:$PORT/api" --output "../outputs/e2e-phase22-$STAMP.json" 2>&1 | tail -45
cd /d/java/lwx-ai-agent

echo
echo "=== 附加断言（ADR-68 收敛：与 CI 是同一实现，scripts/e2e_assertions.py）==="
echo "  ⛔ 缺 --log/--prometheus 时相关项报 SKIP（不计通过），不再有第二套 bash 判据"
$PY scripts/e2e_assertions.py --base "http://127.0.0.1:$PORT/api" --log "$LOG" \
  --prometheus "http://127.0.0.1:$PORT/api/actuator/prometheus" \
  --output "outputs/e2e-assertions-$STAMP.json"


echo
echo "=== 断言 A6（ADR-55）：输出侧不再误伤正常求助（J1）==="
$PY scripts/probe_l3_stream.py --base "http://127.0.0.1:$PORT/api" --repeat 2 \
  --output "outputs/l3-e2e-$STAMP.json" 2>&1 | grep -E "轮数=|被替换成转介|内容完整|J1 "
echo "  CheckNode L3 blocked 次数 = $(grep -ac 'Final-reply guardrail L3 blocked' "$LOG")"
echo "  流式出站护栏拦截次数   = $(grep -ac '流式出站护栏 L3 拦截' "$LOG")"

echo
echo "=== 用户可见结果抽查（必须不是 5000「AI 服务暂时不可用」）==="
grep -ac "AI 服务暂时不可用" "$LOG" | sed 's/^/  出现次数（含配置文案）: /'
grep -a "AI 服务暂时不可用" "$LOG" | tail -3

kill_port "$PORT"
echo
echo "DONE_PHASE22_E2E STAMP=$STAMP LOG=$LOG"
#
# ⚠️ 命名提醒：本脚本是从 phase21 的 E2E 逐步累加而来的，**当前包含 phase21~26 的全部断言**：
#   A/A2（启动横幅，本脚本内联）· A6 输出侧不误伤（probe_l3_stream.py）
#   · **A3 scope 措辞 / B provider 列表 / D 运行期指标 / A4 JEV shadow / A5 输入侧未放松（10 用例）/ E 应用层 ERROR**
#     自 ADR-68 起**移到 `scripts/e2e_assertions.py`**（CI 与本地同一实现），本脚本只负责引导与内联的 A/A2。
#   ⚠️ 改断言前先确认它属于哪个 phase，**且改一处要想到 CI 也会跑到它**。
