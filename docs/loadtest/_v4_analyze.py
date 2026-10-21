#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""v4 单轮统计工装（临时，压测结束后删除）。

复用 v2/v3 的 JTL span 口径：span = max(timeStamp+elapsed) - min(timeStamp)，
QPS = 样本数 / span。分别输出整体（token+buy 400 样本）与 buy 受理（200 样本）口径。

用法：
    python docs/loadtest/_v4_analyze.py results/v4_r1.jtl [results/v4_r2.jtl ...]
"""
import csv
import io
import os
import statistics
import sys

REFUSED_MARKER = 'HttpHostConnectException'
CLIENT_ERR_MARKERS = ('HttpHostConnectException', 'URISyntaxException', 'ConnectException')


def pct(vals, p):
    if not vals:
        return 0.0
    v = sorted(vals)
    k = (len(v) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(v) - 1)
    return v[f] + (v[c] - v[f]) * (k - f)


def span_qps(rows):
    if not rows:
        return 0.0, 0.0
    t0 = min(int(r['timeStamp']) for r in rows)
    t1 = max(int(r['timeStamp']) + int(r['elapsed']) for r in rows)
    span = (t1 - t0) / 1000.0
    return (len(rows) / span if span > 0 else 0.0), span


def suffix_code(label):
    if label.endswith(']') and '[' in label:
        s = label[label.rfind('[') + 1:-1]
        if s.lstrip('-').isdigit():
            return s
    return None


def is_token(r):
    return '/token' in r['label']


def is_buy(r):
    return '/buy/' in r['label']


def is_refused(r):
    return REFUSED_MARKER in r['responseCode']


def is_client_err(r):
    return any(m in r['responseCode'] for m in CLIENT_ERR_MARKERS)


def report(path):
    rows = list(csv.DictReader(io.open(path, encoding='utf-8-sig', errors='replace')))
    name = os.path.basename(path)
    if not rows:
        print('==== %s ==== 空文件' % name)
        return None

    tok = [r for r in rows if is_token(r)]
    buy = [r for r in rows if is_buy(r)]
    overall_qps, span = span_qps(rows)
    tok_qps, _ = span_qps(tok)
    buy_qps, _ = span_qps(buy)
    buy_el = [int(r['elapsed']) for r in buy]

    http5 = sum(1 for r in rows if r['responseCode'].startswith('5'))
    refused = sum(1 for r in rows if is_refused(r))
    refused_buy = sum(1 for r in buy if is_refused(r))
    client_err = sum(1 for r in rows if is_client_err(r))
    other_client = client_err - refused

    buy_codes = {}
    for r in buy:
        if is_client_err(r):
            continue
        k = suffix_code(r['label']) or 'NA'
        buy_codes[k] = buy_codes.get(k, 0) + 1

    print('==== %s ====' % name)
    print('  样本: 整体=%d (token=%d, buy=%d)  span=%.3fs' % (len(rows), len(tok), len(buy), span))
    print('  QPS(span): 整体(400口径)=%.1f   buy受理(200口径)=%.1f   token=%.1f'
          % (overall_qps, buy_qps, tok_qps))
    print('  buy 延迟 ms: avg=%.1f p95=%.1f p99=%.1f max=%d'
          % (statistics.mean(buy_el), pct(buy_el, 95), pct(buy_el, 99), max(buy_el) if buy_el else 0))
    print('  系统错误: 5xx=%d   Connection拒绝=%d (buy拒绝=%d)   其他客户端异常=%d   系统错误率=%.2f%%'
          % (http5, refused, refused_buy, other_client, 100.0 * (http5 + client_err) / len(rows)))
    print('  buy 业务码分布: %s' % dict(sorted(buy_codes.items())))
    return dict(name=name, n=len(rows), overall_qps=overall_qps, buy_qps=buy_qps,
                p95=pct(buy_el, 95), p99=pct(buy_el, 99), http5=http5, refused=refused,
                buy_codes=buy_codes)


def main():
    files = sys.argv[1:]
    if not files:
        sys.exit('用法: python docs/loadtest/_v4_analyze.py results/v4_r*.jtl')
    out = [report(f) for f in files]
    out = [o for o in out if o]
    if len(out) > 1:
        print('\n==== 汇总 ====')
        print('%-14s %8s %10s %8s %8s %6s %8s' % (
            'file', '整体QPS', 'buyQPS', 'buyP95', 'buyP99', '5xx', '拒绝'))
        for o in out:
            print('%-14s %8.1f %10.1f %8.1f %8.1f %6d %8d' % (
                o['name'], o['overall_qps'], o['buy_qps'], o['p95'], o['p99'], o['http5'], o['refused']))


if __name__ == '__main__':
    main()