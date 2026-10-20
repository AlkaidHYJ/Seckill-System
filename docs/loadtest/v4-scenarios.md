# v4 压测方案

> 对齐 [v3 压测方案](v3-scenarios.md)。v4 不追求吞吐提升，目标集中在**一致性闭环、可观测、接入层修复**（设计文档 17.1）。
> 本文只定义**怎么测、怎么判**，不填实测数据；实测值由 [v4 压测报告](../v4-压测报告.md) 承接。

## 1. 测试目标

验证 v4 在 v3（token/path 防刷 + Redis 限流、buy QPS 469.5）基础上补齐超时取消 + 三方对账 + 可观测 + accept-count 修复后：

- **场景 1（性能 + 正确性护栏）**：200 线程 buy 受理 QPS 不低于 v3（护栏 ≥ 422 = v3 的 90%），P99 ≤ 476 ms（v3 的 120%），无超卖、无重复、Redis/MySQL 双 diff = 0。
- **场景 2（超时取消 + 回补）**：下单不支付的过期 `UNPAID` 订单在 ≤ 2 个扫描周期内 100% 进入 `CLOSED_TIMEOUT`，Redis 与 MySQL 库存按件数各回补一次。
- **场景 3（支付免关闭）**：已 `PAID` 订单即使 `pay_deadline` 过期也不被 JOB-01 关闭。
- **场景 4（对账检出）**：人为注入 Redis/MySQL 差异后，1 个对账周期内 `reconciliation_record` 出现非 0 diff 并告警，且系统**不修改任何库存数据**。
- **场景 5（accept-count 修复）**：与 v3 同脚本同 200 线程突发建连，连续 5 轮 `Connection refused` 合计 = 0（对比 v3 r1/r3 的 51/33 次）。
- **场景 6（traceId 跨 MQ）**：单笔订单的 traceId 在 API 与 Consumer 两端日志命中且一致。

## 2. 测试前置

1. 启动 MySQL、Redis、RabbitMQ 与当前应用，确认 `/actuator/health` 为 UP。
2. 以 activity 2 为测试活动，状态 `ONGOING`；准备 200 个有效用户与 JWT：`docs/loadtest/users.csv`（每轮重签）。
3. 每轮开始前复位：库存 = 100、清空 `orders`、清 Redis 库存/限购标记/`seckill:creating`，等待 RabbitMQ `seckill.order.queue` 与 `seckill.dlx.queue` 无积压；记录 `available_stock`、`seckill:stock:2`、订单数快照。
4. 后台 JOB 参数保持默认：JOB-01 关闭扫描 `fixedDelay=30s`、批量 100；JOB-02 回补 `fixedDelay=15s`、批量 50；JOB-03 对账 `fixedDelay=5min`、仅扫 `ONGOING`。场景 2/4 若需加速，**只允许临时改数据（回拨 `pay_deadline`）或缩短等待，不允许改代码常量或 DDL**。
5. 确认 `server.tomcat.accept-count=200` 已生效（见 §3.5），且无其他配置源覆盖（application-dev/prod.yml、环境变量、命令行；取证方法同 v1 报告 §6.1）。
6. JMeter 场景文件：场景 1/5 用 `docs/loadtest/v3.jmx`（200 线程同瞬建连，每用户 token + buy）；场景 2/3/4/6 用低并发 `curl`/单线程 JMX，避免与并发场景混合样本。

## 3. 场景定义

### 场景 1：200 线程压测（性能 + 正确性护栏）

- 并发：200 用户，每用户先取 token 再 buy 各 1 次（沿用 v3 请求模型，每轮 400 样本）。
- 采样器口径：**主口径 = buy 受理**（200 样本/轮），与 v3 一致，可直接比较；P99 记录整体与 buy 分项。
- 预期：token=200 全成功；buy 成功 = 100（等于库存）、其余为 2008 库存不足；2006/2013/5xx 均为 0。
- 校验：
  - buy QPS ≥ 422、buy P99 ≤ 476 ms；
  - 超卖 = 0、重复订单 = 0、`total_stock - available_stock - valid_orders = 0`；
  - 收敛后 MySQL `UNPAID=100`、`available_stock=0`、Redis 库存 = 0、`seckill:creating=0`、订单队列 = 0；
  - JOB 运行窗口内 buy P99 波动 ≤ 10%（护栏）。

