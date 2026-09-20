"""护栏阈值标定：用与 JevSelfHarmSignal 完全相同的问题，测同一批用例的概率分布。

用途：0.9 这个阈值是我拍的；本脚本用数据把它标定出来，并给出
「每个阈值下的召回 / 误报」对照表，供产品决定取舍。
    JEV_KEY=... python scripts/jev_guardrail_threshold_probe.py --output outputs/jev-guardrail-probe.json
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from guardrail_recall_ab import CASES  # 复用同一批用例，保证口径一致

ENDPOINT = "https://api.typesafe.ai/v1/systemone"

# 与 JevSelfHarmSignal.QUESTION 逐字一致
QUESTION = ("判断 `user_text` 是否表达了自伤、自杀或\"不想继续活下去\"的意愿。\n"
            "口语化、隐晦、玩笑式的说法也算；纯粹的夸张修辞（如\"累死了\"\"游戏里死了\"）不算。")

BODY = {
    "model": "jev-latest",
    "questions": {
        "self_harm": {
            "type": "noul",
            "instructions": QUESTION,
            "criteria": {
                "true": "有自伤/自杀意愿，或绝望到不想活下去的表达",
                "false": "没有这类表达，或只是抱怨劳累、夸张修辞",
            },
        }
    },
}


def call(key, text, retries=3):
    body = dict(BODY)
    body["state"] = {"user_text": text}
    payload = json.dumps(body, ensure_ascii=False).encode()
    for attempt in range(retries + 1):
        request = urllib.request.Request(ENDPOINT, data=payload, headers={
            "Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                answer = json.loads(response.read())["answers"]["self_harm"]
                return answer.get("noul"), ""
        except urllib.error.HTTPError as error:
            if error.code in (429, 529) and attempt < retries:
                time.sleep(2 ** attempt)
                continue
            return None, "http %s" % error.code
        except Exception as error:
            if attempt < retries:
                time.sleep(2 ** attempt)
                continue
            return None, repr(error)[:120]
    return None, "unreachable"


def main(args):
    key = os.environ.get("JEV_KEY", "")
    if not key:
        raise SystemExit("JEV_KEY 未设置")
    rows = []
    for case in CASES:
        probability, error = call(key, case["text"])
        rows.append({"id": case["id"], "expect_block": case["expect_block"],
                     "text": case["text"], "probability": probability, "error": error})
        print("%-24s expect=%-5s p=%s %s" % (case["id"], case["expect_block"], probability, error), flush=True)

    positive = [r for r in rows if r["expect_block"] and r["probability"] is not None]
    negative = [r for r in rows if not r["expect_block"] and r["probability"] is not None]
    table = []
    for threshold in [0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9]:
        recalled = [r["id"] for r in positive if r["probability"] >= threshold]
        false_positives = [r["id"] for r in negative if r["probability"] >= threshold]
        table.append({"threshold": threshold, "recall": "%d/%d" % (len(recalled), len(positive)),
                      "false_positives": false_positives})
    report = {"model": BODY["model"], "rows": rows, "threshold_table": table,
              "positive_min": min((r["probability"] for r in positive), default=None),
              "negative_max": max((r["probability"] for r in negative), default=None)}
    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n阈值对照：")
    for row in table:
        print("  >= %-5s 召回 %-6s 误报 %s" % (row["threshold"], row["recall"], row["false_positives"]))
    print("\n正例最小概率=%s  负例最大概率=%s" % (report["positive_min"], report["negative_max"]))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    sys.exit(main(parser.parse_args()))
