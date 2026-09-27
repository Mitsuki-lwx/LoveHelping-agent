"""探测上游 chat 端点的输入长度上限：prompt_tokens 是否被截断。

背景：probe_prompt_cache.py 两次不同请求的 prompt_tokens 都恰好 = 4096。
4096 是 2 的幂，典型的硬上限。本脚本用递增的中英文填充测真实 token 增长曲线：
  - 若 prompt_tokens 随输入增长且能超过 4096 → 之前两次都是巧合/或确实触顶
  - 若在 4096 处平掉 → 上游硬截断，多余内容根本到不了模型
不打印密钥，不写业务数据。
"""
import json, os, re, sys, time, urllib.request

# 自读本地配置：沙箱里 eval 注入的环境变量传不进子进程（已在 probe_prompt_cache.py 上踩过）
LOCAL_YML = "target/classes/application-local.yml"


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

if not KEY or not KEY.strip() or KEY.strip().startswith("${"):
    print("FATAL: OPENAI_API_KEY 未注入且本地配置里也没读到", file=sys.stderr)
    sys.exit(2)


def call(n_chars, tag):
    # 用中文填充：中文 1 字 ≈ 0.6~1 token，便于对照
    filler = "测试内容" * (n_chars // 4)
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": filler},
            {"role": "user", "content": "只回复“ok”两个字。"},
        ],
        "stream": False,
        "max_tokens": 5,
    }).encode("utf-8")
    req = urllib.request.Request(
        BASE + "/chat/completions", data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            raw = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        print(f"chars={n_chars:>7}  HTTP {e.code}  {detail}")
        return None
    dt = time.time() - t0
    u = raw.get("usage") or {}
    pt = u.get("prompt_tokens")
    txt = ""
    try:
        txt = (raw["choices"][0]["message"].get("content") or "")[:20]
    except Exception:
        pass
    print(f"chars={n_chars:>7}  prompt_tokens={pt!s:>7}  {dt:5.2f}s  reply={txt!r}")
    return pt


print(f"endpoint={BASE}  model={MODEL}\n")
print("--- 递增输入，看 prompt_tokens 是否线性增长 ---")
pts = []
for n in (500, 2000, 8000, 16000, 32000, 64000, 128000):
    p = call(n, f"n={n}")
    if p is not None:
        pts.append((n, p))
    time.sleep(0.5)

print("\n--- 增长曲线 ---")
prev = None
for n, p in pts:
    delta = "" if prev is None else f"  +{p - prev}"
    print(f"chars={n:>7} -> tokens={p:>7}{delta}")
    prev = p

if len(pts) >= 2:
    cap = max(p for _, p in pts)
    capped = [n for n, p in pts if p == cap]
    if len(capped) > 1:
        print(f"\n⚠️ 疑似截断：{len(capped)} 个不同输入长度都返回 prompt_tokens={cap}")
        print("   → 多余内容到不了模型（若该值恰为 2 的幂，几乎可确定是硬上限）")
    else:
        print(f"\n未观察到封顶：prompt_tokens 随输入单调增长（最大 {cap}）")
