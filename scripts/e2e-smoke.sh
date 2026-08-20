#!/usr/bin/env bash
# ============================================================================
# E2E 冒烟测试脚本（docs/09-测试策略.md §7 的脚本化实现）
# 前置：MySQL/Redis/PG 就绪，应用已启动（local profile + 真实 LLM Key）
# 用法：BASE_URL=http://localhost:8088/api ADMIN_API_KEY=xxx ./scripts/e2e-smoke.sh
#       SKIP_AGENT=1 可跳过耗时的 Agent 段（触达面不含 Agent/工具时使用）
# ============================================================================
set -u

BASE_URL="${BASE_URL:-http://localhost:8088/api}"
ADMIN_API_KEY="${ADMIN_API_KEY:-}"
USER="smoke_$(date +%s)"
PASS="Passw0rd!"
TOKEN=""
T2=""
PASS_N=0; FAIL_N=0

ok()   { PASS_N=$((PASS_N+1)); echo "  [PASS] $1"; }
bad()  { FAIL_N=$((FAIL_N+1)); echo "  [FAIL] $1  -> $2"; }
check(){ # check <描述> <期望包含> <实际输出>
  case "$3" in *"$2"*) ok "$1";; *) bad "$1" "期望含[$2] 实际[${3:0:120}]";; esac
}

echo "== 7.1 认证链路 =="
R=$(curl -s -m 15 -X POST "$BASE_URL/auth/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
check "注册成功" '"success":true' "$R"
R=$(curl -s -m 15 -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}")
check "登录成功" '"success":true' "$R"
TOKEN=$(echo "$R" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
[ -n "$TOKEN" ] && ok "取得 Token" || bad "取得 Token" "响应无 token 字段"
R=$(curl -s -m 15 "$BASE_URL/auth/me" -H "Authorization: Bearer $TOKEN")
check "/auth/me 返回当前用户" "$USER" "$R"

echo "== 7.2 SSE 流式聊天 =="
R=$(curl -s -m 15 -G "$BASE_URL/Love_app/chat/sse" --data-urlencode "prompt=hi")
check "缺 chatId 返回 400" '"code":400' "$R"
CHAT_OUT=$(curl -s --max-time 60 -N -G "$BASE_URL/Love_app/chat/sse" \
  --data-urlencode "prompt=hello in 5 words" --data-urlencode "chatId=smoke_$USER"   -H "Authorization: Bearer $TOKEN" | head -c 300)
check "带 chatId 正常流式输出" "data:" "$CHAT_OUT"

echo "== 7.3 RAG 聊天 =="
RAG_OUT=$(curl -s --max-time 90 -N -G "$BASE_URL/Love_app/chat/sse/rag" \
  --data-urlencode "prompt=how to avoid awkward first date chat" --data-urlencode "chatId=smoke_rag_$USER"   -H "Authorization: Bearer $TOKEN" | head -c 300)
check "RAG 流式输出" "data:" "$RAG_OUT"

echo "== 7.4 会话记忆 =="
R=$(curl -s -m 15 -X POST "$BASE_URL/memory/register" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d "{\"conversationId\":\"smoke_rag_$USER\",\"title\":\"smoke\"}")
check "注册会话归属" '"code":200' "$R"
R=$(curl -s -m 15 "$BASE_URL/memory/conversations" -H "Authorization: Bearer $TOKEN")
check "会话列表可见" "smoke_rag_$USER" "$R"
R=$(curl -s -m 15 "$BASE_URL/memory/smoke_rag_$USER" -H "Authorization: Bearer $TOKEN")
check "本人可读会话历史" '"code":200' "$R"

echo "== 7.6 安全负向用例 =="
R=$(curl -s -m 15 "$BASE_URL/tenant/token" 2>/dev/null)
case "$R" in
  *eyJhbGci*) bad "tenant/token 应已删除(ADR-13)" "意外仍可签发 Token";;
  *) ok "/tenant/token 已删除（不再签发 Token）";;
esac
R=$(curl -s -m 15 "$BASE_URL/memory/admin/conversations" -H "Authorization: Bearer $TOKEN")
check "普通用户访问 admin 被拒" '"code":403' "$R"
# 跨用户越权（IDOR）
R=$(curl -s -m 15 -X POST "$BASE_URL/auth/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"${USER}_b\",\"password\":\"$PASS\"}")
T2=$(curl -s -m 15 -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"${USER}_b\",\"password\":\"$PASS\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
R=$(curl -s -m 15 "$BASE_URL/memory/smoke_rag_$USER" -H "Authorization: Bearer $T2")
check "用户B读用户A会话被拒(403)" '"code":403' "$R"
R=$(curl -s -m 15 -X POST "$BASE_URL/memory/register" -H "Authorization: Bearer $T2" \
  -H "Content-Type: application/json" -d "{\"conversationId\":\"smoke_rag_$USER\"}")
check "用户B抢注用户A会话被拒(403)" '"code":403' "$R"

if [ "${SKIP_AGENT:-0}" != "1" ]; then
  echo "== 7.5 Agent（LoveManus）=="
  AGENT_OUT=$(curl -s --max-time 150 -G "$BASE_URL/Love_app/chat/LoveManus" \
    --data-urlencode "message=上海今天适合约会吗？查下天气简单说" \
    --data-urlencode "sessionId=smoke_agent_$USER")
  EVENTS=$(printf '%s' "$AGENT_OUT" | grep -c "^data:")
  if [ "${EVENTS:-0}" -gt 5 ]; then ok "Agent 多步执行（$EVENTS 个事件）"; else bad "Agent 执行" "事件数=$EVENTS"; fi
fi

echo "=========================================="
echo "结果：PASS=$PASS_N  FAIL=$FAIL_N"
[ "$FAIL_N" -eq 0 ] && echo "SMOKE: ALL PASS" || echo "SMOKE: FAILED"
exit $([ "$FAIL_N" -eq 0 ] && echo 0 || echo 1)
