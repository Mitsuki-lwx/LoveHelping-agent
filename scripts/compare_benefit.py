# -*- coding: utf-8 -*-
"""对比两臂结果：候选条数、top5 是否变化、目标文档是否从"搜不到"变成"搜到"。"""
import glob, io, json, os, sys

sys.stdout.reconfigure(encoding='utf-8')

def latest(tag):
    fs = glob.glob('outputs/benefit-%s-*.json' % tag)
    assert fs, 'no output for ' + tag
    return max(fs, key=os.path.getmtime)

after = json.load(io.open(latest('after'), encoding='utf-8'))
before = json.load(io.open(latest('before'), encoding='utf-8'))
A = {r['id']: r for r in after['rows']}
B = {r['id']: r for r in before['rows']}
keys = [k for k in A if k in B]

print('%-5s %-9s %-11s %-9s %-8s %s' % ('id', 'group', 'n_hits 前→后', 'top5相同', '名次前→后', 'query'))
print('-' * 116)
n_same_top5 = n_starved_before = 0
newly_found = []
for k in keys:
    a, b = A[k], B[k]
    same = a['top5'] == b['top5']
    n_same_top5 += same
    if b['n_hits'] < 5:
        n_starved_before += 1
    rk = '%s→%s' % (b['rank'] or '-', a['rank'] or '-')
    if b['rank'] is None and a['rank'] is not None:
        newly_found.append(k)
    print('%-5s %-9s %-11s %-9s %-8s %s' % (
        k, a['group'][:9], '%d→%d' % (b['n_hits'], a['n_hits']),
        '是' if same else '**否**', rk, a['question'][:34]))

def stats(arm, rows):
    a = [r for r in rows if r['group'].startswith('A')]
    rec = sum(1 for r in a if r['rank']) / len(a)
    mrr = sum(1.0 / r['rank'] if r['rank'] else 0.0 for r in a) / len(a)
    return rec, mrr

ra, ma = stats('after', after['rows'])
rb, mb = stats('before', before['rows'])
print()
print('A 组（n=%d，有 gold）：' % len([r for r in after['rows'] if r['group'].startswith('A')]))
print('  修复前 Recall@5 %.2f / MRR@5 %.3f' % (rb, mb))
print('  修复后 Recall@5 %.2f / MRR@5 %.3f' % (ra, ma))
print()
print('候选条数：修复前合计 %d / 修复后合计 %d' % (
    sum(r['n_hits'] for r in before['rows']), sum(r['n_hits'] for r in after['rows'])))
print('修复前 n_hits<5 的查询 = %d/%d（真被挤空的）' % (n_starved_before, len(keys)))
print('top5 完全相同的查询 = %d/%d ；top5 有变化的 = %d' % (
    n_same_top5, len(keys), len(keys) - n_same_top5))
print('"修复前搜不到目标、修复后搜到"的查询 = %s' % (newly_found or '无'))
print()
print('判定（先看这两条）:')
print('  ①修复前有查询 n_hits<5 吗 → 有=%s（说明受益场景**真实存在**）' % ('是' if n_starved_before else '否'))
print('  ②这些查询的 top5/名次变了吗 → %s' % ('变了' if len(newly_found) or n_same_top5 < len(keys) else '没变'))
