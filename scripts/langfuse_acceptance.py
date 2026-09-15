#!/usr/bin/env python3
"""Exact-request Langfuse acceptance. A random recent trace is NEVER accepted as evidence.
Credentials: LANGFUSE_HOST / LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY, environment only.
Input: evidence from e2e_live.py. Queries each recorded W3C trace ID and checks ancestry,
terminal state, generation count, usage deduplication, session/user identity and privacy.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import sys
from verification_support import json_request


def attrs(observation):
    return (observation.get("metadata") or {}).get("attributes") or {}


def inspect_trace(run, trace):
    checks = []
    def check(name, passed):
        checks.append({"name":name,"passed":bool(passed)})
    check("exact_trace", trace.get("id") == run["trace_id"])
    observations = trace.get("observations") or []
    by_id = {o["id"]:o for o in observations}
    check("observations_present", bool(observations))
    private = [m for m in run.get("privacy_markers",[]) if isinstance(m,str) and len(m)>=6]
    blob = json.dumps(trace,ensure_ascii=False)
    check("no_test_content_or_raw_identity", not any(m in blob for m in private))
    if run["scenario"].startswith("guardrail"):
        check("guardrail_did_not_call_llm", not any(o.get("name")=="llm.attempt" for o in observations))
        return checks, observations
    pipelines = [o for o in observations if o.get("name")=="chat.pipeline"]
    check("one_pipeline", len(pipelines)==1)
    check("session_exact_match", trace.get("sessionId")==run["session_hash"])
    if run.get("user_hash"):
        check("user_exact_match", trace.get("userId")==run["user_hash"])
    if not pipelines: return checks, observations
    pipeline = pipelines[0]
    check("pipeline_success", attrs(pipeline).get("graph.outcome")=="success" and pipeline.get("endTime") is not None)
    nodes = [o for o in observations if (o.get("name") or "").startswith("graph.node.")]
    check("node_parentage", bool(nodes) and all(o.get("parentObservationId")==pipeline["id"] for o in nodes))
    attempts = [o for o in observations if o.get("name")=="llm.attempt"]
    check("model_attempts_present", bool(attempts))
    check("attempt_parentage", all((by_id.get(o.get("parentObservationId"),{}).get("name") or "").startswith("graph.node.") for o in attempts))
    check("model_success", all(attrs(o).get("llm.outcome")=="success" for o in attempts))
    # ADR-27: the gateway (llm.attempt) is the only *chat* generation owner; whenever the SDK
    # emits its own chat span it must be demoted to a plain span. Embeddings are legitimate
    # generations in their own right and are not duplicated chat generations.
    # (Streaming calls emit no SDK chat span; model_attempts_present already asserts the
    #  gateway generation exists, so an empty set is not a failure here.)
    chat_like = [o for o in observations
                 if attrs(o).get("gen_ai.operation.name")=="chat" or (o.get("name") or "").startswith("chat ")]
    check("gateway_owns_chat_generations",
          all(o.get("type")!="GENERATION" or o.get("name")=="llm.attempt" for o in chat_like))
    check("sdk_chat_demoted_to_span",
          all(o.get("type")=="SPAN" for o in chat_like if o.get("name")!="llm.attempt"))
    gateway_tokens = sum((o.get("usageDetails") or {}).get("total",0) for o in attempts)
    chat_tokens = sum((o.get("usageDetails") or {}).get("total",0) for o in observations
                      if o.get("name")=="llm.attempt" or (o.get("name") or "").startswith("chat "))
    check("no_double_counted_chat_tokens", gateway_tokens>0 and gateway_tokens==chat_tokens)
    if run["scenario"] in ("rag","agent","sandbox") or run["scenario"].startswith("advice"):
        retrievals = [o for o in observations if o.get("name")=="rag.retrieve"]
        check("retrieval_present", bool(retrievals))
        check("retrieval_parentage", all((by_id.get(o.get("parentObservationId"),{}).get("name") or "").startswith("graph.node.")
                                        or by_id.get(o.get("parentObservationId"),{}).get("name")=="agent.tool" for o in retrievals))
        check("retrieval_numeric_results", all(int(attrs(o).get("rag.results",-1))>=0 for o in retrievals))
    check("no_unexpected_error_observations", not any(o.get("level")=="ERROR" for o in observations))
    return checks, observations


def main(args):
    public=os.environ.get("LANGFUSE_PUBLIC_KEY","");secret=os.environ.get("LANGFUSE_SECRET_KEY","")
    if not public or not secret: raise SystemExit("LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY required")
    auth="Basic "+base64.b64encode((public+":"+secret).encode()).decode()
    evidence=json.loads(Path(args.evidence).read_text(encoding="utf-8"))
    results=[]
    for run in evidence["traces"]:
        status, trace=json_request(args.host,"/api/public/traces/"+run["trace_id"],headers={"Authorization":auth})
        if status!=200:
            checks=[{"name":"exact_trace_query","passed":False}]; observations=[]
        else:
            checks, observations=inspect_trace(run,trace)
        row={"scenario":run["scenario"],"trace_id":run["trace_id"],"passed":all(c["passed"] for c in checks),"checks":checks,
             "observations":[{"id":o.get("id"),"name":o.get("name"),"parent":o.get("parentObservationId"),"type":o.get("type"),
                              "level":o.get("level"),"start":o.get("startTime"),"end":o.get("endTime"),"usage":o.get("usageDetails"),
                              "attributes":attrs(o)} for o in observations]}
        results.append(row)
        print(("PASS " if row["passed"] else "FAIL ")+run["scenario"]+" "+run["trace_id"]+" "+str([c["name"] for c in checks if not c["passed"]]),flush=True)
    report={"passed":sum(r["passed"] for r in results),"total":len(results),"host":args.host,"traces":results}
    if args.output: Path(args.output).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"passed":report["passed"],"total":report["total"]}))
    return 0 if results and report["passed"]==report["total"] else 1


if __name__=="__main__":
    sys.stdout.reconfigure(encoding="utf-8",errors="replace")
    p=argparse.ArgumentParser();p.add_argument("--host",default=os.environ.get("LANGFUSE_HOST","http://127.0.0.1:3000"))
    p.add_argument("--evidence",required=True);p.add_argument("--output")
    sys.exit(main(p.parse_args()))
