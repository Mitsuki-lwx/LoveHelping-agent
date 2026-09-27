#!/usr/bin/env python3
"""同一 prompt 连发 N 次，看 cache 命中是否在几个离散值之间抖动。

为什么测这个（前面机制实验已经把"结构问题"排除了）：
  probe_cache_mechanism.py 结论：尾部追加动态段、user 消息变化、空/有交替
  **都不影响命中率**（I/J/K 三组均 98~99.8%）。
  → "advisor 注入顺序断了前缀"这个假设**已被证伪**。

  但真实业务链路 6 轮实测命中落在**两个离散点** 141 / 1180，
  且同一轮的 RAG/rerank/路由结构与其他轮完全一致（日志已核对第 5 轮 trace）。
  剩下最合理的解释是：**上游请求被路由到不同后端副本**，
  部分副本没有该前缀的缓存副本 → 同一个 prompt 在不同副本上表现不同。

  验证方法：同一个 prompt（内容完全不变）连发 N 次，
  若命中在 141/1180 之间**来回跳**，就是副本/节点问题，与应用无关；
  若稳定在高位，那 141 就另有原因（需回到应用侧查那两轮的 payload 差异）。

⚠️ 纪律：这不是"测一次就好"——单次采样无法区分
  "稳定的低命中" 和 "抖动的低命中"，必须 N≥12 看分布。
"""
import collections
import json
import os
import sys
import time
import urllib.request

BASE = os.environ.get("OR_BASE", "https://openrouter.ai/api/v1/chat/completions")
KEY = os.environ.get("OR_KEY", "")
MODEL = os.environ.get("OR_MODEL", "stealth/space-bunny-alpha")
N = int(os.environ.get("N", "12"))

# 长度贴近真实 SYSTEM_PROMPT（实测高命中档 1180）
STATIC = (
    "你是一个恋爱与关系顾问，语气温和、具体、可执行。\n"
    "回答结构：先共情对方的处境，再给出可操作的下一步，最后说明可能的阻力。\n"
) + ("当用户描述一段具体的相处困境时，你要指出其中被忽略的信号，"
     "并说明这些信号为什么值得关注，以及可以如何验证自己的猜测。\n" * 14) \
  + ("【补充背景知识条目】" + "亲密关系中的沟通模式与依恋类型存在个体差异。" * 9 + "\n")

MEM = ("\n\n【已学经验】\n- 冷战破冰: 先承认对方感受再谈事实\n"
       "\n\n【上次说要做的事】\n- 约对方周末去看展，等他答复\n")


def call(system, user, tag):
    body = json.dumps({"model": MODEL, "max_tokens": 8, "messages": [
        {"role": "system", "content": system}, {"role": "user", "content": user}]}).encode()
    req = urllib.request.Request(BASE, data=body, headers={
        "Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=120) as r:
        data = json.loads(r.read().decode("utf-8", "replace"))
    u = data.get("usage") or {}
    d = u.get("prompt_tokens_details") or {}
    return {"i": tag, "prompt_tokens": u.get("prompt_tokens"), "cached": d.get("cached_tokens"),
            "sec": round(time.time() - t0, 1)}


def main():
    if not KEY:
        sys.exit("缺 OR_KEY 环境变量")
    user = "我总忍不住翻他手机查记录，我知道这不对但控制不住，怎么办？"
    print(f"同一 prompt（system {len(STATIC)}+{len(MEM)} 字符）连发 {N} 次\n")
    rows = []
    for i in range(N):
        # ⭐ 尾部动态段在"有/无"之间**交替**：模拟 assembleContext 的二元命中，
        #    若命中稳定 → 交替无害（那就彻底排除应用侧因素）
        sysmsg = STATIC + (MEM if i % 2 == 0 else "")
        try:
            r = call(sysmsg, user, i)
            r["mem"] = bool(i % 2 == 0)
            rows.append(r)
            print(f"  #{i+1:<3} mem={'Y' if r['mem'] else 'N'} "
                  f"prompt={r['prompt_tokens']:<6} cached={str(r['cached']):<6} {r['sec']}s", flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"  #{i+1} 失败 {type(e).__name__}: {e}")

    cs = [r["cached"] for r in rows if r["cached"] is not None]
    print()
    if not cs:
        print("  无有效样本")
        return 2
    dist = collections.Counter(cs)
    print(f"  命中值分布（共 {len(cs)} 次）：")
    for v, c in sorted(dist.items(), reverse=True):
        print(f"    cached={v:<7} 出现 {c} 次  ({c/len(cs):.0%})")
    print(f"  min/中位/max = {min(cs)} / {sorted(cs)[len(cs)//2]} / {max(cs)}")
    lo = [c for c in cs if c < 500]
    print()
    if not lo:
        print("  [OK] 全部高位命中 → 尾部有无动态段**完全无害**，应用侧无问题。")
    elif len(lo) == len(cs):
        print("  [BAD] 全部低位 → 该 prompt 前缀在所有后端都缓存不到。")
    else:
        print(f"  [抖动] {len(lo)}/{len(cs)} 次低命中（{100*len(lo)/len(cs):.0f}%）"
              f" → 命中在 {min(lo)}~{max(cs)} 间跳，指向**后端副本差异**（与 prompt 结构无关）。")
    # 分组看 mem 有无是否影响
    y = [r["cached"] for r in rows if r.get("mem") and r["cached"] is not None]
    n = [r["cached"] for r in rows if not r.get("mem") and r["cached"] is not None]
    if y and n:
        print(f"  有记忆段 median={sorted(y)[len(y)//2]}  无记忆段 median={sorted(n)[len(n)//2]}")
    with open("outputs/cache-stability.json", "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print("\n  已写入 outputs/cache-stability.json")


if __name__ == "__main__":
    main()
