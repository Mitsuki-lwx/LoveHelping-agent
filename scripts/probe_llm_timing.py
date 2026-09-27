"""两件事一次测清：

1. 4096 是不是硬截断？——用 probe_prompt_cache.py 里那段**同样的**静态前缀，
   再跑一个 2 倍版本，看 prompt_tokens 是否跟着翻倍。
   （上一轮 growth 脚本用"测试内容"填充，0.5 token/字，与那段中文散文的分词率不同，
     所以两次数字不可直接比较——这次用同一段文本做对照。）

2. ⭐ 输出生成速率，以及 attempt-timeout-ms=25000 到底够不够用。
   这是本轮真正要紧的问题：若正常长度回答的生成时间 > 25s，
   每次这样的请求都会超时 → 重试 → 降级 → 用户拿到降级文案。

不打印密钥，不写业务数据。
"""
import json, os, re, sys, time, urllib.request

LOCAL_YML = "target/classes/application-local.yml"
ATTEMPT_TIMEOUT_MS = 25000   # app.llm.attempt-timeout-ms 的当前默认值


def from_local_yaml(name):
    try:
        text = open(LOCAL_YML, encoding="utf-8").read()
    except OSError:
        return ""
    m = re.search(rf"^\s*{name}:\s*(\S+)\s*$", text, re.M)
    return m.group(1).strip("'\"") if m else ""


BASE = (os.environ.get("OPENAI_BASE_URL") or from_local_yaml("base-url")
        or "https://open.bigmodel.cn/api/paas/v4").rstrip("/")
KEY = os.environ.get("OPENAI_API_KEY") or from_local_yaml("api-key")
MODEL = os.environ.get("OPENAI_MODEL") or from_local_yaml("model") or "glm-4-flash"

# 与 probe_prompt_cache.py 完全相同的静态前缀
UNIT = ("你是一个资深的恋爱与关系心理顾问。请用中文回答，语气温暖但不谄媚。"
        "回答要具体、可执行，避免空泛安慰。")


def call(system, user, tag, max_tokens=None, want_stream_timing=False):
    payload = {"model": MODEL,
               "messages": [{"role": "system", "content": system},
                            {"role": "user", "content": user}],
               "stream": False}
    if max_tokens:
        payload["max_tokens"] = max_tokens
    req = urllib.request.Request(
        BASE + "/chat/completions", data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            raw = json.loads(r.read().decode("utf-8"))
    except Exception as e:
        print(f"[{tag}] FAILED after {time.time()-t0:.1f}s: {type(e).__name__}: {e}")
        return None
    dt = time.time() - t0
    u = raw.get("usage") or {}
    pt, ct = u.get("prompt_tokens"), u.get("completion_tokens")
    rate = (ct / dt) if (ct and dt) else 0
    over = " ⚠️ 超过 attempt-timeout!" if dt * 1000 > ATTEMPT_TIMEOUT_MS else ""
    print(f"[{tag}] {dt:6.2f}s  prompt={pt}  completion={ct}  "
          f"生成速率={rate:5.1f} tok/s{over}")
    return {"seconds": dt, "prompt": pt, "completion": ct, "rate": rate}


print(f"endpoint={BASE}  model={MODEL}")
print(f"attempt-timeout-ms = {ATTEMPT_TIMEOUT_MS}ms\n")

print("=== ① 4096 是不是硬截断（同段前缀 ×1 / ×2 对照）===")
one = UNIT * 120
r1 = call(one, "请解释什么是煤气灯效应。", "1x")
r2 = call(UNIT * 240, "请解释什么是依恋风格。", "2x")
if r1 and r2:
    print(f"\n前缀翻倍后 prompt_tokens: {r1['prompt']} → {r2['prompt']} "
          f"(×{r2['prompt'] / r1['prompt']:.2f})")
    if r2["prompt"] > r1["prompt"] * 1.5:
        print("→ 未截断。4096 只是那段文本恰好这么多 token，不是上限。")
    else:
        print("→ ⚠️ 疑似封顶，需进一步查。")

print("\n=== ② 输出生成速率 vs attempt-timeout（关掉 max_tokens，用自然回答长度）===")
Q = "我和男朋友吵架三天了，他不说话，我该主动找他吗？请具体说说怎么做。"
res = []
for i in range(3):
    r = call(one, Q, f"真实回答#{i+1}")
    if r:
        res.append(r)

if res:
    avg_rate = sum(r["rate"] for r in res) / len(res)
    avg_sec = sum(r["seconds"] for r in res) / len(res)
    avg_ct = sum(r["completion"] for r in res) / len(res)
    print(f"\n--- 汇总 ---")
    print(f"平均 completion_tokens = {avg_ct:.0f}")
    print(f"平均耗时 = {avg_sec:.2f}s   平均生成速率 = {avg_rate:.1f} tok/s")
    print(f"attempt-timeout = {ATTEMPT_TIMEOUT_MS/1000:.0f}s")
    safe_ct = avg_rate * (ATTEMPT_TIMEOUT_MS / 1000)
    print(f"→ 25s 内最多能生成约 {safe_ct:.0f} completion tokens")
    if avg_ct > safe_ct:
        print(f"⚠️ 本次平均就写了 {avg_ct:.0f} tokens，**超出 25s 预算** → 会超时")
    else:
        print(f"本次平均 {avg_ct:.0f} tokens 在预算内，但余量只有 "
              f"{safe_ct - avg_ct:.0f} tokens（{100*avg_ct/safe_ct:.0f}% 占用）")
    print(f"\n按 {avg_rate:.1f} tok/s 反推：生成 {n} tokens 需要 {n/avg_rate:.1f}s"
          if (n := 500) else "")
    for n in (300, 500, 800, 1200):
        print(f"  {n:>5} tokens → {n/avg_rate:5.1f}s "
              f"{'✗ 超时' if n/avg_rate > ATTEMPT_TIMEOUT_MS/1000 else '✓'}")
