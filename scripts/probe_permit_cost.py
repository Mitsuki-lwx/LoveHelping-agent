"""量化 ADC（permit 覆盖整条链路）的收益与代价。

方法：**持续请求流**（不是瞬时突发——突发下所有人都在入口被同时判死，看不出差异）。
在固定闸门 G 下持续发请求，比较：
  ① 假上游侧看到的最大并发（厂商侧真实在途）—— 改造前应 > G，改造后应 <= G
  ② 应用侧各类结果的分布（入口 4003 / 上游 5000 / 成功）
  ③ 代价：入口 4003 的绝对数量（改造后应上升——重试期间不再让出许可）

⚠️ **必须在基线前调用假上游的 `/reset`**。`fake_stats.max` 是**进程内累计最大值**，
   不重置的话先跑的用例把 max 顶高、后跑的用例结构上不可能超过它，对照直接失效。
   （2026-09-18 第一次实验就是这么报废的：两份产物 max 都是 4、`max_at` 完全相同。）
   本脚本在 `before_f` 快照前 reset，并把 reset 回执写进产物，便于事后核对确实重置过。

环境变量：
  BASE        应用 API 根（必填，如 http://127.0.0.1:8088/api）
  FAKE_STATS  假上游 stats 地址（默认 http://127.0.0.1:19080/stats）
  RATE_S      发送间隔秒（默认 0.35）
  DURATION    持续秒数（默认 24）
  USERS       预注册用户数（默认 8）
  GATE        闸门值，仅用于判据与产物记录（默认 4）
  LABEL       用例标签，写进产物（before/after）
  OUT         产物路径（默认 outputs/adr31-permit-cost.json）
"""
import collections
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ["BASE"]
FAKE_STATS = os.environ.get("FAKE_STATS", "http://127.0.0.1:19080/stats")
RATE_S = float(os.environ.get("RATE_S", "0.35"))
DURATION = float(os.environ.get("DURATION", "24"))
USERS = int(os.environ.get("USERS", "8"))
GATE = int(os.environ.get("GATE", "4"))
LABEL = os.environ.get("LABEL", "unlabeled")


def post(path, body, token=None, timeout=30):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=headers)
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


def classify(body, status=None):
    """把响应归类。注意业务错误码可能包在 HTTP 200 的 body 里。"""
    if "4003" in body or "AI 服务繁忙" in body or "当前咨询较多" in body:
        return "gate_4003"
    if "5000" in body or "AI 服务暂时不可用" in body:
        return "upstream_5000"
    if status is not None:
        return "http_%d" % status
    if "event:error" in body:
        return "error_event"
    return "ok"


def login_one(idx):
    u = "pc_%d_%d" % (idx, int(time.time()))
    post("/auth/register", {"username": u, "password": "Passw0rd!123"})
    r = post("/auth/login", {"username": u, "password": "Passw0rd!123"})
    return r.get("token") or r.get("data", {}).get("token")


tokens = [t for t in (login_one(i) for i in range(USERS)) if t]
print("已准备 %d 个用户 token" % len(tokens))


def fake_stats():
    try:
        return json.loads(urllib.request.urlopen(FAKE_STATS, timeout=10).read())
    except Exception as e:
        return {"error": str(e)}


def fake_reset():
    """清零假上游计数器；返回回执（含清零前的快照）。"""
    reset_url = FAKE_STATS.rsplit("/", 1)[0] + "/reset"
    try:
        return json.loads(urllib.request.urlopen(reset_url, timeout=10).read())
    except Exception as e:
        raise SystemExit("假上游 /reset 失败（%s）：对照实验无法进行，拒绝产出无效数据。%s" % (reset_url, e))


def metrics():
    raw = urllib.request.urlopen(BASE + "/actuator/prometheus", timeout=20).read().decode()
    out = {}
    for line in raw.splitlines():
        if line.startswith("llm_call_total"):
            k = re.search(r'provider="([^"]+)".*outcome="([^"]+)"', line)
            if k:
                out["llm.call.%s.%s" % (k.group(1), k.group(2))] = float(line.rsplit(" ", 1)[1])
        elif line.startswith("online_inflight_rejected_total"):
            out["gate.rejected"] = float(line.rsplit(" ", 1)[1])
        elif line.startswith("online_inflight_entered_total"):
            out["gate.entered"] = float(line.rsplit(" ", 1)[1])
    return out


reset_ack = fake_reset()
before_m = metrics()
before_f = fake_stats()
print("已重置假上游：%s" % json.dumps(reset_ack, ensure_ascii=False))
print("基线: 闸门 entered=%s rejected=%s | 假上游 current=%s max=%s total=%s"
      % (before_m.get("gate.entered"), before_m.get("gate.rejected"),
         before_f.get("current"), before_f.get("max"), before_f.get("total")))
