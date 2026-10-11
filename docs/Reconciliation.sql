USE `seckill`;
SET NAMES utf8mb4;

-- 1. 有效订单数：统计每个活动处于 UNPAID 或 PAID 的订单数。
-- 预期结果：正常活动返回有效订单数；没有有效订单的活动返回 0。
-- 异常判断：出现 NULL 不应发生；订单数异常增长需结合流量、库存扣减和 MQ 消费日志排查。
SELECT
  a.`id` AS `activity_id`,
  a.`goods_name`,
  COUNT(o.`id`) AS `valid_order_count`,
  COALESCE(SUM(o.`quantity`), 0) AS `valid_sold_quantity`
FROM `seckill_activity` a
LEFT JOIN `orders` o
  ON o.`activity_id` = a.`id`
 AND o.`status` IN ('UNPAID', 'PAID')
GROUP BY a.`id`, a.`goods_name`
ORDER BY a.`id`;

-- 2. MySQL 剩余库存：读取活动表中的 available_stock 快照。
-- 预期结果：available_stock >= 0，且不超过 total_stock。
-- 异常判断：available_stock < 0 表示超卖或数据损坏；available_stock > total_stock 表示库存回补或初始化异常。
SELECT
  `id` AS `activity_id`,
  `goods_name`,
  `total_stock`,
  `available_stock`,
  CASE
    WHEN `available_stock` < 0 THEN '异常: 负库存'
    WHEN `available_stock` > `total_stock` THEN '异常: 剩余库存超过总库存'
    ELSE '正常'
  END AS `check_result`
FROM `seckill_activity`
ORDER BY `id`;

-- 3. 是否负库存：只返回负库存活动。
-- 预期结果：0 行。
-- 异常判断：返回任意记录都表示库存扣减、回补或数据修复流程存在严重异常，应立即告警并暂停自动修正。
SELECT
  `id` AS `activity_id`,
  `goods_name`,
  `total_stock`,
  `available_stock`,
  '异常: 负库存' AS `anomaly`
FROM `seckill_activity`
WHERE `available_stock` < 0
ORDER BY `id`;

-- 4. 同用户同活动重复订单：按 activity_id + user_id 分组检查有效订单。
-- 预期结果：0 行；uk_activity_user 正常时数据库层应阻止重复记录。
-- 异常判断：返回记录表示历史脏数据、约束未生效或约束变更，需先停止相关下单并核查数据。
SELECT
  `activity_id`,
  `user_id`,
  COUNT(*) AS `duplicate_order_count`,
  GROUP_CONCAT(`order_no` ORDER BY `id` SEPARATOR ',') AS `order_nos`
FROM `orders`
WHERE `status` IN ('UNPAID', 'PAID')
GROUP BY `activity_id`, `user_id`
HAVING COUNT(*) > 1
ORDER BY `activity_id`, `user_id`;

-- 5. MySQL 侧库存恒等式对账：有效售出件数 + MySQL 剩余库存 = 活动总库存。
-- 预期结果：0 行；每个活动的 mysql_diff 应为 0。
-- 异常判断：mysql_diff > 0 表示少卖、库存未扣或回补遗漏；mysql_diff < 0 表示疑似超卖或重复扣减。
-- Redis 差异必须由任务读取 Redis 后写入 reconciliation_record.redis_diff，本 SQL 无法直接读取 Redis。
SELECT
  a.`id` AS `activity_id`,
  a.`goods_name`,
  a.`total_stock`,
  a.`available_stock`,
  COUNT(o.`id`) AS `valid_order_count`,
  COALESCE(SUM(o.`quantity`), 0) AS `sold_quantity`,
  COALESCE(SUM(o.`quantity`), 0) + a.`available_stock` AS `mysql_stock_total`,
  a.`total_stock` - (COALESCE(SUM(o.`quantity`), 0) + a.`available_stock`) AS `mysql_diff`,
  CASE
    WHEN a.`total_stock` = COALESCE(SUM(o.`quantity`), 0) + a.`available_stock` THEN 'PASS: MySQL 口径一致'
    ELSE '异常: MySQL 口径不一致'
  END AS `check_result`
FROM `seckill_activity` a
LEFT JOIN `orders` o
  ON o.`activity_id` = a.`id`
 AND o.`status` IN ('UNPAID', 'PAID')