### 场景 2：下单不支付 → 超时自动关闭 + 库存回补

- 并发：单用户 1 次 buy（低并发），确保订单落库为 `UNPAID`。
- **加速做法（关键）**：`pay_deadline` 由代码硬编码为 `now + 15min`，等待完整 15 分钟太慢。**不污染生产 DDL**——只对该测试订单**回拨自身 `pay_deadline` 列**（数据 UPDATE，不改表结构、不改代码）：
  - 快速版（立即过期，推荐）：
    ```sql
    UPDATE orders SET pay_deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE)
    WHERE order_no = '<orderNo>' AND status = 'UNPAID';
    ```
    随后等待 ≤ 1 个扫描周期（30s）。
  - 忠实版（调小到 60 秒，仍走真实"倒计时"）：
    ```sql
    UPDATE orders SET pay_deadline = DATE_ADD(NOW(), INTERVAL 60 SECOND)
    WHERE order_no = '<orderNo>' AND status = 'UNPAID';
    ```
    随后等待 60s + ≤ 1 个扫描周期。
  - 幂等约束：`AND status='UNPAID'` 保证只对未支付订单生效；回拨前先记录该订单的 `quantity`。
- 预期（JOB-01 → JOB-02）：
  1. JOB-01 扫到该单，CAS `UNPAID → CLOSED_TIMEOUT` 成功（`close_reason=1`、写 `close_time`），**同事务**插入 1 条 `TIMEOUT_REFUND(PENDING)`；
  2. JOB-02 领取并执行回补：Redis 库存 +1、删除该用户限购标记、MySQL `available_stock` +1，任务置 `SUCCESS`。
- 校验：
  - ≤ 2 个扫描周期内 `SELECT ... WHERE status='UNPAID' AND pay_deadline < NOW()` 对该单返回 0 行（对齐 `docs/Reconciliation.sql` 第 7 条）；
  - 关闭数 = 回补数 = 该单 `quantity`（此处 1）；Redis 库存增量 = MySQL `available_stock` 增量 = 1；
  - `stock_compensation` 中该单 `TIMEOUT_REFUND` 恰好 1 条且 `status=SUCCESS`；
  - 日志引用：`Timeout order closed and refund task created, orderNo=...`、`JOB-01 batch finished, scanned=..., closed=...`；计数 `seckill.timeout.closed`、`seckill.compensation.refunded` 各 +1。
- 幂等复验（可选）：手动再触发一次扫描（或重复回拨），Redis 库存不得二次 +1（`seckill:refund:{orderNo}` 标记 + 收尾 CAS 兜底）。

### 场景 3：下单支付 → 不被超时关闭

- 并发：单用户 1 次 buy，落库后立即 `POST /api/orders/{orderNo}/pay`（Body 可省略或 `{"paymentMethod":"MOCK"}`，API-07）。
- 前置：支付成功后，回拨该单 `pay_deadline`（同上快速版）使其"账面过期"，验证 JOB-01 不会误关闭已支付订单。
- 预期：JOB-01 `findExpiredUnpaid` 仅查 `status='UNPAID'`，`PAID` 订单不在扫描范围；日志若触及也应为 `Timeout close CAS skipped (paid or already closed), orderNo=...`（CAS 影响行数 = 0）。
- 校验：
  - 订单保持 `status='PAID'`，`close_time` 为 NULL；
  - `stock_compensation` 无该单 `TIMEOUT_REFUND`；
  - Redis/MySQL 库存不因该单发生回补（无 +1）。

### 场景 4：人为造 Redis/MySQL 差异 → 对账告警

- **前置检查（注入前必须全为 0，否则本轮会走豁免告警、达不到预期）**：
  ```sql
  SELECT COUNT(*) FROM stock_compensation WHERE status IN ('PENDING','COMPENSATING');
  ```
  ```text
  ZCARD seckill:creating
  ```
  两者都为 0 才允许注入；同时确认 activity 2 为 `ONGOING`（在途任务会触发**豁免告警**，见 ADR-020 §3）。
