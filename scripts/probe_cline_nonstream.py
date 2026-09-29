#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""phase25 判据 C：**非流式** `LlmGateway.call()` 路径能否穿过 cline 网关的信封。

背景（2026-09-29 实测）：cline 网关 `POST /api/v1/chat/completions` 的**非流式**响应是
`{"data": {choices...}, "success": true}`，标准 OpenAI 客户端在顶层找不到 `choices`
→ Spring AI 的 `OpenAiChatModel.call()` 大概率解析失败。

判据（对着"用户被伤到什么"写，不是对着报错写）：
  - 成功 = `/sandbox/ta-view` 返回 `reply` 非空且不含降级/报错话术
  - 失败 = 返回 5000 类错误文案，或 reply 为空

沙盘 ta-view 是当前唯一可直接触达 `call()` 的用户面接口（另两处 SentimentService 走 JEV 优先）。

用法：
  python scripts/probe_cline_nonstream.py --base http://127.0.0.1:PORT/api
"""
import argparse
import json
import sys
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verification_support import register, json_request  # noqa: E402

# 降级/报错话术：出现即视为失败（与 probe_answer_stability 同族判据）
FAIL_MARKERS = ("AI 服务暂时不可用", "服务繁忙", "请稍后再试", "内部错误")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--output")
    args = ap.parse_args()

    user, token = register(args.base)
    payload = {"customTraits": "谨慎、话不多，习惯先听完再说",
               "message": "我们能聊聊吗？最近好像有点疏远。"}
    out = {"base": args.base, "user_hash": __import__("hashlib").sha256(
        user.encode()).hexdigest()[:16]}
    try:
        status, resp = json_request(args.base, "/sandbox/ta-view", payload=payload, token=token, method="POST")
        out["http_status"] = status
        out["raw"] = resp
        code = resp.get("code") if isinstance(resp, dict) else None
        data = (resp.get("data") or {}) if isinstance(resp, dict) else {}
        reply = (data.get("reply") or "") if isinstance(data, dict) else ""
        out["code"] = code
        out["reply_chars"] = len(reply)
        out["reply_head"] = reply[:80]
        blob = json.dumps(resp, ensure_ascii=False)
        hit = [m for m in FAIL_MARKERS if m in blob]
        out["fail_markers"] = hit
        out["ok"] = bool(reply) and not hit
    except urllib.error.HTTPError as e:
        out["ok"] = False
        out["http_error"] = "%s %s" % (e.code, e.read().decode("utf-8", "replace")[:200])
    except Exception as e:
        out["ok"] = False
        out["error"] = "%s: %s" % (type(e).__name__, e)

    print(json.dumps(out, ensure_ascii=False, indent=2))
    if args.output:
        Path(args.output).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
        print("已写入 %s" % args.output)
    sys.exit(0 if out.get("ok") else 1)


if __name__ == "__main__":
    main()
