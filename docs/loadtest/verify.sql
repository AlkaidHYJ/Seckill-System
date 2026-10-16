-- =============================================================
-- verify.sql  压测后库存/订单对账（活动 id=2，初始库存 100）
-- 执行（全部为只读 SELECT，可随时重复跑）：
--   PowerShell: Get-Content docs\loadtest\verify.sql -Raw | docker exec -i flash-sale-mysql mysql -t -uroot -p<密码> seckill
--   bash:       docker exec -i flash-sale-mysql mysql -t -uroot -p<密码> seckill < docs/loadtest/verify.sql
-- 结论口径: a/b/c/d 全部符合期望 => 无超卖无少卖；e/f 必须为 0 行/0 条
-- =============================================================

USE `seckill`;

-- -------------------------------------------------------------
-- a. 有效订单数（UNPAID + PAID 视为占用库存的有效单，CLOSED_TIMEOUT 已回补不算）
--    期望: = 100（库存打满）；若 > 初始库存(total_stock) 即为超卖，必须为 0 容忍
-- -------------------------------------------------------------
SELECT COUNT(*) AS valid_order_count
FROM `orders`
WHERE `activity_id` = 2
  AND `status` IN ('UNPAID', 'PAID');

-- -------------------------------------------------------------
-- b. 剩余库存（MySQL 兜底库存，落库时条件扣减 available_stock >= 1）
--    期望: = 0（100 件全卖出）；若 < 0 说明条件扣减失效
-- -------------------------------------------------------------
SELECT `available_stock` AS remaining_stock
FROM `seckill_activity`
WHERE `id` = 2;

-- -------------------------------------------------------------
-- c. 初始库存（对账基准，压测前后都应为 100）
--    期望: = 100；若被 prepare.sql 之外的操作改动，先复原再压
-- -------------------------------------------------------------
SELECT `total_stock` AS initial_stock
FROM `seckill_activity`
WHERE `id` = 2;

-- -------------------------------------------------------------
-- d. diff 对账：初始库存 - 剩余库存 - 有效订单数
--    期望: diff = 0（三数自洽，既无超卖也无少卖）
--    diff > 0 => 少卖（库存扣了但订单没落库 / 订单丢失，需查事务回滚与唯一索引冲突路径）
--    diff < 0 => 超卖（订单多于库存，最严重问题）
-- -------------------------------------------------------------
SELECT a.total_stock                                  AS initial_stock,
       a.available_stock                              AS remaining_stock,
       COALESCE(o.valid_orders, 0)                    AS valid_order_count,
       a.total_stock - a.available_stock - COALESCE(o.valid_orders, 0) AS diff
FROM `seckill_activity` a
LEFT JOIN (
    SELECT `activity_id`, COUNT(*) AS valid_orders
    FROM `orders`
    WHERE `status` IN ('UNPAID', 'PAID')
    GROUP BY `activity_id`
) o ON o.activity_id = a.id
WHERE a.id = 2;

-- -------------------------------------------------------------
-- e. 负库存检查（全库所有活动）
--    期望: 返回 0 行；任何一行都是防超卖失效的证据
-- -------------------------------------------------------------
SELECT `id` AS activity_id, `total_stock`, `available_stock`
FROM `seckill_activity`
WHERE `available_stock` < 0;

-- -------------------------------------------------------------
-- f. 重复订单检查（同一活动同一用户多单，或同一订单号多单）
--    期望: 两个子查询都返回 0 行（uk_activity_user / uk_order_no 生效）
-- -------------------------------------------------------------
-- f-1 同一用户在同一活动重复下单
SELECT `activity_id`, `user_id`, COUNT(*) AS cnt
FROM `orders`
WHERE `activity_id` = 2
GROUP BY `activity_id`, `user_id`
HAVING COUNT(*) > 1;

-- f-2 业务订单号重复（唯一索引被绕过的兜底检查）
SELECT `order_no`, COUNT(*) AS cnt
FROM `orders`
WHERE `activity_id` = 2
GROUP BY `order_no`
HAVING COUNT(*) > 1;

-- -------------------------------------------------------------
-- 附：下单总量与失败原因分布（辅助判断错误率是否来自"库存不足"这类正常业务拒绝）
--    期望: total_requests ≈ 200，其中成功 100、库存不足 100；CLOSED_TIMEOUT 应为 0（未触发超时任务）
-- -------------------------------------------------------------
SELECT `status`, COUNT(*) AS cnt
FROM `orders`
WHERE `activity_id` = 2
GROUP BY `status`
ORDER BY `status`;
