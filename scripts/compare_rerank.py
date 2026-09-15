"""Paired evaluation on identical production candidates; no production rerank configuration changes.
Baseline(8 chunks), truncation-only(5), local cross-encoder(5) and conservative RRF blend(5).
Random candidate order/no synthetic scores are not used. API errors fail the run, never skipped.
"""
import argparse
import json
import os
from pathlib import Path
import statistics
import sys
import time
import urllib.parse
from verification_support import json_request


def measure(candidates, case):
    files=list(dict.fromkeys(d['filename'] for d in candidates if d['filename']))[:5]
    positions=[next((i+1 for i,f in enumerate(files) if word in f),None) for word in case['expect_docs']]
    rank=(max(positions) if all(p is not None for p in positions) else None) if case.get('expect_mode')=='all' else min((p for p in positions if p is not None),default=None)
    return {'recall':float(rank is not None),'mrr':1/rank if rank else 0,'files':files}


def main(args):
    key=os.environ.get('ADMIN_API_KEY','')
    if not key: raise SystemExit('ADMIN_API_KEY required')
    cases=json.loads(Path(args.cases).read_text(encoding='utf-8'))['cases']
    results=[]
    for case in cases:
        started=time.perf_counter()
        status, body=json_request(args.base,'/admin/rag/retrieve?'+urllib.parse.urlencode({'query':case['question'],'includeCandidates':'true'}),headers={'X-Admin-Key':key})
        if status!=200 or 'candidates' not in body: raise RuntimeError('Candidate snapshot failed; evaluation aborted')
        docs=body['candidates']
        if len(docs)<1: raise RuntimeError('No retrieval candidates; evaluation aborted')
        rank_start=time.perf_counter()
        status, ranked=json_request(args.reranker,'/rerank',{'query':case['question'],'documents':[d['text'][:1000] for d in docs],'top_n':len(docs)},timeout=15)
        if status!=200: raise RuntimeError('Local reranker failed; cannot claim model quality from fallback')
        order=[r['index'] for r in ranked['results']]
        if sorted(order)!=list(range(len(docs))): raise RuntimeError('Invalid reranker permutation')
        model_position={idx:rank for rank,idx in enumerate(order)}
        blended=sorted(range(len(docs)),key=lambda idx:-(.75/(60+idx+1)+.25/(60+model_position[idx]+1)))
        variants={'baseline':docs,'truncate_only':docs[:5],'local': [docs[i] for i in order[:5]],'blend_75_25':[docs[i] for i in blended[:5]]}
        row={'id':case['id'],'variants':{name:measure(ds,case) for name,ds in variants.items()},
             'rerank_ms':round((time.perf_counter()-rank_start)*1000,1),'total_ms':round((time.perf_counter()-started)*1000,1),
             'uuid_text_candidates':sum(len(d['text'])==36 and d['text'].count('-')==4 for d in docs)}
        results.append(row)
        print(case['id'],{k:v['mrr'] for k,v in row['variants'].items()},flush=True)
    names=results[0]['variants']
    summary={name:{'n':len(results),'recall':statistics.mean(r['variants'][name]['recall'] for r in results),
                   'mrr':statistics.mean(r['variants'][name]['mrr'] for r in results)} for name in names}
    report={'design':'paired_identical_candidates; no tuning on test labels','summary':summary,
            'median_rerank_ms':statistics.median(r['rerank_ms'] for r in results),'uuid_text_candidates':sum(r['uuid_text_candidates'] for r in results),'cases':results}
    Path(args.output).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='cases'},ensure_ascii=False),flush=True)


if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8',errors='replace')
    p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:8088/api');p.add_argument('--reranker',default='http://127.0.0.1:8091')
    p.add_argument('--cases',default=str(Path(__file__).with_name('retrieval-ground-truth.json')));p.add_argument('--output',required=True)
    main(p.parse_args())