- 记录基准：`total_stock`、Redis `seckill:stock:2`、MySQL `available_stock`、`SUM(quantity)`（`UNPAID,PAID`），计算基线双 diff。
- **注入规则：每轮只改一个变量**（Redis **或** MySQL 二选一），不得同时造两种差异，便于归因与恢复。
  - 方式 A（只改 Redis，使 `redis_diff = total - redis_stock - sold ≠ 0`）：
    ```text
    redis-cli SET seckill:stock:2 <baseline-1>   # 固定为错值（少卖方向）
    redis-cli DECR seckill:stock:2               # 或制造 ±1 偏差
    ```
  - 方式 B（只改 MySQL，使 `mysql_diff = total - available_stock - sold ≠ 0`）：
    ```sql
    UPDATE seckill_activity SET available_stock = available_stock - 1 WHERE id = 2;
    ```
- 预期：下一个对账周期（≤ 5 min）内 JOB-03 检出差异并告警：
  - `reconciliation_record` 最新一行该活动对应 `*_diff ≠ 0`、`result ≠ 0`（1 疑似超卖 / 2 疑似少卖）；
  - 日志 ERROR：`JOB-03 inconsistency detected, activityId=2, total=..., redisStock=..., mysqlAvailable=..., sold=..., redisDiff=..., mysqlDiff=..., result=...`；
  - 计数 `seckill.reconciliation.inconsistent` ≥ 1（无在途补偿时）；`seckill.reconciliation.diff.total{type=redis|mysql}` 对应 +1。
- 校验（只告警口径）：
  - **系统未修改任何库存数据**：除我们手工改动的那一侧外，`available_stock`、`orders`、`stock_compensation` 相对注入前无变化；
  - **人工恢复用对应反向操作**：
    - 方式是 A → `redis-cli SET seckill:stock:2 <baseline>`；
    - 方式是 B → `UPDATE seckill_activity SET available_stock = available_stock + 1 WHERE id = 2;`
    - 再等 ≤ 5 min 观察双 diff 归零、`result=0`。

### 场景 5：200 线程突发建连无 Connection refused（accept-count 修复）

- 脚本：`docs/loadtest/v3.jmx`（200 线程 / Ramp-up 1s / 同步计时器 200 同瞬建连，token + buy）。
- **轮次（关键）**：v3 的建连拒绝是**偶发**（r1/r3 有、r2/r4 无），单轮不足以定论，**连续跑 5 轮（r0~r4）取稳定结论**。
- 统计：从每轮 jtl 统计 `HttpHostConnectException`（`Connection refused`）次数，逐轮记录。
- 预期：5 轮 refused 合计 = 0（对比 v3 r1/r3 的 51/33 次）。
- 校验与边界：
  - refused 归零不得引入 5xx、超卖、重复、diff 非 0（accept 队列只影响建连，不应改变业务语义）；
  - 若调大后仍出现 refused：按设计 17.7 归因转向**本机环境候选**（临时端口耗尽 / Windows listen backlog 实现上限），记录为环境限制关闭此项，**不改业务代码兜底**。

### 场景 6：traceId 跨 MQ 验证（Producer ↔ Consumer）

- 请求：发起 1 次 buy（可携带**固定** `X-Request-Id: v4-trace-1` 使 traceId 可预期；`TraceIdFilter` 透传该 Header 作为 traceId 并回写 `X-Trace-Id`）。记录该笔订单的 `orderNo`。
- 链路约定（ADR-021 §2.1）：`TraceIdFilter` 写 MDC → Producer `MessagePostProcessor` 把 MDC traceId 写入 AMQP header `traceId` → Consumer 取 header 放 MDC 再处理；logback pattern 每行渲染 `[traceId=%X{traceId:-N/A}]`。
- **永久日志（v4 新增，非临时代码，无需改任何配置）**：
  - Producer `send()` 成功：`发送订单消息: orderNo={}, activityId={}, traceId={}`
  - Consumer `consume()` 进入业务逻辑前：`消费订单消息: orderNo={}, traceId={}`