if before_f.get("max") or before_f.get("total"):
    raise SystemExit("假上游未真正清零（max=%s total=%s），拒绝产出无效数据。"
                     % (before_f.get("max"), before_f.get("total")))

results = []
lock = threading.Lock()
stop = threading.Event()


def fire(i):
    tok = tokens[i % len(tokens)]
    url = BASE + "/Love_app/chat/sse?" + urllib.parse.urlencode(
        {"prompt": "你好", "chatId": "pc_%d_%d" % (i, int(time.time() * 1000))})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + tok, "Accept": "text/event-stream"})
    kind = "other"
    t0 = time.time()
    try:
        body = urllib.request.urlopen(req, timeout=90).read().decode("utf-8", "replace")
        kind = classify(body)
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:
            body = ""
        kind = classify(body, status=e.code)
    except Exception as e:
        kind = "exc_" + type(e).__name__
    with lock:
        results.append((kind, time.time() - t0))


# ---------- 网关争抢：后台任务走网关但**不走准入**，故能抢走用户请求的网关许可 ----------
# ADR-31 建议 3 的收益（"一次请求 = 恰好一次 tryAcquire"）只有在网关被后台任务争抢时才可观测。
# /insight/analyze 经网关（ADR-31 建议 4）但不经 OnlineLoadTracker.admit()（仅 ChatEntry 走），
# 正是理想的争抢源。
CONTENTION = int(os.environ.get("CONTENTION", "0"))
contention_results = []


def contention_loop(idx):
    tok = tokens[idx % len(tokens)]
    while not stop.is_set():
        t0 = time.time()
        kind = "other"
        try:
            post("/insight/analyze",
                 {"conversation": "我：在吗\n她：在的\n我：周末有空吗", "sourceType": "PASTE"},
                 token=tok, timeout=60)
            kind = "ok"
        except urllib.error.HTTPError as e:
            try:
                body = e.read().decode("utf-8", "replace")
            except Exception:
                body = ""
            kind = classify(body, status=e.code)
        except Exception as e:
            kind = "exc_" + type(e).__name__
        with lock:
            contention_results.append((kind, time.time() - t0))
        time.sleep(0.05)


def driver():
    i = 0
    t_end = time.time() + DURATION
    while not stop.is_set() and time.time() < t_end:
        t = threading.Thread(target=fire, args=(i,), daemon=True)
        t.start()
        i += 1
        time.sleep(RATE_S)
    print("驱动结束，共发出 %d 个请求" % i)


if CONTENTION:
    print("启动 %d 个网关争抢线程（/insight/analyze，走网关不走准入）" % CONTENTION)
    for k in range(CONTENTION):
        threading.Thread(target=contention_loop, args=(k,), daemon=True).start()
    time.sleep(2)

d = threading.Thread(target=driver)
d.start()
d.join()
time.sleep(3)  # 等在途请求收尾

stop.set()
time.sleep(1)
after_m = metrics()
after_f = fake_stats()

dist = collections.Counter(k for k, _ in results)
lat = sorted(dt for _, dt in results)
c_dist = collections.Counter(k for k, _ in contention_results)
print()
print("结果分布（用户请求）: %s" % dict(dist))
if lat:
    print("请求耗时: 中位 %.1fs  最大 %.1fs" % (lat[len(lat) // 2], lat[-1]))
if CONTENTION:
    print("结果分布（争抢后台）: %s" % dict(c_dist))
    print("  → 后台被 4003 挡掉 %d 次 = 用户流量优先（ADR-31 建议 3 的预期行为）"
          % c_dist.get("gate_4003", 0))
print("假上游（本轮）: max 并发 = %s | total = %s | current = %s"
      % (after_f.get("max"), after_f.get("total"), after_f.get("current")))
print("闸门: entered %s -> %s | rejected %s -> %s"
      % (before_m.get("gate.entered"), after_m.get("gate.entered"),
         before_m.get("gate.rejected"), after_m.get("gate.rejected")))

peak = after_f.get("max") or 0
out = {
    "label": LABEL,
    "gate": GATE,
    "contention": CONTENTION,
    "reset_ack": reset_ack,
    "distribution": dict(dist),
    "contention_distribution": dict(c_dist),
    "fake_stats": after_f,
    "gate_entered": after_m.get("gate.entered"),
    "gate_rejected": after_m.get("gate.rejected"),
    "requests_fired": sum(dist.values()),
    "latency_median_s": round(lat[len(lat) // 2], 2) if lat else None,
    "latency_max_s": round(lat[-1], 2) if lat else None,
}
print()
print("判据 A：假上游峰值 <= 闸门(%d)  → %s（实测 %d）"
      % (GATE, "PASS" if peak <= GATE else "FAIL(超出 %d)" % (peak - GATE), peak))
with open(os.environ.get("OUT", "outputs/adr31-permit-cost.json"), "w", encoding="utf-8") as f:
    f.write(json.dumps(out, ensure_ascii=False, indent=2))