GROUP BY a.`id`, a.`goods_name`, a.`total_stock`, a.`available_stock`
HAVING `mysql_diff` <> 0
ORDER BY a.`id`;

-- 6. 按活动分组的对账汇总：汇总订单、库存、Redis 差异和 MySQL 差异。
-- 预期结果：正常活动的 redis_diff 和 mysql_diff 均为 0，negative_stock_flag=0。
-- 异常判断：任一差异非 0或负库存标记为 1，都需要进一步核查；redis_diff 取最近一次已落库的 Redis 快照。
SELECT
  a.`id` AS `activity_id`,
  a.`goods_name`,
  a.`status` AS `activity_status`,
  a.`total_stock`,
  a.`available_stock`,
  COUNT(o.`id`) AS `valid_order_count`,
  COALESCE(SUM(o.`quantity`), 0) AS `sold_quantity`,
  CASE WHEN a.`available_stock` < 0 THEN 1 ELSE 0 END AS `negative_stock_flag`,
  latest.`redis_diff`,
  latest.`mysql_diff`,
  CASE
    WHEN a.`available_stock` < 0 THEN '异常: 负库存'
    WHEN latest.`redis_diff` IS NULL OR latest.`mysql_diff` IS NULL THEN '异常: 缺少对账快照'
    WHEN latest.`redis_diff` <> 0 OR latest.`mysql_diff` <> 0 THEN '异常: 库存差异'
    ELSE 'PASS: 对账一致'
  END AS `check_result`
FROM `seckill_activity` a
LEFT JOIN `orders` o
  ON o.`activity_id` = a.`id`
 AND o.`status` IN ('UNPAID', 'PAID')
LEFT JOIN (
  SELECT r1.`activity_id`, r1.`redis_diff`, r1.`mysql_diff`
  FROM `reconciliation_record` r1
  INNER JOIN (
    SELECT `activity_id`, MAX(`id`) AS `id`
    FROM `reconciliation_record`
    GROUP BY `activity_id`
  ) r2 ON r2.`id` = r1.`id`
) latest ON latest.`activity_id` = a.`id`
GROUP BY a.`id`, a.`goods_name`, a.`status`, a.`total_stock`, a.`available_stock`, latest.`redis_diff`, latest.`mysql_diff`
ORDER BY a.`id`;

-- 7. 超时未支付但状态仍为 UNPAID：使用订单自身 pay_deadline 判断。
-- 预期结果：0 行。
-- 异常判断：返回记录表示定时扫描或补偿任务未及时处理；应执行幂等 CAS 关闭和补偿，禁止直接批量改状态绕过业务逻辑。
SELECT
  `id`,
  `order_no`,
  `user_id`,
  `activity_id`,
  `create_time`,
  `pay_deadline`,
  `status`,
  TIMESTAMPDIFF(SECOND, `pay_deadline`, CURRENT_TIMESTAMP) AS `overdue_seconds`
FROM `orders`
WHERE `status` = 'UNPAID'
  AND `pay_deadline` < CURRENT_TIMESTAMP
ORDER BY `pay_deadline`, `id`;

-- 建议跑批频率：
-- 1. 秒杀进行中：每 1 分钟执行一次第 3、5、6、7 项，或按订单/MQ 告警实时触发。
-- 2. 活动结束后：立即执行一次全量对账，之后至少每 5 分钟执行一次，持续到无差异。
-- 3. 日常低峰：每天执行一次全量对账并保留结果，便于审计和趋势分析。
--
-- 差异处理建议：
-- 1. 先只读记录并告警，保留 activity_id、订单范围、Redis stock、MySQL stock、执行时间和错误日志。
-- 2. 负库存、疑似超卖或差异持续扩大时，先暂停活动下单，核对 Redis Lua、MQ 消费、超时关闭和库存回补日志。
-- 3. 超时 UNPAID 使用 status=UNPAID 的 CAS 更新关闭，只有 CAS 成功的线程执行一次库存回补，保证幂等。
-- 4. 不直接用对账 SQL 自动修改库存；确认根因后再通过受审计的人工修复或补偿任务处理，并重新对账。
-- 5. 差异稳定为 0 后，再恢复活动或解除告警。
