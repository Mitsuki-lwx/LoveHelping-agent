#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线重打分：judge 坏了不必重跑应用。

2026-09-27 实测：路由前提 A/B 跑完两臂各 16 例 ×2 轮，结果全是 0.00 ——
原因是 `answer_eval.py` 的 judge 拿到了 OpenRouter key 调智谱 → HTTP 401。
但**原始回答已经落在 outputs/*.json 里**，而重新打分只需 judge，不需要应用。

用法：
  python scripts/rescore_saved_answers.py outputs/routing-premise-A-spacebunny-134434.json \
      --out outputs/rescored-A.json
"""
import argparse
import importlib.util
import io
import json
import os
import statistics
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_answer_eval():
    """复用 answer_eval 的 judge 提示词与解析，避免两处口径漂移。"""
    path = os.path.join(ROOT, "scripts", "answer_eval.py")
    spec = importlib.util.spec_from_file_location("answer_eval", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("infile")
    ap.add_argument("--out")
    a = ap.parse_args()

    mod = load_answer_eval()
    key = mod.load_zhipu_key()
    data = json.load(io.open(a.infile, encoding="utf-8"))

    scored, failed = [], 0
    for case in data["report"]:
        for rnd in case["rounds"]:
            if not rnd.get("answer"):
                continue
            try:
                score, reason = mod.judge(key, case["question"], case["golden"], rnd["answer"])
            except Exception as e:  # noqa: BLE001 —— judge 失败必须留痕，不能当 0 分
                failed += 1
                print("  judge 失败 %s r%s: %s" % (case["id"], rnd["round"], e), file=sys.stderr)
                continue
            rnd["score"] = score
            rnd["reason"] = reason
        vals = [r["score"] for r in case["rounds"] if r.get("answer")]
        case["mean"] = sum(vals) / len(vals) if vals else 0.0
        scored.append(case)

    means = [c["mean"] for c in scored]
    print("%-8s %s" % ("case", "均值"))
    for c in scored:
        print("%-8s %.2f" % (c["id"], c["mean"]))
    print("\nAnswer Correctness 均值: %.3f  (n=%d, judge失败 %d 条)"
          % (statistics.mean(means) if means else 0.0, len(means), failed))
    if a.out:
        io.open(a.out, "w", encoding="utf-8").write(
            json.dumps({"source": a.infile, "judge_failed": failed, "report": scored},
                       ensure_ascii=False, indent=2))
        print("已写入 %s" % a.out)


if __name__ == "__main__":
    main()
