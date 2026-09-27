#!/usr/bin/env python3
"""机制对照实验：什么样的 prompt 变动会打断 OpenRouter 的 prompt cache 前缀。

为什么单独做这个实验（而不是继续在应用里猜）：
  实测真实业务链路 6 轮，命中值落在**两个离散点** 141 / 1180，不是连续下滑。
  SYSTEM_PROMPT 粗估 ~1060 token ≈ 高命中值 1180。
  → 假设：1180 = 完整静态前缀被命中；141 = 前缀在**极早期**就断了。
  但 141 太小，装不下 SYSTEM_PROMPT，所以断裂点比"尾部追加"更靠前。
  **这个假设必须用对照实验证伪，不能靠读代码推断** ——
  与 ADR-46/47 同一条纪律：结构上说得通 ≠ 载荷真的那样。

四组对照，每组连发 2 次（第一次必然冷启动，第二次才是稳态）：
  A 基线     ：完全相同的 prompt 连发
  B 尾部追加 ：SYSTEM_PROMPT + 一段随机变化的动态文本（模拟 memory/skill/RAG 注入）
  C 头部变化 ：在 SYSTEM_PROMPT **前面**加一段变化文本
  D 消息历史 ：system 不变，user 消息不同（模拟不同用户提问）

⚠️ 关键纪律：每组必须**先跑一次预热再取第二次**的结果，
   否则测的是冷启动，A 组也会是 0 → 会被误读成"缓存没生效"。
"""
import json
import os
import sys
import time
import urllib.request

BASE = os.environ.get("OR_BASE", "https://openrouter.ai/api/v1/chat/completions")
KEY = os.environ.get("OR_KEY", "")
MODEL = os.environ.get("OR_MODEL", "stealth/space-bunny-alpha")

# ⚠️ 第一版用 ~591 token 的样本，四组命中全是 138~140（**地板值**），
#    于是"尾部/头部追加会不会断前缀"这个问题根本没被测到 ——
#    样本太短，短到只有那个固定地板前缀可命中，**任何**变动都落在地板之后。
#    这是量具设计错误：测"前缀缓存"必须让静态前缀**长到超过缓存门槛**。
#    真实链路 SYSTEM_PROMPT ≈1060 token，高命中档实测 1180。
#    所以这里的长度按真实链路取，并把长度扫开看门槛在哪。
STATIC = (
    "你是一个恋爱与关系顾问，语气温和、具体、可执行。\n"
    "回答结构：先共情对方的处境，再给出可操作的下一步，最后说明可能的阻力。\n"
) + ("当用户描述一段具体的相处困境时，你要指出其中被忽略的信号，"
     "并说明这些信号为什么值得关注，以及可以如何验证自己的猜测。\n" * 14)


def sized(n_units):
    """按真实 prompt 的密度生成指定长度的静态前缀（用于扫长度门槛）。"""
    return STATIC + ("【补充背景知识条目】" + "亲密关系中的沟通模式与依恋类型存在个体差异。" * 9 + "\n") * n_units


# 照抄真实 assembleContext 的形态：\n\n【段名】\n + 条目行
MEM = ("\n\n【已学经验】\n- 冷战破冰: 先承认对方感受再谈事实，不要在气头上追问"
       "- 需求表达: 用观察加感受代替指责\n"
       "\n\n【上次说要做的事】\n- 约对方周末去看展，等他答复\n")


def call(prompt, tag):
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "system", "content": prompt[0]},
                     {"role": "user", "content": prompt[1]}],
        "max_tokens": 8,
    }).encode()
    req = urllib.request.Request(BASE, data=body, headers={
        "Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=120) as r:
        data = json.loads(r.read().decode("utf-8", "replace"))
    u = data.get("usage") or {}
    d = u.get("prompt_tokens_details") or {}
    return {"tag": tag, "prompt_tokens": u.get("prompt_tokens"),
            "cached": d.get("cached_tokens"), "sec": round(time.time() - t0, 1)}


def show(r):
    c = r["cached"]
    p = r["prompt_tokens"]
    ratio = f"{c / p:.1%}" if c and p else "n/a"
    return f"  {r['tag']:<28} prompt={p:<6} cached={str(c):<6} 命中={ratio:<7} {r['sec']}s"


def main():
    if not KEY:
        sys.exit("缺 OR_KEY 环境变量")
    dyn_tail = "【当前用户状态】" + "他最近工作压力大，沟通变少。 " * 8
    # len(尾部动态段) ≈ n_units 越大，system 越长 —— 扫长度找门槛
    groups = [
        ("A 基线·完全相同",      lambda i: (STATIC, f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("B 尾部追加动态段",     lambda i: (STATIC + dyn_tail + f"\n（轮次标记 {i}）",
                                            f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("C 头部插入动态段",     lambda i: (f"【轮次标记 {i}】\n" + STATIC,
                                            f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("D system不变/user变",  lambda i: (STATIC,
                                            f"第{i}轮：" + "我和对象吵架后冷战三天了，该先开口吗？" * 2)),
        # 扫长度：静态前缀越长，命中越多 → 找"缓存在多长以上才真正生效"的门槛
        ("E 长前缀·完全相同",    lambda i: (sized(6), f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("F 长前缀·尾部追加",    lambda i: (sized(6) + dyn_tail + f"\n（轮次标记 {i}）",
                                            f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("G 超长前缀·尾部追加",  lambda i: (sized(20) + dyn_tail + f"\n（轮次标记 {i}）",
                                            f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("H 超长前缀·user变",    lambda i: (sized(20),
                                            f"第{i}轮：" + "他父母反对我们在一起，说我配不上他。" * 3)),
        # ⭐ 真实链路模拟：assembleContext（memory/skill/actionItem）**命中与否是二元的**
        #   —— 有则整段进、没则空串。上一版发现 G/H 只差尾部却 41% vs 98%，
        #   提示上游按内容分块、尾部一小段能顶掉整块。这里直接照抄真实结构测。
        ("I 尾部为空(无注入)",   lambda i: (sized(6),
                                            f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("J 尾部=记忆段(有注入)", lambda i: (sized(6) + MEM,
                                             f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
        ("K 空/有交替",          lambda i: (sized(6) + (MEM if i == 1 else ""),
                                             f"第{i}轮，异地半年了感情变淡，怎么判断还值不值得坚持？")),
    ]
    out = []
    only = os.environ.get("ONLY", "")
    for name, mk in groups:
        # ONLY=IJK 之类可只跑指定组（按组名首字母匹配）。全跑要 ~1.5 分钟/组。
        if only and name[0] not in only:
            continue
        print(f"\n{name}")
        rows = []
        for i in (0, 1, 2):  # i=0 预热；i=1,2 取稳态（**至少两次稳态**，单次可能是偶然）
            try:
                r = call(mk(i), name + ("/预热" if i == 0 else f"/稳态{i}"))
                print(show(r), flush=True)
                rows.append(r)
                out.append(r)
            except Exception as e:  # noqa: BLE001
                print(f"  {name} 第{i}轮 失败：{type(e).__name__}: {e}")
                break
    print("\n结论读法：")
    print("  A 稳态命中高 → 缓存本身有效（前提断言通过）")
    print("  B 若命中骤降 → 尾部追加动态内容会断前缀（那要在 prompt 结构上做文章）")
    print("  C 若命中为 0 → 头部有变化就全废（最坏情况）")
    print("  D 若命中≈A → 只改 user 消息不影响前缀（正常且符合预期）")
    with open("outputs/cache-mechanism.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n已写入 outputs/cache-mechanism.json")


if __name__ == "__main__":
    main()
