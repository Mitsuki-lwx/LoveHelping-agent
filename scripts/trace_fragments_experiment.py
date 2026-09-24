#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""洞察路径 trace 碎片：三段受控实验的驱动脚本（被 run_trace_fragments.sh 调用）。

三段（每段之间用"平台侧快照"分隔，快照 = 拉取 [t0, now] 的全部 trace 并按名字统计）：
  ① 空闲基线：不做任何请求，只让定时任务跑
  ② actuator 抓取：模拟 Prometheus 打 3 次 /api/actuator/prometheus
  ③ 洞察调用：POST 一次 /api/insight/analyze

判定：
  - 若碎片的增量出现在 ② → 碎片 = actuator 流量的"根被丢、子 span 变孤儿"（T3 那道的残留）
  - 若出现在 ③ → 洞察路径自身产生碎片（与 T3 无关，属既有问题）
  - 若 ① 就有 → 定时任务/其他后台流量产生
"""
import base64, io, json, os, sys, time, urllib.error, urllib.parse, urllib.request, uuid

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))

BASE = 'http://127.0.0.1:%s/api' % os.environ['APP_PORT']
LF = os.environ['LF_HOST'].rstrip('/')
STAMP = os.environ['STAMP']
AUTH = 'Basic ' + base64.b64encode(
    (os.environ['LANGFUSE_PUBLIC_KEY'] + ':' + os.environ['LANGFUSE_SECRET_KEY']).encode()).decode()

import verification_support as vs   # noqa: E402

MARK = 'tracefrag-%s' % STAMP


def iso(ts):
    return time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(ts))


def fetch_traces(frm):
    """把 [frm, now] 的 trace 全量拉回来（分页），返回 (name -> count, 无名 trace 的 id 列表)。"""
    hist, unnamed_ids = {}, []
    page = 1
    while page <= 30:
        url = ('%s/api/public/traces?limit=100&page=%d&fromTimestamp=%s'
               % (LF, page, urllib.parse.quote(frm)))
        req = urllib.request.Request(url, headers={'Authorization': AUTH})
        with urllib.request.urlopen(req, timeout=40) as r:
            d = json.loads(r.read())
        rows = d.get('data') or []
        for t in rows:
            n = t.get('name') or ''
            hist[n] = hist.get(n, 0) + 1
            if not n:
                unnamed_ids.append(t.get('id'))
        if len(rows) < 100:
            break
        page += 1
    return hist, unnamed_ids


def snapshot(tag, frm):
    hist, unnamed = fetch_traces(frm)
    total = sum(hist.values())
    return {'tag': tag, 'since': frm, 'total': total,
            'unnamed': hist.get('', 0), 'histogram': hist, 'unnamed_ids': unnamed[:5]}


def main():
    user, token = vs.register(BASE)
    print('  合成的验证用户已注册（%s…）' % user[:12])

    steps = []
    reports = []

    # ---------- ① 空闲基线 ----------
    t = time.time()
    print('\n① 空闲基线：等 45s，期间不发任何请求…')
    time.sleep(45)
    s = snapshot('①空闲基线', iso(t))
    steps.append((t, '①空闲基线'))
    reports.append(s)
    print('   trace 总数 %d ；其中无名 %d' % (s['total'], s['unnamed']))

    # ---------- ② actuator 抓取 ----------
    t = time.time()
    print('\n② 模拟 Prometheus：打 3 次 /api/actuator/prometheus…')
    for i in range(3):
        try:
            with vs.open_request(urllib.request.Request(BASE + '/actuator/prometheus')) as r:
                r.read(200)
        except Exception as e:
            print('   抓取 #%d 失败 %s' % (i + 1, type(e).__name__))
        time.sleep(1)
    time.sleep(45)
    s = snapshot('②actuator×3', iso(t))
    reports.append(s)
    print('   trace 总数 %d ；其中无名 %d' % (s['total'], s['unnamed']))

    # ---------- ③ 洞察调用 ----------
    t = time.time()
    print('\n③ 洞察：POST 一次 /api/insight/analyze（会话内容带标记 %s）…' % MARK)
    conv = ('我：%s 你好，最近我们总吵架，我一说话他就玩手机。\n'
            '他：你别烦我。\n我：我只是想聊聊。\n他：没什么好聊的。' % MARK)
    status, body = vs.json_request(BASE, '/insight/analyze', {'conversation': conv}, token=token)
    print('   HTTP %s  ok=%s' % (status, bool(isinstance(body, dict) and body.get('success'))))
    time.sleep(70)          # 等 LLM + 导出批次（scheduleDelay）落盘
    s = snapshot('③洞察×1', iso(t))
    reports.append(s)
    print('   trace 总数 %d ；其中无名 %d' % (s['total'], s['unnamed']))

    # ---------- 结论 ----------
    print('\n=== 各段增量（无名 trace 是重点）===')
    print('%-14s %8s %8s  %s' % ('阶段', 'Δ总数', 'Δ无名', '出现的 trace 名（top5）'))
    prev = {'total': 0, 'unnamed': 0, 'histogram': {}}
    for s in reports:
        dt = s['total'] - prev['total']
        du = s['unnamed'] - prev['unnamed']
        names = {k: v - prev['histogram'].get(k, 0) for k, v in s['histogram'].items()}
        named = ', '.join('%s×%d' % (k or '(无名)', v) for k, v in
                          sorted(names.items(), key=lambda x: -x[1]) if v > 0)[:70]
        print('%-14s %8d %8d  %s' % (s['tag'], dt, du, named))
        prev = s

    print('\n判定：')
    deltas = {s['tag']: s['unnamed'] - (reports[i - 1]['unnamed'] if i else 0)
              for i, s in enumerate(reports)}
    for k, v in deltas.items():
        if v > 0:
            print('  ⚠️ %s 阶段新增无名 trace %d 条 → 碎片来自这一段' % (k, v))
    if not any(v > 0 for v in deltas.values()):
        print('  ✅ 三段都没有新增无名 trace（当前代码已无此现象）')

    json.dump({'stamp': STAMP, 'port': os.environ['APP_PORT'], 'mark': MARK,
               'reports': reports},
              io.open('outputs/tracefrag-%s.json' % STAMP, 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('已写 outputs/tracefrag-%s.json' % STAMP)


if __name__ == '__main__':
    main()