- 验证（grep 一条 `orderNo` 即可）：
  1. 用该笔订单的 `orderNo` 同时 grep Producer 与 Consumer 两行：
     ```powershell
     Select-String -Path logs/flash-sale-system.log -Pattern '<orderNo>'
     ```
  2. 命中 `发送订单消息: orderNo=<orderNo>, activityId=..., traceId=<T>` 与 `消费订单消息: orderNo=<orderNo>, traceId=<T>`。
  3. **一致性判定**：两行 `traceId=` 字符串**完全相同** → 跨 MQ 贯通成立；Consumer 行若为 `N/A` 视为断链、不通过。

## 4. 压测轮次

| 轮次 | 用途 | 场景 | 用户数 | 库存 | 结果文件 |
|---|---|---|---:|---:|---|
| r0 | 预热 | 场景 1 / 场景 5 | 200 | 100 | `results/v4_r0.jtl` |
| r1 | 正式 | 场景 1 / 场景 5 | 200 | 100 | `results/v4_r1.jtl` |
| r2 | 正式 | 场景 1 / 场景 5 | 200 | 100 | `results/v4_r2.jtl` |
| r3 | 正式 | 场景 1 / 场景 5 | 200 | 100 | `results/v4_r3.jtl` |
| r4 | 正式 | 场景 1 / 场景 5 | 200 | 100 | `results/v4_r4.jtl` |
| 功能轮 | 功能验证 | 场景 2 / 3 / 4 / 6（低并发，独立样本） | 1 | 100 | 手工/单线程，不混入 QPS 统计 |

场景 1 性能取 r1~r4 中位数（r0 预热）；场景 5 建连拒绝按 r0~r4 **5 轮合计**判定；功能场景独立执行，不参与 QPS/延迟统计。

## 5. 结果判定

- **性能护栏**：buy QPS ≥ 422（v3 的 90%）、buy P99 ≤ 476 ms（v3 的 120%）；JOB 触发窗口内 buy P99 波动 ≤ 10%。
- **正确性**：超卖 = 0、重复订单 = 0、`total_stock - available_stock - valid_orders = 0`、双 diff = 0。
- **超时取消**：过期 `UNPAID` 在 ≤ 2 个扫描周期内清零；回补增量 = Σquantity，`TIMEOUT_REFUND` 幂等且 `SUCCESS`。
- **支付免关闭**：`PAID` 订单 `close_time` 为 NULL、无 `TIMEOUT_REFUND`。
- **对账检出**：1 个周期内 `reconciliation_record` 非 0 + ERROR 告警，且系统未改任何库存数据。
- **accept-count**：r0~r4 五轮 refused 合计 = 0。
- **traceId**：固定 traceId 在 API 与 Consumer 日志命中且一致。
- 任何 5xx、连接失败、库存负数、重复订单、diff 非 0、超时未清零或 traceId 断链均判定为失败。

## 6. 建议命令

```powershell
# 基础设施与应用
 docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health

# 场景 1 / 5：非 GUI 执行 v3.jmx（正式轮示例）
jmeter -n -t docs/loadtest/v3.jmx `
  -JHOST=localhost -JPORT=8080 -JACTIVITY_ID=2 `
  -JTHREADS=200 -JRAMP_UP=1 -JLOOPS=1 `
  -l results/v4_r1.jtl -e -o results/html_v4_r1

# 汇总 JTL（QPS/P99/refused）
python docs/loadtest/analyze_jtl.py results/v4_r1.jtl

# 场景 6：固定 traceId 下单 + grep 贯通
curl -s -H "Authorization: Bearer <jwt>" -H "X-Request-Id: v4-trace-1" `
  -H "X-Seckill-Token: <token>" -X POST `
  "http://localhost:8080/api/seckill/activities/2/buy/<path>"
Select-String -Path logs/flash-sale-system.log -Pattern 'traceId=v4-trace-1'

# 场景 2：回拨 pay_deadline（快速版）
#   UPDATE orders SET pay_deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE)
#   WHERE order_no='<orderNo>' AND status='UNPAID';

# 场景 4：注入 Redis 差异（验证后需人工恢复）
#   redis-cli SET seckill:stock:2 <baseline-1>
```

> 说明：本文档给出命令与口径，**不执行压测、不填实际数据**；`v3.jmx` 沿用 v3 请求模型与业务码采集，无需改动。