#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Tomcat 工作线程取证：判断 http-nio-8080-exec-<N> 的编号是否等于并发线程数。

结论判据：
  - 若"同一时刻的 distinct 线程数" <= 200，而"编号最大值" > 200，
    说明编号是 TaskThreadFactory 的累计计数器（线程回收后继续递增），并不违反 maxThreads=200。
  - 若某一刻 distinct 线程数 > 200，则 maxThreads 确被调大，与配置文件推断矛盾。
"""
import csv
import datetime
import glob
import gzip
import io
import os
import re
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOG_DIR = os.path.join(ROOT, 'logs')
PAT = re.compile(r'http-nio-8080-exec-(\d+)')
FMT = '%Y-%m-%d %H:%M:%S'


def ts_of(line):
    try:
        return datetime.datetime.strptime(line[:19], FMT)
    except (ValueError, IndexError):
        return None


def all_lines():
    """当前日志 + 历史 gz，按时间顺序合并。"""
    out = []
    cur = os.path.join(LOG_DIR, 'flash-sale-system.log')
    if os.path.exists(cur):
        out += io.open(cur, encoding='utf-8', errors='replace').read().splitlines()
    for gz in sorted(glob.glob(os.path.join(LOG_DIR, '*.gz'))):
        with gzip.open(gz, 'rt', encoding='utf-8', errors='replace') as f:
            out += [l.rstrip('\n') for l in f]
    return [l for l in out if ts_of(l)]


def rounds():
    ws = []
    for p in sorted(glob.glob(os.path.join(ROOT, 'results', 'pool*_*.jtl'))):
        rows = list(csv.DictReader(io.open(p, encoding='utf-8-sig', errors='replace')))
        if not rows:
            continue
        t0 = min(int(r['timeStamp']) for r in rows) / 1000.0
        t1 = max(int(r['timeStamp']) + int(r['elapsed']) for r in rows) / 1000.0
        ws.append((os.path.basename(p)[:-4],
                   datetime.datetime.fromtimestamp(t0) - datetime.timedelta(seconds=2),
                   datetime.datetime.fromtimestamp(t1) + datetime.timedelta(seconds=2)))
    return ws


def main():
    lines = sorted(all_lines(), key=ts_of)
    print('参与分析的日志行数 =', len(lines),
          ' 时间范围 =', lines[0][:19], '~', lines[-1][:19])

    # 1) 全局：编号最大值 vs 出现过的 distinct 编号个数
    nums = [int(m.group(1)) for l in lines for m in [PAT.search(l)] if m]
    print('\n[全局] exec 编号最大值 = %d, distinct 编号个数 = %d' % (max(nums), len(set(nums))))

    # 2) 按天：编号最大值
    by_day = defaultdict(list)
    for l in lines:
        m = PAT.search(l)
        if m:
            by_day[l[:10]].append(int(m.group(1)))
    print('\n[按天] 日期 -> exec 编号最大 / distinct 个数')
    for d in sorted(by_day):
        v = by_day[d]
        print('   %s  max=%-5d distinct=%d' % (d, max(v), len(set(v))))

    # 3) 按压测轮次的 burst 窗口：同一时刻真实并发线程数（决定性证据）
    print('\n[按轮次窗口] 窗口内 distinct 线程数 与 编号区间')
    for name, lo, hi in rounds():
        v = [int(m.group(1)) for l in lines
             if lo <= ts_of(l) <= hi for m in [PAT.search(l)] if m]
        if not v:
            print('   %-14s 无 buy 线程日志' % name)
            continue
        print('   %-14s distinct线程数=%-4d 编号区间=%d~%d  %s' % (
            name, len(set(v)), min(v), max(v),
            'OK(<=200)' if len(set(v)) <= 200 else '!! 超过 200，maxThreads 确被调大'))

    # 4) 编号 > 200 的行出现在哪些天/时段（判断是否累计计数器）
    over = [l for l in lines if (m := PAT.search(l)) and int(m.group(1)) > 200]
    print('\n[编号>200 的日志] 共 %d 行，时间分布：' % len(over))
    days = defaultdict(int)
    for l in over:
        days[l[:13]] += 1
    for k in sorted(days):
        print('   %s 时 -> %d 行（最大编号 %d）' % (
            k, days[k], max(int(m.group(1)) for l in over if l[:13] == k for m in [PAT.search(l)])))
    if over:
        print('   样例：', over[0][:150])


if __name__ == '__main__':
    main()
