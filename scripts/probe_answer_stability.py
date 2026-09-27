#!/usr/bin/env python3
"""答案稳定性探针：同一问题连发 N 次，量输出的长度与内容差异。

为什么需要它（ADR-48 现场触发）：
  同一个问题「我最近总是失眠，怎么调整？」在真实链路上跑两次，
  一次 395 字给出完整建议，一次 119 字直接回「我不了解，不能给你可靠建议」。
  两次都 success=True、errors=[]、provider=primary、outcome=success
  → **所有现有自动化都不会有一条变红**，但用户体验天差地别。

  这跟 ADR-41/45 的教训同形：指标合格 ≠ 用户拿到的东西合格。
  单次观察不能下结论（可能只是随机），所以必须连发多轮看**分布**。

用法：
  python scripts/probe_answer_stability.py --base http://127.0.0.1:PORT/api \
      --repeat 5 --question "我最近总是失眠，怎么调整？"

输出：每轮的字数/耗时/是否含实质建议（长度阈值只是粗筛，最终要人眼看片段），
     以及去重后的不同回答条数 —— 报 n 时必须报去重后文本数，不是轮数。
"""
import argparse
import json
import statistics
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import register, sse  # noqa: E402

# 粗筛阈值：低于这个字数大概率是"拒答/兜底话术"而非实质回答。
# ⚠️ 这是**启发式**，只用来把可疑样本挑出来给人看，不作为判定结论。
MIN_SUBSTANTIVE = 150
# 明显的"我不了解 / 无法回答"信号
REFUSAL_MARKERS = ("不了解", "无法提供", "不能给你", "没有足够信息", "建议尽快咨询", "无法回答")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--question", default="我最近总是失眠，怎么调整？")
    ap.add_argument("--output")
    args = ap.parse_args()

    user, token = register(args.base)
    rows = []
    for i in range(args.repeat):
        cid = "stab_" + uuid.uuid4().hex
        t0 = time.time()
        try:
            r = sse(args.base, "/Love_app/chat/sse", {"prompt": args.question, "chatId": cid}, token)
            text = r["text"]
            rows.append({
                "round": i + 1,
                "ok": r["success"],
                "chars": len(text),
                "seconds": round(time.time() - t0, 1),
                "ttft_ms": r.get("ttft_ms"),
                "errors": r["errors"],
                "refusal_signals": [m for m in REFUSAL_MARKERS if m in text],
                "text": text,
            })
            print(f"  第{i+1}轮  {rows[-1]['seconds']:>5}s  {len(text):>4}字  "
                  f"ok={r['success']}  拒答信号={rows[-1]['refusal_signals']}", flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"  第{i+1}轮  异常：{type(e).__name__}: {e}", flush=True)
            rows.append({"round": i + 1, "ok": False, "chars": 0, "seconds": round(time.time() - t0, 1),
                         "ttft_ms": None, "errors": [f"{type(e).__name__}: {e}"], "refusal_signals": [], "text": ""})

    ok_rows = [r for r in rows if r["ok"]]
    uniq = {r["text"] for r in ok_rows}
    lens = [r["chars"] for r in ok_rows]
    thin = [r for r in ok_rows if r["chars"] < MIN_SUBSTANTIVE]
    refused = [r for r in ok_rows if r["refusal_signals"]]

    print()
    print(f"  轮数={len(rows)}  成功={len(ok_rows)}  **去重后不同回答={len(uniq)}**")
    if lens:
        print(f"  长度 min/中位/max = {min(lens)}/{int(statistics.median(lens))}/{max(lens)}"
              f"   标准差={statistics.pstdev(lens):.1f}" if len(lens) > 1 else
              f"  长度 = {lens[0]}")
        print(f"  ⚠️ 疑似非实质回答（<{MIN_SUBSTANTIVE}字）：{len(thin)}/{len(ok_rows)}")
        print(f"  ⚠️ 含拒答信号：{len(refused)}/{len(ok_rows)}")
        ratio = max(lens) / max(1, min(lens))
        print(f"  ⚠️ 最长/最短 = {ratio:.1f} 倍" + ("（**离散度大，说明输出不稳定**）" if ratio >= 2 else ""))
    print()
    print("  ---- 各轮原文（供人工判读，别只看数字）----")
    for r in ok_rows:
        print(f"  [{r['round']}] {r['chars']}字: {r['text'][:220]}")
    print("  ---- 原文结束 ----")

    if args.output:
        Path(args.output).write_text(json.dumps(
            {"question": args.question, "repeat": args.repeat,
             "unique_answers": len(uniq), "rows": rows}, ensure_ascii=False, indent=2),
            encoding="utf-8")
        print(f"\n  已写入 {args.output}")


if __name__ == "__main__":
    main()
