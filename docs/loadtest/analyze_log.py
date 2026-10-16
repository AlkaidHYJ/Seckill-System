#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""服务端日志交叉验证工装：用 results/*.jtl 的每轮时间窗，统计应用日志中的真实处理量。

用途：确认"客户端看到的"与"服务端做到的"是否一致。
  - UPDATE seckill_activity 次数 应 = 到达服务端的 buy 请求数
  - INSERT INTO orders 次数      应 = 200 成功响应数（=库存数）
  - ERROR/WARN 条数              应 = 0（有则贴出前若干条）
用法：
  python docs/loadtest/analyze_log.py            # 自动扫描 results/pool*_*.jtl
  python docs/loadtest/analyze_log.py --day 2026-10-16   # 额外列出当天应用启停标记
"""
import csv
import datetime
import glob
import io
import os
import re
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))          # <ROOT>/docs/loadtest
ROOT = os.path.dirname(os.path.dirname(HERE))              # <ROOT>
LOG = os.path.join(ROOT, 'logs', 'flash-sale-system.log')
PAD = 20  # 每轮时间窗前后各留 20s，覆盖 ramp-up 与日志刷盘延迟
FMT = '%Y-%m-%d %H:%M:%S'


def line_ts(l):
    try:
        return datetime.datetime.strptime(l[:19], FMT)
    except (ValueError, IndexError):
        return None


def read_log():
    if not os.path.exists(LOG):
        sys.exit('日志文件不存在：%s' % LOG)
    return io.open(LOG, encoding='utf-8', errors='replace').read().splitlines()


def round_windows():
    """从 jtl 推断每轮的起止时间（epoch ms）。"""
    out = []
    for path in sorted(glob.glob(os.path.join(ROOT, 'results', 'pool*_*.jtl'))):
        rows = list(csv.DictReader(io.open(path, encoding='utf-8-sig', errors='replace')))
        if not rows:
            continue
        t0 = min(int(r['timeStamp']) for r in rows) / 1000.0
        t1 = max(int(r['timeStamp']) + int(r['elapsed']) for r in rows) / 1000.0
        codes = Counter(r['responseCode'] for r in rows)
        out.append(dict(
            name=os.path.basename(path)[:-4],
            lo=datetime.datetime.fromtimestamp(t0) - datetime.timedelta(seconds=PAD),
            hi=datetime.datetime.fromtimestamp(t1) + datetime.timedelta(seconds=PAD),
            n=len(rows), ok=codes.get('200', 0), biz=codes.get('400', 0),
            lost=sum(v for k, v in codes.items() if 'Exception' in k or 'Error' in k)))
    return out


def main():
    lines = read_log()
    print('日志总行数=%d，最后一行时间=%s' % (len(lines), (lines[-1][:19] if lines else '-')))
    if '--day' in sys.argv:
        day = sys.argv[sys.argv.index('--day') + 1]
        print('\n--- %s 应用启停/失败标记 ---' % day)
        pat = re.compile(r'Starting FlashSaleApplication|HikariDataSource - FlashSaleHikariPool - Start completed'
                         r'|Graceful shutdown complete|Shutdown completed|Application run failed')
        for l in lines:
            if l.startswith(day) and pat.search(l):
                print('  ', l[:120])
    print('\n--- 逐轮服务端证据 ---')
    print('%-14s %-6s %6s %6s %6s %7s %6s %6s %s' % (
        'round', '日志行', 'UPDATE', 'INSERT', 'SELECT', 'http400', 'ERROR', 'WARN', '异常/告警摘要'))
    for w in round_windows():
        seg = [l for l in lines if (t := line_ts(l)) and w['lo'] <= t <= w['hi']]
        c = Counter()
        bad = []
        for l in seg:
            low = l.lower()
            if 'update seckill_activity' in low:
                c['upd'] += 1
            if 'insert into orders' in low:
                c['ins'] += 1
            if 'select' in low and ' from ' in low:
                c['sel'] += 1
            if re.search(r'-> 400', l):
                c['http400'] += 1
            if re.search(r' ERROR | WARN ', l):
                c['err'] += 1 if ' ERROR ' in l else 0
                c['warn'] += 1 if ' WARN ' in l else 0
                bad.append(l[:170])
        flag = ''
        if c['upd'] != w['n'] - w['lost']:
            flag = ' !! UPDATE 数(%d) ≠ 到达请求数(%d)' % (c['upd'], w['n'] - w['lost'])
        if c['ins'] != w['ok']:
            flag += ' !! INSERT 数(%d) ≠ 成功数(%d)' % (c['ins'], w['ok'])
        print('%-14s %-6d %6d %6d %6d %7d %6d %6d %s%s' % (
            w['name'], len(seg), c['upd'], c['ins'], c['sel'], c['http400'],
            c['err'], c['warn'], ('客户端丢失=%d' % w['lost']) if w['lost'] else '', flag))
        for b in bad[:5]:
            print('      >>', b)


if __name__ == '__main__':
    main()
