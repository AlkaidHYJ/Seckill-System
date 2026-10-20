# ADR-020：三方对账口径与处置策略

- 状态：已采纳
- 日期：2026-10-20
- 范围：v4 JOB-03 三方对账（`ReconciliationJob`、`ReconciliationRecord`、`ReconciliationResult`、`ReconciliationRecordMapper`）、`resources/sql/reconciliation.sql`
- 不包含：差异自动修复、分库分表、跨机房/多数据源对账、Redis 与 MySQL 之外的第三方存储

## 1. 背景

v2/v3 的库存一致性依赖两条链路各自自洽：Redis 侧由 `seckill_pre_decrement.lua` 原子扣减，MySQL 侧由下单落库逐条扣减 `available_stock`，超时/回补由 v4 的 JOB-01/JOB-02 收敛。三条链路各自幂等，但**没有任何环节做全局交叉验证**：一旦出现 Lua 与落库之间「Redis 扣了、订单没落」「订单关了、库存没还」的偏差（ADR-019 已登记 `FAILED` 残窗），系统无法自动感知。

v4 设计 11.4 / 17.4 要求引入**只读的三方对账**，作为防超卖三道防线中的第三道：不参与扣减、不修改数据，只做**检测 + 记录 + 告警**，把偏差暴露给人，而不是让系统"自作主张"修数。

## 2. 决策

**触发（`fixedDelay = 300_000L`，即每 5 分钟）**

- 采用 `fixedDelay`（上一轮结束到下一轮开始固定间隔），避免处理耗时超过间隔时任务重入叠加。
- 每个周期扫描 `status='ONGOING'` 的活动（`SeckillActivityMapper.findByStatus`），活动规模小，全量遍历。
- 批次 `traceId = job03-{yyyyMMddHHmmss}` 写入 MDC，与 JOB-01/JOB-02 风格一致，便于日志串联。

**口径（三方比一个恒等式）**

| 量 | 来源 | 说明 |
|---|---|---|
| `total_stock` | `seckill_activity.total_stock` | 活动总库存，常量基准 |
| `redis_stock` | Redis `seckill:stock:{activityId}` | 权威可扣减库存 |
| `mysql_available_stock` | `seckill_activity.available_stock` | MySQL 侧剩余库存 |
| `sold_quantity` | `SUM(orders.quantity) WHERE status IN ('UNPAID','PAID')` | 有效售出件数；`CLOSED_TIMEOUT` 已回补，**不计入** |

```
redis_diff = total_stock - redis_stock          - sold_quantity
mysql_diff = total_stock - mysql_available_stock - sold_quantity
```

两条等式同时为 0 才算一致。`diff != 0` 的含义：

- `diff < 0`（疑似超卖，`result=1`）：实际占用 > 总库存，说明扣减多于基准（超卖/重复扣）。
- `diff > 0`（疑似少卖，`result=2`）：实际占用 < 总库存，说明有库存未扣或回补异常（漏扣/多补）。

**结论落库（append-only）**

- 每个活动每轮写一条 `reconciliation_record`，字段即上述快照 + 双 diff + `result` + `detail` + `created_at`；`insert` 走 `ReconciliationRecordMapper`（`BaseMapper`）。
- `result` 沿用 DDL 的 `tinyint` 口径：**0 一致 / 1 疑似超卖 / 2 疑似少卖**。设计 17.4 的符号值 `CONSISTENT` 映射 `code=0`；符号 `COMPENSATING`（在途豁免）**不占用独立 code**，以 `detail` 标注 + 本轮不告警表达（见下文）。
- 无论是否一致都写记录，保证时序可追溯；查询走 `idx_activity_ctime`。

**告警（只告警，不自动修）**

- `diff != 0` 且该活动**无在途补偿**时：`log.error` 输出完整现场 + `Counter seckill.reconciliation.inconsistent` 计数。
- 该活动存在在途补偿（`stock_compensation` 中 `PENDING/COMPENSATING`）时：建单/回补尚未收敛属**预期中间态**，本轮**豁免告警**，写 `Counter seckill.reconciliation.exempted` + `log.warn`，但仍照常落库。
- Redis key 缺失或非数字：`redis_stock` 落 0（`NOT NULL` 列约束），`detail` 追加 `redis_stock_key_missing`，强制 `log.error` + `Counter seckill.reconciliation.redis_missing`。
- 单活动处理抛异常：`Counter seckill.reconciliation.failed` 计数 + `log.error`，**不影响同批其他活动**（逐活动 try/catch）。
- 任何分支都**不写** `seckill_activity` / `orders` / `stock_compensation`。

## 3. 在途豁免的边界（关键取舍）

在途豁免只统计 **`PENDING` + `COMPENSATING`**，**故意不含 `FAILED`**。

