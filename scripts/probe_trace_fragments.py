#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Langfuse 侧取证：按时间窗统计 trace 名称分布，并下钻某条 trace 的 observations。

为什么要看 details 而不是只看计数：无名 trace 的**来源**只能从"它包含哪些 observation、
类型是什么、有没有 parent"反推。只数个数无法归因。

用法：
  # 名称直方图（分页拉全）
  LANGFUSE_PUBLIC_KEY=.. LANGFUSE_SECRET_KEY=.. python scripts/probe_trace_fragments.py hist \
      --host http://localhost:3000 --from 2026-09-19T05:50:00Z --to 2026-09-19T06:20:00Z
  # 下钻一条（含 observation 结构与关键属性）
  ... python scripts/probe_trace_fragments.py show --host ... --id <traceId>
"""
import argparse, base64, io, json, os, sys, urllib.parse, urllib.request

sys.stdout.reconfigure(encoding='utf-8')


def auth_header():
    pk = os.environ.get('LANGFUSE_PUBLIC_KEY', '')
    sk = os.environ.get('LANGFUSE_SECRET_KEY', '')
    if not pk or not sk:
        raise SystemExit('需要 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY 环境变量')
    return 'Basic ' + base64.b64encode((pk + ':' + sk).encode()).decode()


def get(host, path, **params):
    url = host.rstrip('/') + path
    if params:
        url += '?' + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={'Authorization': auth_header()})
    with urllib.request.urlopen(req, timeout=40) as r:
        return json.loads(r.read())


def cmd_hist(a):
    names, ids_by_name, total, page = [], {}, 0, 1
    while page <= a.max_pages:
        d = get(a.host, '/api/public/traces', limit=100, page=page,
                **{'fromTimestamp': a.frm, 'toTimestamp': a.to})
        rows = d.get('data') or []
        total = (d.get('meta') or {}).get('totalItems', total)
        if not rows:
            break
        for t in rows:
            n = t.get('name') or ''
            names.append(n)
            ids_by_name.setdefault(n, []).append(t.get('id'))
        if len(rows) < 100:
            break
        page += 1
    print('窗口 %s ~ %s' % (a.frm, a.to))
    print('platform 报告 totalItems = %s ；本脚本取到 %d 条（%d 页）' % (total, len(names), page))
    print()
    print('%-46s %6s  %s' % ('trace name', 'count', 'sample id'))
    for n in sorted(set(names), key=lambda x: -names.count(x)):
        print('%-46s %6d  %s' % (repr(n), names.count(n), (ids_by_name[n] or [''])[0][:16]))
    out = {'from': a.frm, 'to': a.to, 'total_items': total, 'fetched': len(names),
           'histogram': {n: names.count(n) for n in set(names)},
           'ids_by_name': {k: v[:5] for k, v in ids_by_name.items()}}
    if a.output:
        json.dump(out, io.open(a.output, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print('\n已写', a.output)


def cmd_show(a):
    d = get(a.host, '/api/public/traces/' + a.id)
    print('trace id   =', d.get('id'))
    print('name       =', repr(d.get('name')))
    print('timestamp  =', d.get('timestamp'), ' latency =', d.get('latency'),
          ' userId =', d.get('userId'), ' sessionId =', d.get('sessionId'))
    print('tags       =', d.get('tags'), ' release =', d.get('release'))
    obs = d.get('observations') or []
    print('observations =', len(obs))
    for o in obs[:a.limit]:
        print('  - type=%-9s name=%-28r parent=%s start=%s end=%s id=%s'
              % (o.get('type'), o.get('name'), (o.get('parentObservationId') or '-')[:12],
                 o.get('startTime'), o.get('endTime'), (o.get('id') or '')[:12]))
        md = o.get('metadata')
        if md:
            print('      metadata =', json.dumps(md, ensure_ascii=False)[:180])
        for key in ('model', 'input', 'output', 'level', 'statusMessage'):
            v = o.get(key)
            if v not in (None, '', []):
                print('      %s = %s' % (key, json.dumps(v, ensure_ascii=False)[:160]))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    h = sub.add_parser('hist')
    h.add_argument('--host', default='http://localhost:3000')
    h.add_argument('--from', dest='frm', required=True)
    h.add_argument('--to', dest='to', required=True)
    h.add_argument('--max-pages', type=int, default=20)
    h.add_argument('--output', default='')
    h.set_defaults(fn=cmd_hist)
    s = sub.add_parser('show')
    s.add_argument('--host', default='http://localhost:3000')
    s.add_argument('--id', required=True)
    s.add_argument('--limit', type=int, default=12)
    s.set_defaults(fn=cmd_show)
    a = ap.parse_args()
    a.fn(a)


if __name__ == '__main__':
    main()
