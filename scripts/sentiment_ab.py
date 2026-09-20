"""情绪打分 A/B：同一批人工标注消息，分别打给「Jev 路径」与「原 LLM 路径」启动的应用实例。

用法：
    python scripts/sentiment_ab.py --base http://127.0.0.1:PORT/api --mode jev --output outputs/sentiment-jev.json
每个用例用独立 chatId（`scoreText` 对同 chatId 会跳过重复打分，故每次必须新用户）。
"""
import argparse
import json
import sys
import time
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import json_request, register  # noqa: E402

# 人工标签：覆盖 -2..2 五档。分数域与产品既有定义一致（-2=危机 … 2=明显变好）
CASES = [
    {"id": "crisis_end", "label": -2, "text": "我不想活了，每天都在想结束这一切"},
    {"id": "crisis_meaningless", "label": -2, "text": "撑不下去了，感觉活着一点意义都没有"},
    {"id": "low_quarrel", "label": -1, "text": "今天又跟他吵架了，心里堵得慌，什么都不想干"},
    {"id": "low_ending", "label": -1, "text": "感觉这段感情快走到头了，我很难过"},
    {"id": "flat_ack", "label": 0, "text": "嗯，知道了"},
    {"id": "flat_daily", "label": 0, "text": "今天上班，没什么特别的"},
    {"id": "up_talk", "label": 1, "text": "昨天我们聊了很久，好像有点转机"},
    {"id": "good_makeup", "label": 2, "text": "他今天主动来找我了，我们和好了，特别开心"},
    {"id": "good_decided", "label": 2, "text": "想通了，我决定好好生活，谢谢你们"},
]


def main(args):
    user, token = register(args.base)
    print("user=%s" % user)
    latencies = []
    for case in CASES:
        started = time.time()
        status, body = json_request(args.base, "/sentiment/score",
                                    payload={"chatId": case["id"], "text": case["text"]}, token=token)
        latencies.append(time.time() - started)
        case["http"] = status
    status, timeline = json_request(args.base, "/sentiment/timeline", token=token)
    rows = timeline.get("data") or timeline.get("rows") or []
    stored = {r.get("chatId"): r for r in rows}

    hits, near, table = 0, 0, []
    for case in CASES:
        row = stored.get(case["id"])
        got = row.get("score") if row else None
        reason = row.get("reason") if row else None
        hits += 1 if got == case["label"] else 0
        near += 1 if (got is not None and abs(got - case["label"]) <= 1) else 0
        table.append({"id": case["id"], "label": case["label"], "got": got, "reason": reason,
                      "text": case["text"]})

    lat = sorted(latencies)
    report = {"mode": args.mode, "base": args.base, "cases": len(CASES),
              "exact_hits": hits, "within_one": near,
              "exact_rate": round(hits / len(CASES), 3), "within_one_rate": round(near / len(CASES), 3),
              "latency_p50_s": round(lat[len(lat) // 2], 2), "latency_max_s": round(lat[-1], 2),
              "rows": table}
    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("%-20s %6s %6s  %s" % ("case", "label", "got", "reason"))
    for row in table:
        print("%-20s %6s %6s  %s" % (row["id"], row["label"], row["got"], (row["reason"] or "")[:28]))
    print("\nmode=%s exact=%d/%d (%.1f%%) within±1=%d/%d (%.1f%%)  latency p50=%.2fs max=%.2fs" % (
        args.mode, hits, len(CASES), 100 * hits / len(CASES),
        near, len(CASES), 100 * near / len(CASES), report["latency_p50_s"], report["latency_max_s"]))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--output")
    sys.exit(main(parser.parse_args()))
