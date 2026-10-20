# ADR-019：超时取消与库存回补幂等

- 状态：已采纳
- 日期：2026-10-20
- 范围：v4 JOB-01 超时扫表关闭（`TimeoutCloseJob`）、JOB-02 库存补偿执行（`StockCompensationJob`）、`refund_stock.lua`
- 不包含：三方对账任务（下一批）、差异自动修复、RabbitMQ TTL/DLX 延迟链路

## 1. 背景

v2/v3 的订单落库后若用户不支付，过期 `UNPAID` 订单既不关闭也不回补，占用的 Redis 库存、Redis 限购标记和 MySQL `available_stock` 永不释放。v4 设计 17.3/17.5 要求：超时收敛、回补可恢复、全程幂等，且不引入第二套超时触发链路。

## 2. 决策

**关闭（JOB-01，`fixedDelay=30s`）**

- 扫描 `status='UNPAID' AND pay_deadline < NOW()` 每批 `LIMIT 100`，命中 `idx_status_deadline`。
- 逐单 CAS：`UPDATE orders SET status='CLOSED_TIMEOUT', close_time=NOW(), close_reason=1 WHERE order_no=? AND status='UNPAID'`；影响行数=1 才发生迁移，与支付的双向 CAS（`payIfUnpaid WHERE status='UNPAID'`）保证关闭和支付只有一个成功。
- CAS 成功与 `TIMEOUT_REFUND(PENDING)` 任务插入在**同一事务**原子提交（`TimeoutCloseService`），消除「已关闭但无任务」的孤儿窗口；唯一键 `uk_compensation_order_type` 兜住并发重复建任务。

**回补（JOB-02，`fixedDelay=15s`）**

- 扫描 `status='PENDING' AND next_retry_at <= NOW()` 每批 `LIMIT 50`；逐单先 CAS 领取 `PENDING→COMPENSATING`，领取失败直接跳过。
- 动作顺序：Redis 回补 Lua（`INCR seckill:stock` + `DEL seckill:user` 限购标记 + 写 `seckill:refund:{orderNo}` 标记）→ MySQL `available_stock+1`（条件 `available_stock < total_stock` 防超还）与 `COMPENSATING→SUCCESS` **同事务**（`StockRefundService`）。
- 失败：`retry_count+1`，按 10s/30s/2min 指数退避回到 `PENDING`；累计 3 次置 `FAILED` 并 `log.error` 告警。`FAILED` 不跳过、不改数，等人工介入。
- 崩溃恢复：领取后卡死超过 5 分钟的 `COMPENSATING` 由 `reclaimStale` 退回 `PENDING` 重新调度。

## 3. 幂等三重保障

| 层 | 机制 | 重复触发的结果 |
|---|---|---|
| 创建幂等 | 关闭 CAS 影响行数=1 + `uk(order_no, compensation_type)` | 同一订单只存在一份 `TIMEOUT_REFUND` |
| 执行幂等 | `PENDING→COMPENSATING` 领取 CAS + `COMPENSATING→SUCCESS` 收尾 CAS；收尾 CAS 失败则 DB 事务整体回滚 | 重复调度/多执行者只有一个能完结 |
| 动作幂等 | `seckill:refund:{orderNo}` 标记（TTL 24h）使 Lua 重放返回 1 跳过 INCR/DEL；MySQL 归还是 `available_stock < total_stock` 条件更新且与置 SUCCESS 同事务 | 「Redis 已补、DB 未成」重试时只重做 MySQL 步，库存不重复增加 |

## 4. 原子性与故障策略

- Redis Lua 成功但 DB 事务回滚：重试时标记挡住重复 INCR，仅重放 MySQL 步，链路收敛。
- 重试耗尽置 `FAILED`：存在「Redis 已回补、MySQL 未归还」的残窗，本设计**只告警不自动修复**，差异由下一批三方对账任务检出（`mysql_diff` 口径）。
- Redis 不可用：回补失败进入退避重试，不降级为直写 MySQL 库存（延续 Redis 权威源 + fail-close 原则）。

## 5. 配置

当前批次参数以常量落实现（与 `CreatingCompensationJob` 同风格），压测后再外提为配置项：

| 参数 | 值 | 位置 |
|---|---|---|
| 关闭扫描间隔 / 批量 | 30s / 100 | `TimeoutCloseJob` |
| 补偿扫描间隔 / 批量 | 15s / 50 | `StockCompensationJob` |
| 退避序列 / 最大重试 | 10s、30s、2min / 3 | `StockCompensationJob` |
| refund 标记 TTL | 24h | `StockCompensationJob` |
| COMPENSATING 判死阈值 | 5min | `StockCompensationMapper.reclaimStale` |

## 6. 拿不准点的拍板结论（2026-10-20）

1. **status 数值 vs 枚举** → 拍板：**保持 DDL 字符串枚举，不改表**。`0/1/2` 仅作口头映射（0≈PENDING、1≈SUCCESS、2≈FAILED）。
2. **同事务 vs 设计的异事务+孤儿补扫** → 拍板：**接受同事务简化**，设计文档 17.3/17.5 已回写对齐，孤儿关闭单补扫方案作废。
3. **调度参数与判死阈值是否外提** → 拍板：**暂不外提**，连同 `FAILED` 残窗、无 `user_id` 列等一并登记在设计文档「17.10 已知限制」（L1~L5）。
