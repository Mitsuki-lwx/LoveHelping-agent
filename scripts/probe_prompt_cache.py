"""探测上游 chat 端点是否支持 prompt cache，以及 usage 里是否回报缓存命中字段。

做法：用同一段长静态前缀连发两次（第二次只改末尾一句），比较两次 usage。
不打印任何密钥；不写业务数据。
"""
import json, os, sys, time, urllib.request, urllib.error

BASE = os.environ.get("OPENAI_BASE_URL", "https://open.bigmodel.cn/api/paas/v4").rstrip("/")
KEY = os.environ.get("OPENAI_API_KEY", "")
MODEL = os.environ.get("OPENAI_MODEL", "glm-4-flash")

if not KEY or not KEY.strip() or KEY.strip().startswith("${"):
    print("FATAL: OPENAI_API_KEY 未注入（空或仍是占位符）", file=sys.stderr)
    sys.exit(2)

# 静态前缀：模拟 ChatExecutor.SYSTEM_PROMPT 的形态（长、常量）
STATIC = ("你是一个资深的恋爱与关系心理顾问。请用中文回答，语气温暖但不谄媚。"
          "回答要具体、可执行，避免空泛安慰。") * 120

def call(text, tag):
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": STATIC},
            {"role": "user", "content": text},
        ],
        "stream": False,
    }).encode("utf-8")
    req = urllib.request.Request(
        BASE + "/chat/completions", data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = json.loads(r.read().decode("utf-8"))
    dt = time.time() - t0
    usage = raw.get("usage") or {}
    print(f"[{tag}] {dt:.2f}s usage={json.dumps(usage, ensure_ascii=False)}")
    return usage, raw

print(f"endpoint={BASE}  model={MODEL}")
print(f"static_prefix_chars={len(STATIC)} (~{len(STATIC)//2} tokens 量级)\n")

u1, r1 = call("第一次：请解释什么是煤气灯效应。", "call#1")
time.sleep(1)
u2, r2 = call("第二次：请解释什么是依恋风格。", "call#2  # 前缀相同，末尾不同")

print("\n--- usage 字段全集 ---")
print("call#1 keys:", sorted(u1.keys()))
print("call#2 keys:", sorted(u2.keys()))

cache_fields = [k for k in set(u1) | set(u2)
                if any(t in k.lower() for t in ("cache", "cached"))]
print("cache 相关字段:", cache_fields or "（无 —— 该端点不回报缓存命中）")

p1 = u1.get("prompt_tokens") or u1.get("input_tokens")
p2 = u2.get("prompt_tokens") or u2.get("input_tokens")
print(f"prompt_tokens: call#1={p1} call#2={p2} 增量={None if (p1 is None or p2 is None) else p2-p1}")
