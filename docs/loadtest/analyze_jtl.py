#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""压测结果统计工装：解析 results/*.jtl，逐轮输出状态码分布与延迟分位数。

用法（在项目任意位置执行均可，路径按脚本位置解析）：
    python docs/loadtest/analyze_jtl.py                  # 自动扫描 results/pool*_*.jtl
    python docs/loadtest/analyze_jtl.py results/pool10_r4.jtl ...   # 指定文件
每轮独立文件是硬要求：同一文件叠加多轮会使样本数与 QPS 翻倍（见 v1 压测报告 Q 系列问题）。
"""
import csv
import glob
import io
import json
import os
import statistics
import sys
import datetime
import zoneinfo
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))          # <ROOT>/docs/loadtest
ROOT = os.path.dirname(os.path.dirname(HERE))              # <ROOT>
TZ = zoneinfo.ZoneInfo('Asia/Shanghai')
CLIENT_ERRORS = ('Exception', 'Error')   # 非 HTTP 状态（连接失败等），不计入服务端错误率


def t2s(ms):
    return datetime.datetime.fromtimestamp(ms / 1000.0, TZ).strftime('%m-%d %H:%M:%S.%f')[:-3]


def pct(vals, p):
    v = sorted(vals)
    k = (len(v) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(v) - 1)
    return v[f] + (v[c] - v[f]) * (k - f)


def load(path):
    raw = [l.rstrip('\n') for l in io.open(path, encoding='utf-8-sig', errors='replace')]
    body = raw[1:]
    rows = list(csv.DictReader(io.open(path, encoding='utf-8-sig', errors='replace')))
    dup = len(body) - len(set(body))          # 整行重复 => 监听器与 -l 双写
    return rows, dup


def report(path):
    rows, dup = load(path)
    name = os.path.basename(path)
    codes = Counter(r['responseCode'] for r in rows)
    succ = Counter(r['success'] for r in rows)
    threads = Counter(r['threadName'] for r in rows)
    el = [int(r['elapsed']) for r in rows]
    con = [int(r['Connect']) for r in rows]
    t0 = min(int(r['timeStamp']) for r in rows)
    t1 = max(int(r['timeStamp']) + int(r['elapsed']) for r in rows)
    span = (t1 - t0) / 1000.0
    ok = codes.get('200', 0)
    biz = codes.get('400', 0)
    http5 = sum(v for k, v in codes.items() if k.startswith('5'))
    conn_err = sum(v for k, v in codes.items() if any(e in k for e in CLIENT_ERRORS))
    sys_err = http5 + conn_err
    other = {k: v for k, v in codes.items() if k not in ('200', '400')}

    print('==== %s ====' % name)
    print('  n=%d  threads=%d  重复整行=%d %s' % (
        len(rows), len(threads), dup, '(!! 双写，样本与QPS不可用)' if dup else ''))
    print('  窗口: %s ~ %s  span=%.2fs' % (t2s(t0), t2s(t1), span))
    print('  状态码: 200=%d  400=%d  5xx=%d  连接失败=%d  其他=%s' % (ok, biz, http5, conn_err, other))
    print('  success=%s  系统错误率=%.2f%%  业务拒绝率=%.2f%%' % (
        dict(succ), 100.0 * sys_err / len(rows), 100.0 * biz / len(rows)))
    print('  响应 ms: avg=%.1f med=%.1f min=%d max=%d p90=%.1f p95=%.1f p99=%.1f' % (
        statistics.mean(el), pct(el, 50), min(el), max(el), pct(el, 90), pct(el, 95), pct(el, 99)))
    print('  connect ms: avg=%.1f p95=%.1f max=%d   QPS=%.1f' % (
        statistics.mean(con), pct(con, 95), max(con), len(rows) / span))
    html = os.path.join(ROOT, 'results', 'html_' + name[:-4], 'statistics.json')
    if os.path.exists(html):
        st = json.load(io.open(html, encoding='utf-8'))['Total']
        print('  HTML对照: n=%d err=%s mean=%.1f p95=%.1f p99=%.1f tput=%.1f' % (
            st['sampleCount'], st['errorPct'], st['meanResTime'],
            st['pct2ResTime'], st['pct3ResTime'], st['throughput']))
    return dict(name=name, n=len(rows), ok=ok, biz=biz, sys_err=sys_err, dup=dup,
                avg=statistics.mean(el), p95=pct(el, 95), p99=pct(el, 99), qps=len(rows) / span)


def main():
    args = sys.argv[1:]
    files = args if args else sorted(glob.glob(os.path.join(ROOT, 'results', 'pool*_*.jtl')))
    if not files:
        sys.exit('未找到 jtl 文件：results/pool*_*.jtl')
    rows = [report(f) for f in files]
    print('\n==== 汇总 ====')
    print('%-18s %5s %5s %5s %6s %8s %8s %8s %8s %-12s' % (
        'file', 'n', '200', '400', 'sysErr', 'avg', 'p95', 'p99', 'QPS', '判定'))
    for r in rows:
        verdict = '不可用(双写)' if r['dup'] else ('不可用(连接失败)' if r['sys_err'] else '有效')
        print('%-18s %5d %5d %5d %6d %8.1f %8.1f %8.1f %8.1f %-12s' % (
            r['name'], r['n'], r['ok'], r['biz'], r['sys_err'], r['avg'], r['p95'], r['p99'],
            r['qps'], verdict))
    valid = [r for r in rows if not r['dup'] and not r['sys_err']]
    if valid:
        print('\n有效轮数=%d  QPS 中位=%.1f  P95 中位=%.1f  P99 中位=%.1f  avg 中位=%.1f' % (
            len(valid), statistics.median([r['qps'] for r in valid]),
            statistics.median([r['p95'] for r in valid]),
            statistics.median([r['p99'] for r in valid]),
            statistics.median([r['avg'] for r in valid])))
        print('各轮 QPS: %s   （单调或波动均可判断是否已进入平台期）' % [round(r['qps'], 1) for r in valid])


if __name__ == '__main__':
    main()
