-- =====================================================================
-- JOB-03 三方对账：运行时语句（与 ReconciliationJob / *Mapper 注解 SQL 一一对应）
-- 说明：
--   1. 本文件是「文档化」参考，服务于两件事：
--      a) 逐条列出 JOB-03 每个调度周期真正执行的 SQL，便于审阅与 EXPLAIN 核验；
--      b) 提供对账结果的追溯查询，供人工排障与审计使用。
--   2. 项目无 XML mapper，运行时 SQL 全部写在 Mapper 注解里（下方标注来源方法）。
--   3. 对账是只读 + append-only 落表：除 reconciliation_record 的 INSERT 外，
--      不对 seckill_activity / orders / stock_compensation 做任何写操作。
--      禁止用本文件的查询结果直接 UPDATE 库存（只告警、不自动修）。
-- =====================================================================

USE `seckill`;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 第一部分：JOB-03 运行时语句（每个调度周期按序执行）
-- ---------------------------------------------------------------------

-- [R1] 取当前 ONGOING 活动清单。来源：SeckillActivityMapper.findByStatus
--      走主键/状态过滤，活动规模小，直接全量遍历。
SELECT *
FROM `seckill_activity`
WHERE `status` = 'ONGOING'
ORDER BY `id` ASC;

-- [R2] 读单活动有效售出件数（对每个 ONGOING 活动执行一次）。
--      来源：OrderMapper.sumValidQuantity
--      CLOSED_TIMEOUT 已回补库存，不计入 sold；COALESCE 保证无有效订单时返回 0 而非 NULL。
SELECT COALESCE(SUM(`quantity`), 0) AS `sold_quantity`
FROM `orders`
WHERE `activity_id` = ?            -- activityId
  AND `status` IN ('UNPAID', 'PAID');

-- [R3] 读单活动在途补偿任务数（PENDING/COMPENSATING），决定本轮是否豁免告警。
--      来源：StockCompensationMapper.countInFlight
--      注意：故意不含 FAILED —— FAILED 属于「已停摆的残窗」，必须让对账 diff 暴露出来，
--      否则 ADR-019 所述「Redis 已补、MySQL 未还」的差异会被永久豁免。
SELECT COUNT(*) AS `in_flight`
FROM `stock_compensation`
WHERE `activity_id` = ?            -- activityId
  AND `status` IN ('PENDING', 'COMPENSATING');

-- [R4] Redis 读库存（非 SQL）：StringRedisTemplate.get("seckill:stock:{activityId}")
--      key 缺失或非数字 → redis_stock 落 0，detail 追加 redis_stock_key_missing，
--      并强制 ERROR 日志 + redis_missing 计数。

-- [R5] 写一条对账结果（append-only）。来源：ReconciliationRecordMapper.insert（BaseMapper）
--      result 取值：0 一致 / 1 疑似超卖(diff<0) / 2 疑似少卖(diff>0)。
--      detail 形如：redis_diff=x,mysql_diff=y[,redis_stock_key_missing][,in_flight_compensation=n[(exempted)]]
INSERT INTO `reconciliation_record`
  (`activity_id`, `redis_stock`, `mysql_available_stock`, `sold_quantity`,
   `redis_diff`, `mysql_diff`, `result`, `detail`, `created_at`)
VALUES
  (?, ?, ?, ?, ?, ?, ?, ?, NOW());

-- 口径说明（与应用内计算一致）：
--   redis_diff = total_stock - redis_stock          - sold_quantity
--   mysql_diff = total_stock - mysql_available_stock - sold_quantity
--   redis_diff 与 mysql_diff 同时为 0 → result=0；任一 < 0 → result=1；否则 → result=2。

-- ---------------------------------------------------------------------
-- 第二部分：追溯查询（人工排障 / 审计，非任务运行时执行）
-- ---------------------------------------------------------------------

-- [T1] 某活动最近 20 条对账记录（含豁免轮次）。来源：ReconciliationRecordMapper.findRecent
--      走 idx_activity_ctime；同时按 id 倒序，避免同秒多轮次序歧义。
SELECT *
FROM `reconciliation_record`
WHERE `activity_id` = ?
ORDER BY `created_at` DESC, `id` DESC
LIMIT 20;

-- [T2] 每个活动的最新一条对账快照，一眼看当下是否一致。
--      与 docs/Reconciliation.sql 第 6 项同构，便于交叉核对。
SELECT r.`activity_id`, r.`redis_stock`, r.`mysql_available_stock`, r.`sold_quantity`,
       r.`redis_diff`, r.`mysql_diff`, r.`result`, r.`detail`, r.`created_at`
FROM `reconciliation_record` r
INNER JOIN (
  SELECT `activity_id`, MAX(`id`) AS `id`
  FROM `reconciliation_record`
  GROUP BY `activity_id`
) latest ON latest.`id` = r.`id`
ORDER BY r.`activity_id`;

-- [T3] 全部「非一致且非豁免」的告警轮次（result<>0 且 detail 不含 exempted）。
--      这是运维真正需要跟进的清单；exempted 轮次属预期噪声，另行归集。
SELECT `activity_id`, `redis_stock`, `mysql_available_stock`, `sold_quantity`,
       `redis_diff`, `mysql_diff`, `result`, `detail`, `created_at`
FROM `reconciliation_record`
WHERE `result` <> 0
  AND (`detail` IS NULL OR `detail` NOT LIKE '%(exempted)%')
ORDER BY `created_at` DESC, `id` DESC;

-- [T4] 近 24h 内出现过不一致的活动汇总，用于判断差异是否收敛。
SELECT `activity_id`,
       COUNT(*)                                   AS `inconsistent_rounds`,
       MIN(`created_at`)                          AS `first_seen`,
       MAX(`created_at`)                          AS `last_seen`,
       SUM(CASE WHEN `redis_diff` < 0 OR `mysql_diff` < 0 THEN 1 ELSE 0 END) AS `oversold_rounds`,
       SUM(CASE WHEN `redis_diff` > 0 OR `mysql_diff` > 0 THEN 1 ELSE 0 END) AS `undersold_rounds`
FROM `reconciliation_record`
WHERE `result` <> 0
  AND `created_at` >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY `activity_id`
ORDER BY `inconsistent_rounds` DESC, `activity_id`;

-- [T5] 连续两轮及以上非零且无在途补偿的活动：按「设计 17.4 的持续告警口径」筛选。
--      思路：取每活动最近两条非豁免的非一致记录，若其均为非 0 则视为持续异常。
SELECT `activity_id`, COUNT(*) AS `trailing_inconsistent`
FROM (
  SELECT `activity_id`, `created_at`
  FROM `reconciliation_record`
  WHERE `result` <> 0
    AND (`detail` IS NULL OR `detail` NOT LIKE '%(exempted)%')
    AND `created_at` >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
) recent
GROUP BY `activity_id`
HAVING COUNT(*) >= 2
ORDER BY `trailing_inconsistent` DESC;

-- ---------------------------------------------------------------------
-- 第三部分：处置纪律（与 ADR-020 一致）
--   1. 对账只检测并记录，绝不自动修改线上库存或订单状态。
--   2. 先在 reconciliation_record 里保留现场（双 diff、Redis/MySQL 快照、detail、时间），
--      再结合 Redis Lua、MQ 消费、超时关闭、库存回补日志排查根因。
--   3. 差异持续扩大或出现负 diff 时，优先暂停相关活动下单，人工确认后再受审计地修复并重新对账。
--   4. 差异收敛为 0（连续多轮一致）后，方可解除告警。
-- ---------------------------------------------------------------------