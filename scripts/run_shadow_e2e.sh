#!/bin/bash
# 影子模式真实 E2E（docs/phase7-jev-shadow）：
#   启动应用（JEV_GUARDRAIL_MODE=shadow）→ 走真实 SSE 聊天 → 断言
#   ① 越阈消息**不改变响应**（用户拿到正常回复，不是转介文案）
#   ② DB 里出现 action=SHADOW 且 signal_score 非空的事件
#   ③ 词典兜底未被削弱（真正该拦的仍拦）
# 用法： bash logs/run_shadow_e2e.sh
set -u
cd /d/java/lwx-ai-agent
PY="C:/Users/lwx/.workbuddy/binaries/python/versions/3.13.12/python.exe"
JAVA=/d/jdk/bin/java
CP='D:\apache-maven-3.9.11\boot\plexus-classworlds-2.9.0.jar'
CW='D:\apache-maven-3.9.11\bin\m2.conf'
MVN='D:\apache-maven-3.9.11'
MPD='D:\java\lwx-ai-agent'
MYSQL=/d/mysql/bin/mysql
STAMP=$(date +%H%M%S)
APP_LOG=logs/app-shadow-$STAMP.log
OUT=outputs/shadow-e2e-$STAMP.json

if [ -z "${JEV_KEY:-}" ]; then echo "JEV_KEY 未设置"; exit 1; fi

echo "=== 启动（影子模式）==="
JEV_ENABLED=true JEV_API_KEY="$JEV_KEY" JEV_GUARDRAIL_MODE=shadow \
MCP_SERVER_URL="http://localhost:8300/mcp" ADMIN_API_KEY=lwx-admin-eval-2026 \
$JAVA -classpath "$CP" "-Dclassworlds.conf=$CW" "-Dmaven.home=$MVN" "-Dmaven.multiModuleProjectDirectory=$MPD" \
  org.codehaus.plexus.classworlds.launcher.Launcher -o spring-boot:run > "$APP_LOG" 2>&1 &

PORT=""
for i in $(seq 1 72); do
  PORT=$(grep -o "Tomcat started on port [0-9]*" "$APP_LOG" 2>/dev/null | tail -1 | grep -o "[0-9]*$")
  [ -n "$PORT" ] && break
  grep -q "BUILD FAILURE\|APPLICATION FAILED TO START" "$APP_LOG" 2>/dev/null && { echo "启动失败"; grep -E "ERROR|Caused by" "$APP_LOG" | tail -12; exit 1; }
  sleep 5
done
[ -z "$PORT" ] && { echo "未取到端口"; tail -20 "$APP_LOG"; exit 1; }
echo "端口=$PORT"

echo "=== 迁移 V24 是否应用 ==="
$MYSQL -uroot -p123456 agentdb -e "select version, description, success from flyway_schema_history where version='24';" 2>/dev/null
$MYSQL -uroot -p123456 agentdb -e "show columns from guardrail_event like 'signal%';" 2>/dev/null

echo "=== 真实 SSE（影子模式）==="
$PY scripts/shadow_mode_e2e.py --base "http://127.0.0.1:$PORT/api" --output "$OUT" 2>&1 | tail -10

USER=$( $PY -c "
import json,io
try:
    print(json.load(io.open('$OUT',encoding='utf-8'))['user'])
except Exception: print('')
" )
echo "对应用户=$USER"

echo "=== DB：本次会话的护栏事件 ==="
$MYSQL -uroot -p123456 agentdb -e "
select level, rule_id, action, signal_score, signal_threshold, content_hmac is not null as has_hmac, created_at
from guardrail_event where user_id='$USER' order by id;
" 2>/dev/null

echo "=== 断言 ②：SHADOW 事件存在且概率非空 ==="
CNT=$( $MYSQL -uroot -p123456 -N -B agentdb -e "
select count(*) from guardrail_event
where user_id='$USER' and action='SHADOW' and rule_id='jev:self_harm' and signal_score is not null and signal_threshold=0.6;
" 2>/dev/null )
echo "满足条件的 SHADOW 事件数 = $CNT"
[ "${CNT:-0}" -ge 1 ] && echo "ASSERT_SHADOW_OK" || echo "ASSERT_SHADOW_FAIL"

echo "=== 断言 ③：影子模式未削弱词典兜底（存在 BLOCKED 事件）==="
BLK=$( $MYSQL -uroot -p123456 -N -B agentdb -e "
select count(*) from guardrail_event where user_id='$USER' and action='BLOCKED';
" 2>/dev/null )
echo "BLOCKED 事件数 = $BLK"

echo "=== 结论行 ==="
$PY -c "
import json,io
d=json.load(io.open('$OUT',encoding='utf-8'))
print('SHADOW_E2E passed=%d/%d' % (d['passed'], d['total']))
for r in d['rows']:
    print('  %-22s expect=%-22s blocked=%-5s pass=%s' % (r['id'], r['expect'], r['blocked'], r['pass']))
"

APP_PORT="$PORT" $PY -c "
import os, subprocess
out = subprocess.run(['cmd','/c','netstat -ano | findstr :'+os.environ['APP_PORT']], capture_output=True, errors='replace').stdout or ''
for pid in {l.split()[-1] for l in out.splitlines() if 'LISTENING' in l}:
    subprocess.run(['taskkill','/F','/PID',pid], capture_output=True)
print('app stopped')
"
echo "DONE_SHADOW_E2E STAMP=$STAMP LOG=$APP_LOG"