- 若把 `FAILED` 也算作"在途"，则 ADR-019 登记的「Redis 已回补、MySQL 未归还」残窗会被**永久豁免**，对账永远报不出这条已停摆的真实差异。
- `PENDING/COMPENSATING` 是"还在动"的任务，差异大概率自愈；`FAILED` 是"已停摆"的终态，必须暴露。
- 与设计 17.4 措辞（`PENDING/FAILED`）的差异见 §6 拍板结论。

豁免是**逐轮**判断，不跨轮记忆：某轮有在途任务 → 豁免；任务收敛后下一轮 diff 归零 → 一致；任务置 `FAILED` → 下一轮不再豁免 → 告警。设计 17.4 的「连续 2 轮非 0 才告警」在本批**未实现**（见 §6），由 `detail` 中的轮次记录 + 追溯 SQL `[T5]` 事后识别。

## 4. 处置纪律（SOP）

1. **只读**：对账只检测、只记录、只告警，绝不自动修改线上库存或订单状态。
2. **留现场**：先在 `reconciliation_record` 保全双 diff、Redis/MySQL 快照、`detail`、时间。
3. **查根因**：结合 Redis Lua 日志、MQ 消费日志、超时关闭/回补日志定位偏差来源。
4. **人工修复**：差异持续扩大或出现负 diff 时，优先暂停相关活动下单，人工确认后以受审计方式修复并重新对账。
5. **收敛解警**：差异连续多轮归零后，方可解除告警。

> 明确**不做**自动修复：秒杀场景下自动修数极易在根因未明时放大错误（例如把超卖"修"成更大的超卖），且破坏 append-only 审计性。

## 5. 可观测性

| Micrometer Counter | 触发条件 | 处置 |
|---|---|---|
| `seckill.reconciliation.inconsistent` | `diff != 0` 且无在途补偿 | 告警，人工跟进 |
| `seckill.reconciliation.exempted` | `diff != 0` 但有在途补偿 | 观察，多为自愈 |
| `seckill.reconciliation.redis_missing` | Redis key 缺失/非法 | 高优先级排查 |
| `seckill.reconciliation.failed` | 单活动处理异常 | 排查异常栈 |

日志：批次总结 `log.info`（活动数 / 一致数 / 不一致数）；单活动不一致 `log.error` 含 `total/redisStock/mysqlAvailable/sold/redisDiff/mysqlDiff/result`。

## 6. 配置

当前批次参数以常量落实现（与 JOB-01/JOB-02 同风格）；调度线程池已外提，其余参数压测后再外提：

| 参数 | 值 | 位置 |
|---|---|---|
| 对账扫描间隔 | 5min（`fixedDelay`） | `ReconciliationJob#reconcile` |
| 扫描范围 | `status='ONGOING'` | `SeckillActivityMapper.findByStatus` |
| 有效售出状态 | `UNPAID, PAID` | `OrderMapper.sumValidQuantity` |
| 在途豁免状态 | `PENDING, COMPENSATING` | `StockCompensationMapper.countInFlight` |
| `detail` 截断 | 255 | `ReconciliationJob#buildDetail` |
| Redis key 前缀 | `seckill:stock:` | `ReconciliationJob` |
| 调度线程池 | `pool.size=3` + `thread-name-prefix: seckill-sched-`（JOB-01/02/03 各独占线程） | `application.yml`（`spring.task.scheduling`） |

## 7. 拿不准点的拍板结论（2026-10-20）

1. **`result` 值域：DDL `tinyint(0/1/2)` vs 设计符号 `CONSISTENT/COMPENSATING`** → 拍板：**以 DDL `tinyint` 为准**，用 `ReconciliationResult` 枚举承载 0/1/2；`CONSISTENT` 映射 0，`COMPENSATING` 不占 code，改以 `detail` 标注 + 不告警表达。设计 17.4 符号口径已回写对齐。
2. **频率/范围：用户指令 5min/仅 ONGOING vs 设计 17.4 10min/ONGOING+ENDED** → 拍板：**实现按用户指令**（5min、仅 ONGOING），因活动结束后的收敛核验由人工全量对账（`docs/Reconciliation.sql`）承接；设计 17.4 已回写为 5min/ONGOING。在途豁免范围**取 `PENDING/COMPENSATING`、不含 `FAILED`**（理由见 §3）。设计 17.4 的「连续 2 轮非 0」**未在本批实现**，登记为已知限制。
3. **三个 JOB 共用默认单线程调度器** → 拍板：**现在外提**。已配置 `spring.task.scheduling.pool.size=3`（+ `thread-name-prefix: seckill-sched-`）于 `application.yml`，JOB-01/02/03 各独占线程，避免 JOB-03 单轮耗时挤占 JOB-01/02。原「不外提」结论失效，设计 17.10 L8 已回写为「已配置」。