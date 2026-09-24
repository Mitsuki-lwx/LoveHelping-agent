#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ADR-43 受益场景探针：同一批查询，测两个臂的**返回条数**与命中名次。

为什么不能只报 Recall/MRR：本实验的受益场景是"旧实现过滤后候选不足 topK"，
它的直接症状是**返回条数变少**（结果少了，而不是结果里混了不该有的东西）。
所以必须把 n_hits 一起报出来 —— 只报 MRR 会把"少了 12 条候选但 top5 恰好没变"看成"没差别"。

用法：
  ADMIN_API_KEY=xxx python scripts/probe_retrieval_benefit.py \
      --base http://127.0.0.1:PORT/api --tag after \
      --out outputs/benefit-after.json
"""
import argparse, io, json, os, sys, time, urllib.parse, urllib.request

sys.stdout.reconfigure(encoding='utf-8')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True)
    ap.add_argument('--cases', default='scripts/benefit-query-set.json')
    ap.add_argument('--tag', default='run')
    ap.add_argument('--out', default='')
    ap.add_argument('--admin-key', default=os.environ.get('ADMIN_API_KEY', ''))
    args = ap.parse_args()
    assert args.admin_key, 'need ADMIN_API_KEY'

    cases = json.load(io.open(args.cases, encoding='utf-8'))['cases']
    rows = []
    print('%-4s %-9s %-6s %-5s %-6s %s' % ('id', 'group', 'n_hits', '名次', 'pool60', 'top5'))
    print('-' * 112)
    for c in cases:
        url = (args.base.rstrip('/') + '/admin/rag/retrieve?'
               + urllib.parse.urlencode({'query': c['question']}))
        req = urllib.request.Request(url, headers={'X-Admin-Key': args.admin_key})
        try:
            hits = json.loads(urllib.request.urlopen(req, timeout=90).read())['hits']
            err = None
        except Exception as e:                      # 失败必须单列，不能算作"低分"
            hits, err = [], '%s: %s' % (type(e).__name__, str(e)[:60])
        uniq = list(dict.fromkeys(hits))
        exp = c.get('expect_docs') or []
        rank = None
        if exp:
            for i, f in enumerate(uniq[:5]):
                if any(k in (f or '') for k in exp):
                    rank = i + 1
                    break
        rows.append({'id': c['id'], 'group': c.get('group', '?'), 'question': c['question'],
                     'n_hits': len(uniq), 'rank': rank, 'top5': uniq[:5], 'error': err})
        print('%-4s %-9s %-6d %-5s %-6s %s' % (
            c['id'], c.get('group', '?')[:9], len(uniq), rank or '-', c.get('pool60', '-'),
            ', '.join((f or '?')[:16] for f in uniq[:5])[:52]))
        time.sleep(0.2)

    a = [r for r in rows if r['group'].startswith('A')]
    rec = [1.0 if r['rank'] else 0.0 for r in a]
    mrr = [1.0 / r['rank'] if r['rank'] else 0.0 for r in a]
    errs = [r for r in rows if r['error']]
    print()
    print('[%s] A 组（有 gold，n=%d）：Recall@5 %.2f | MRR@5 %.3f'
          % (args.tag, len(a), sum(rec) / len(rec), sum(mrr) / len(mrr)))
    print('[%s] 全组 n_hits：min %d / 中位 %d / max %d ；n_hits<5 的查询 = %d/%d ；请求失败 = %d'
          % (args.tag, min(r['n_hits'] for r in rows),
             sorted(r['n_hits'] for r in rows)[len(rows) // 2],
             max(r['n_hits'] for r in rows),
             sum(1 for r in rows if r['n_hits'] < 5), len(rows), len(errs)))
    for r in errs:
        print('   FAIL %s %s' % (r['id'], r['error']))
    if args.out:
        json.dump({'tag': args.tag, 'rows': rows}, io.open(args.out, 'w', encoding='utf-8'),
                  ensure_ascii=False, indent=1)
        print('已写', args.out)


if __name__ == '__main__':
    main()
