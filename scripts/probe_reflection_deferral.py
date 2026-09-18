"""任务 3：触发并验证反思任务的"容量让路"分支。

设计（闸门 gate=1，wait-ms=0 → 超额立即 4003）：
  ① 先用假上游造一个会话（假上游 slow 模式，延迟短，能正常返回）→ 写入 message 表
  ② 等它空闲达阈值（extract-delay-seconds=30）成为反思候选
  ③ 再发一个"占位"请求把 gate=1 的唯一许可占住（假上游 hang 模式 / attempt-timeout 很长）
  ④ 反思调度触发 → 调 LlmGateway → tryAcquire 失败 → 4003
  ⑤ 断言日志出现 WARN 单行 `deferred: LLM gateway at capacity`，且没有 ERROR 全栈

⚠️ 放在 `scripts/` 而非 `logs/`：`logs/` 被 gitignore，放那里实验无法复现。
   配套：`scripts/fake_llm.py`（含 hang 模式）、`scripts/run_reflection_defer.sh`。
   实测记录见 `docs/09-测试策略.md` §8.11。
"""
import json
import os
import threading
import time
import urllib.parse
import urllib.request

BASE = os.environ["BASE"]
APP_LOG = os.environ.get("APP_LOG", "logs/app-reflect-defer.log")


def post(path, body, token=None, timeout=30):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=headers)
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def login(idx):
    u = "rd_%d_%d" % (idx, int(time.time()))
    post("/auth/register", {"username": u, "password": "Passw0rd!123"})
    r = post("/auth/login", {"username": u, "password": "Passw0rd!123"})
    return r.get("token") or r.get("data", {}).get("token")


def read_log():
    try:
        return open(APP_LOG, encoding="utf-8", errors="replace").read()
    except Exception:
        return ""


tokens = [t for t in (login(i) for i in range(3)) if t]
print("用户 token: %d 个" % len(tokens))


def sse(chat_id, prompt, token, sink=None):
    url = BASE + "/Love_app/chat/sse?" + urllib.parse.urlencode({"prompt": prompt, "chatId": chat_id})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token, "Accept": "text/event-stream"})
    try:
        body = urllib.request.urlopen(req, timeout=300).read().decode("utf-8", "replace")
    except Exception as e:
        body = "EXC " + str(e)[:120]
    if sink is not None:
        sink.append(body)
    return body


# ---------- ① 占位：把 gate 唯一的许可占住 ----------
# 用假上游 hang 模式（requests 永不返回）→ 占位请求持续持有并发许可，
# 直到 attempt-timeout 把它掐掉。slow 模式会在 DELAY 后成功返回，占不住。
print("① 发占位请求占住 gate（假上游 hang 模式）...")
holder = []
threading.Thread(target=sse, args=("hold_%d" % int(time.time()), "占位", tokens[0], holder), daemon=True).start()
time.sleep(8)

# ---------- ④ 等反思调度触发 ----------
print("④ 观察反思调度（每次扫描 15s，观察 150s）...")
for remaining in range(150, 0, -30):
    time.sleep(30)
    log = read_log()
    scans = log.count("Reflection scan:")
    deferred = log.count("deferred: LLM gateway at capacity")
    failed = log.count("Failed to reflect session")
    print("   剩余 %3ds | 扫描轮次 %d | 让路(WARN) %d | ERROR 全栈 %d"
          % (remaining - 30, scans, deferred, failed))

log = read_log()
scans = log.count("Reflection scan:")
import re
ready = re.findall(r"Reflection scan: (\d+) session", log)
deferred = log.count("deferred: LLM gateway at capacity")
failed = log.count("Failed to reflect session")
no_valid = log.count("No valid skills reflected from session")

print()
print("日志统计：扫描轮次=%d（候选数 %s）| 容量让路 WARN=%d | ERROR 全栈=%d | 正常落定=%d"
      % (scans, ready, deferred, failed, no_valid))

checks = [
    ("反思调度确实运行过（扫描轮次 >= 1）", scans >= 1),
    ("会话成为反思候选（扫描命中 >= 1 个 session）", any(int(x) > 0 for x in ready)),
    ("出现容量让路 WARN 单行（反思撞上网关 4003）", deferred >= 1),
    ("未落入 ERROR 全栈（新分级生效）", failed == 0),
]
print()
allpass = True
for name, passed in checks:
    print("  %s %s" % ("PASS" if passed else "FAIL", name))
    allpass = allpass and passed
print()
print("REFLECTION_DEFERRAL=%s" % ("PASS" if allpass else "FAIL"))

with open("outputs/adr31-reflection-deferral.json", "w", encoding="utf-8") as f:
    f.write(json.dumps({
        "scans": scans, "ready_counts": ready, "deferred_warn": deferred,
        "error_stacktraces": failed, "no_valid_reflected": no_valid,
        "checks": [{"name": n, "passed": p} for n, p in checks],
    }, ensure_ascii=False, indent=2))

# 让占位请求自然中断（应用会被脚本停掉）
print("(占位请求仍在挂起，交由收尾步骤结束)")
