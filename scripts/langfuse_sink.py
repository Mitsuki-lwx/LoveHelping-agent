#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Answer-Correctness 评测的 **Langfuse 旁路 sink**（ADR-70 方案 A）。

## 定位：旁路，不是主线

`scripts/answer_eval.py`（**路线 B**）刻意**不依赖 Langfuse 配置链** —— 它自带 judge、自带基线
（AC=0.90, n=16×3 轮），并且实战驱动过三次修复。本模块**不改变**这条主线，只是把**同一批分数**
额外推一份到 Langfuse，好处是：评测集**版本化**、多次运行**可比**、结果可在 UI 里回看。

## ⛔ 三条硬纪律

1. **fail-open**：任何一步失败（无凭据 / Langfuse 挂了 / 接口变了）都只 **WARN**，
   **不得**影响评测的结果、退出码或耗时上限。评测的价值不该被一个外部服务绑架。
2. **不伪造数据**：只推**真实跑出来的**分数。没有分数就不推 score（宁缺勿假）——
   假分数比没分数更糟，它会被当成证据。
3. **不吞错误**：失败的每一步都计数并打印（`failed=N`），让"推失败了"和"推成功了"分得开。

## 与 app 侧 trace 的关系

app 的 OTel trace 以请求头 `traceparent` 的 trace-id 为准（`verification_support.sse` 同一约定）。
调用方把每例的 trace_id 传进来，sink 用它把 dataset run item 与 trace 挂钩；
**若 trace 不存在于 Langfuse（如 LANGFUSE_ENABLED=false），挂钩会失败 —— 那也是 fail-open。**
"""
import base64
import io
import json
import os
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_LOCAL = os.path.join(ROOT, ".workbuddy-ai", ".env.local")
DATASET_NAME = "answer-correctness"
SCORE_NAME = "answer_correctness"


def _load_env_local():
    """凭据优先取环境变量；缺失时读本地 gitignored 的 .env.local（值不外泄）。"""
    if os.environ.get("LANGFUSE_PUBLIC_KEY") and os.environ.get("LANGFUSE_SECRET_KEY"):
        return
    if not os.path.exists(ENV_LOCAL):
        return
    with io.open(ENV_LOCAL, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip().strip('"').strip("'")
            if k.startswith("LANGFUSE_") and k not in os.environ:
                os.environ[k] = v


def _config():
    """@return (host, auth_header) 或 None（未配置 → 调用方静默跳过）"""
    _load_env_local()
    host = (os.environ.get("LANGFUSE_HOST") or os.environ.get("LANGFUSE_BASE_URL") or "").rstrip("/")
    pub = os.environ.get("LANGFUSE_PUBLIC_KEY") or ""
    sec = os.environ.get("LANGFUSE_SECRET_KEY") or ""
    if not host or not pub or not sec:
        return None
    auth = "Basic " + base64.b64encode(f"{pub}:{sec}".encode()).decode()
    return host, auth


def _get(host, auth, path, timeout=15):
    """@return (status, text)；永不抛异常。"""
    req = urllib.request.Request(host + path)
    req.add_header("Authorization", auth)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return 0, "%s: %s" % (type(e).__name__, e)


def _post(host, auth, path, body, timeout=15):
    """@return (status, text)；永不抛异常（fail-open 的底座）"""
    req = urllib.request.Request(host + path, method="POST", data=json.dumps(body).encode("utf-8"))
    req.add_header("Authorization", auth)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 —— fail-open：网络/超时/证书问题都只记账
        return 0, "%s: %s" % (type(e).__name__, e)


def push(items, run_name, description="Answer Correctness 固定集（answer_eval.py 实跑）"):
    """把评测结果推到 Langfuse dataset + run + scores。**fail-open**。

    items: [{"id", "question", "expected", "score", "reason", "trace_id"}]
           ⛔ score 为 None 时**不推 score**（不伪造数据）。
    @return dict 摘要（dataset/items/run_items/scores/failed/skipped）
    """
    cfg = _config()
    if cfg is None:
        print("  [langfuse-sink] 未配置 LANGFUSE_* → 跳过（不影响评测结果）")
        return {"skipped": True}
    host, auth = cfg
    summary = {"dataset": False, "items": 0, "run_items": 0, "scores": 0, "failed": 0,
               "skipped": False, "skipped_no_trace": 0, "scores_verified": None}

    try:
        st, body = _post(host, auth, "/api/public/v2/datasets",
                         {"name": DATASET_NAME, "description": description})
        summary["dataset"] = st == 200 or "already exists" in body or "conflict" in body.lower()
        # ⛔ **传输层失败 → 立刻放弃整批**：Langfuse 连不上时，逐项再试只是把 15s 超时乘上用例数
        #    （16 例 ≈ 4 分钟），把"fail-open"变成"fail-slow" —— 旁路不许拖慢主线。
        #    HTTP 层失败（4xx）则继续：那多半是"同名 dataset 已存在"，逐项仍可能成功。
        if st == 0:
            summary["failed"] += 1
            print("  [langfuse-sink] ⚠️ 连不上（%s）→ 放弃本次推送，不影响评测" % body[:80])
            print("  [langfuse-sink] dataset=FAIL items=0 run_items=0 scores=0 无trace跳过=0 failed=1")
            return summary
        if not summary["dataset"]:
            summary["failed"] += 1
            print("  [langfuse-sink] ⚠️ dataset 就绪失败（继续尝试逐项推送）：%s %s" % (st, body[:120]))

        for it in items:
            st, body = _post(host, auth, "/api/public/dataset-items", {
                "datasetName": DATASET_NAME,
                "id": str(it["id"]),
                "input": {"question": it["question"]},
                "expectedOutput": it.get("expected"),
                "metadata": {"source": "scripts/retrieval-ground-truth.json"},
            })
            if st == 200:
                summary["items"] += 1
            else:
                summary["failed"] += 1
                print("  [langfuse-sink] ⚠️ item %s 推送失败 %s %s" % (it["id"], st, body[:120]))

            if it.get("trace_id"):
                st, body = _post(host, auth, "/api/public/dataset-run-items", {
                    "runName": run_name,
                    "datasetItemId": str(it["id"]),
                    "traceId": it["trace_id"],
                })
                if st == 200:
                    summary["run_items"] += 1
                else:
                    summary["failed"] += 1  # trace 可能没落盘（LANGFUSE_ENABLED=false）—— 不阻断

            if it.get("score") is not None:
                # ⛔ **必须带 traceId**：实测（2026-10-01）只带 datasetRunId 的 payload，
                #    API **返回 200 但根本不落库**（项目里 score 总数始终为 0）——
                #    "200 但被丢弃"是最难查的一类假成功。没有 trace 就**不推**（不伪造）。
                if not it.get("trace_id"):
                    summary["skipped_no_trace"] += 1
                    continue
                # ⛔ 主体**只能有一个**：同时给 traceId 和 datasetRunId 会被 400 拒（实测
                #    `Invalid request data ... ["traceId","sessionId","datasetRunId","observationId"]`）。
                #    选 traceId —— dataset run 视图本来就是"run item → trace → 该 trace 的 scores"，
                #    所以挂 trace 才是对的位置。
                st, body = _post(host, auth, "/api/public/scores", {
                    "name": SCORE_NAME,
                    "value": float(it["score"]),
                    "traceId": it["trace_id"],
                    "comment": (it.get("reason") or "")[:500],
                })
                if st == 200:
                    summary["scores"] += 1
                else:
                    summary["failed"] += 1
                    print("  [langfuse-sink] ⚠️ score %s 推送失败 %s %s" % (it["id"], st, body[:120]))
    except Exception as e:  # noqa: BLE001 —— 兜底：sink 绝不允许弄崩评测
        summary["failed"] += 1
        print("  [langfuse-sink] ⚠️ 异常（已忽略，不影响评测）：%s: %s" % (type(e).__name__, e))

    # ⛔ **读回验证**：不信 200。Langfuse 对"缺主体的 score"会**收下但不落库**（实测），
    #    只看状态码会把"被丢弃"记成成功。这里回读一次，把差异喊出来。
    if summary["scores"] > 0:
        # ⛔ **有界重试**：Langfuse 的 score 是**异步入库**的（实测：推完立刻读只有 1/2，约 5~10s 后 2/2）。
        #    只读一次会天天报假警 —— 而"狼来了"式告警会让人把这个信号训练成噪声。所以重试几轮再下结论。
        # ⛔ 按**本批的 traceId** 精确比对，而不是数总数：同名分数会跨运行**累计**，
        #    数总数会把"这次被丢弃"藏在历史里（实测踩到：回读 3 而本批只推了 2）。
        want = {str(it["trace_id"]) for it in items if it.get("score") is not None and it.get("trace_id")}
        actual = None
        for attempt in range(5):
            st, body = _get(host, auth, "/api/public/scores?name=%s&limit=100" % SCORE_NAME)
            if st == 200:
                try:
                    got = {str(x.get("traceId")) for x in json.loads(body).get("data", [])}
                    actual = len(want & got)
                    if actual >= len(want):
                        break
                except Exception:  # noqa: BLE001
                    actual = None
            time.sleep(3)
        summary["scores_verified"] = actual
        if actual is not None and actual < len(want):
            print("  [langfuse-sink] ⚠️ 本批 %d 条分数里回读只找到 %d 条（等约 15s 仍不足）"
                  % (len(want), actual))

    print("  [langfuse-sink] dataset=%s items=%d run_items=%d scores=%d(回读 %s) 无trace跳过=%d failed=%d"
          % ("OK" if summary["dataset"] else "FAIL", summary["items"], summary["run_items"],
             summary["scores"], summary.get("scores_verified", "?"),
             summary["skipped_no_trace"], summary["failed"]))
    return summary
