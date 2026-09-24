#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""定向 A/B：碎片到底来自"actuator 抓取"还是"业务请求"？

机制假设（待验证）：SafeExporter 只在**导出时**按 span 名/属性丢 /actuator 流量，
而**根 span 最后结束** → 子 span（Spring Security 的 secured request / authorize request /
security filterchain）先落盘、那时根还没到、traceId 还没被记住 → 子 span 放过去；
稍后根到了被丢 → 这棵树就永久没有根，Langfuse 里表现为**一条无名 trace**。

因此预测：
  · 打 N 次 /actuator/prometheus（根会被丢）→ 出现无名碎片
  · 打 N 次业务端点（根不会被丢）      → 不出现无名碎片

判据要分两步（否则会把"摄入延迟"误判成碎片）：
  ① 窗口内统计无名 trace
  ② **等 90s 后复查这些 id 是否仍然无名** —— 仍无名才算结构性碎片（补上名字的是延迟）
"""
import base64, json, os, sys, time, urllib.parse, urllib.request

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verification_support as vs   # noqa: E402

BASE = 'http://127.0.0.1:%s/api' % os.environ['APP_PORT']
LF = os.environ['LF_HOST'].rstrip('/')
STAMP = os.environ['STAMP']
AUTH = 'Basic ' + base64.b64encode(
    (os.environ['LANGFUSE_PUBLIC_KEY'] + ':' + os.environ['LANGFUSE_SECRET_KEY']).encode()).decode()
N = 6
GAP = 3


def iso(ts):
    return time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(ts))


def fetch(frm, to=None):
    """拉 [frm, to] 的 trace（分页）。返回 (总数, 无名 id 列表, 名字直方图)。"""
    unnamed, hist, page = [], {}, 1
    while page <= 30:
        params = {'limit': 100, 'page': page, 'fromTimestamp': frm}
        if to:
            params['toTimestamp'] = to
        url = LF + '/api/public/traces?' + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={'Authorization': AUTH})
        with urllib.request.urlopen(req, timeout=40) as r:
            d = json.loads(r.read())
        rows = d.get('data') or []
        for t in rows:
            n = t.get('name') or ''
            hist[n] = hist.get(n, 0) + 1
            if not n:
                unnamed.append(t.get('id'))
        if len(rows) < 100:
            break
        page += 1
    return sum(hist.values()), unnamed, hist


def trace_name(tid):
    req = urllib.request.Request(LF + '/api/public/traces/' + tid, headers={'Authorization': AUTH})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read()).get('name') or ''


def phase(label, url_path, token):
    t0 = time.time()
    print('\n=== %s：打 %d 次 %s（间隔 %ds）===' % (label, N, url_path, GAP))
    ok = 0
    for i in range(N):
        req = urllib.request.Request(BASE + url_path)
        if token:
            req.add_header('Authorization', 'Bearer ' + token)
        try:
            with vs.open_request(req) as r:
                r.read(64)
            ok += 1
        except Exception as e:
            print('   #%d 失败 %s' % (i + 1, type(e).__name__))
        time.sleep(GAP)
    t1 = time.time()
    print('   成功 %d/%d ；窗口 %s ~ %s' % (ok, N, iso(t0), iso(t1)))
    return t0, t1


def main():
    user, token = vs.register(BASE)
    print('  合成用户 %s…' % user[:12])

    a0, a1 = phase('A 组：actuator 抓取（根会被丢）', '/actuator/prometheus', None)
    b0, b1 = phase('B 组：业务端点（根不会被丢）', '/auth/me', token)

    print('\n等 75s 让导出批次与摄入落定…')
    time.sleep(75)

    ta, ua, ha = fetch(iso(a0), iso(a1))
    tb, ub, hb = fetch(iso(b0), iso(b1))
    print('\n=== 快照（导入后立刻）===')
    print('  A(actuator) 总数 %-3d 无名 %-3d  名字: %s'
          % (ta, len(ua), json.dumps(ha, ensure_ascii=False)[:150]))
    print('  B(业务)     总数 %-3d 无名 %-3d  名字: %s'
          % (tb, len(ub), json.dumps(hb, ensure_ascii=False)[:150]))

    print('\n再等 90s，复查这些无名的 id 是否仍然是无名（仍无名=结构性，补名=摄入延迟）…')
    time.sleep(90)
    res = {}
    for label, ids in (('A', ua), ('B', ub)):
        still, named_later = [], []
        for tid in ids:
            try:
                n = trace_name(tid)
            except Exception:
                n = '?'
            (still if not n else named_later).append((tid, n))
        res[label] = {'total': ta if label == 'A' else tb, 'unnamed_at_snapshot': len(ids),
                      'still_unnamed': len(still), 'named_later': len(named_later),
                      'named_later_detail': [x[1][:40] for x in named_later[:4]],
                      'still_unnamed_ids': [x[0] for x in still[:5]]}
        print('  %s 组：快照时无名 %d → 90s 后仍无名 **%d**（这 %d 条是结构性碎片；另 %d 条只是摄入延迟）'
              % (label, len(ids), len(still), len(still), len(named_later)))
        if named_later:
            print('     延迟补名的实际是：%s' % ', '.join(x[1][:28] for x in named_later[:3]))

    print('\n判定：')
    sa, sb = res['A']['still_unnamed'], res['B']['still_unnamed']
    if sa > sb:
        print('  ✅ 碎片集中在 **actuator 抓取**（A=%d vs B=%d）→ 归因成立；'
              '洞察路径与该现象无关' % (sa, sb))
    elif sa == 0 and sb == 0:
        print('  ⚠️ 两组都没产生结构性碎片（本轮批次边界恰好没漏）—— 加大 N 重跑')
    else:
        print('  ❓ 两组都有（A=%d B=%d）→ 需要换判据（可能不止 actuator）' % (sa, sb))

    json.dump({'stamp': STAMP, 'n_per_phase': N, 'gap_s': GAP,
               'window_A': [iso(a0), iso(a1)], 'window_B': [iso(b0), iso(b1)],
               'A': res['A'], 'B': res['B'],
               'hist_A': ha, 'hist_B': hb},
              open('outputs/tracefrag-ab-%s.json' % STAMP, 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('已写 outputs/tracefrag-ab-%s.json' % STAMP)


if __name__ == '__main__':
    main()
