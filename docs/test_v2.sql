USE `seckill`;
SET NAMES utf8mb4;

-- 测试说明：测试 1、4 包含预期失败 SQL。
-- 请使用 mysql --force 执行，否则客户端遇到预期错误后会停止后续语句。

-- ===== Test 1 =====
-- uk_activity_user：重复插入同一用户、同一活动，预期报 Duplicate entry
DELETE FROM `orders` WHERE `activity_id` = 910001 OR `user_id` = 910001;
DELETE FROM `stock_compensation` WHERE `activity_id` = 910001;
DELETE FROM `seckill_activity` WHERE `id` = 910001;
DELETE FROM `users` WHERE `id` = 910001;

INSERT INTO `users` (`id`, `username`, `password`)
VALUES (910001, 'test_unique_user', 'test-password');

INSERT INTO `seckill_activity`
  (`id`, `goods_name`, `original_price`, `seckill_price`, `total_stock`, `available_stock`, `limit_per_user`, `start_time`, `end_time`, `status`)
VALUES
  (910001, '测试商品-唯一索引', 10.00, 1.00, 2, 2, 1,
   NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR, 'ONGOING');

INSERT INTO `orders`
  (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`, `amount`, `pay_deadline`)
VALUES
  ('TEST-UQ-910001-A', 910001, 910001, '测试商品-唯一索引', 1.00, 1, 1.00, NOW() + INTERVAL 15 MINUTE);

-- 预期错误：Duplicate entry，命中 uk_activity_user
INSERT INTO `orders`
  (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`, `amount`, `pay_deadline`)
VALUES
  ('TEST-UQ-910001-B', 910001, 910001, '测试商品-唯一索引', 1.00, 1, 1.00, NOW() + INTERVAL 15 MINUTE);

SELECT CASE
         WHEN COUNT(*) = 1 THEN 'PASS: uk_activity_user 已阻止重复下单'
         ELSE 'FAIL: 重复订单已写入'
       END AS `result`
FROM `orders`
WHERE `order_no` = 'TEST-UQ-910001-A';

DELETE FROM `orders` WHERE `activity_id` = 910001 OR `user_id` = 910001;
DELETE FROM `stock_compensation` WHERE `activity_id` = 910001;
DELETE FROM `seckill_activity` WHERE `id` = 910001;
DELETE FROM `users` WHERE `id` = 910001;

-- ===== Test 2 =====
-- idx_status_deadline：EXPLAIN 超时扫描，预期 type=range，key=idx_status_deadline
START TRANSACTION;

DELETE FROM `orders` WHERE `activity_id` = 910002 OR `user_id` = 910002;
DELETE FROM `stock_compensation` WHERE `activity_id` = 910002;
DELETE FROM `seckill_activity` WHERE `id` = 910002;
DELETE FROM `users` WHERE `id` = 910002;

INSERT INTO `users` (`id`, `username`, `password`)
VALUES (910002, 'test_explain_user', 'test-password');

INSERT INTO `seckill_activity`
  (`id`, `goods_name`, `original_price`, `seckill_price`, `total_stock`, `available_stock`, `limit_per_user`, `start_time`, `end_time`, `status`)
VALUES
  (910002, '测试商品-执行计划', 10.00, 1.00, 1, 1, 1,
   NOW() - INTERVAL 2 HOUR, NOW() + INTERVAL 1 HOUR, 'ONGOING');

INSERT INTO `orders`
  (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`, `amount`, `status`, `create_time`, `pay_deadline`)
VALUES
  ('TEST-EXPLAIN-910002', 910002, 910002, '测试商品-执行计划', 1.00, 1, 1.00, 'UNPAID',
  NOW() - INTERVAL 30 MINUTE, NOW() - INTERVAL 15 MINUTE);

EXPLAIN
SELECT `id`
FROM `orders`
WHERE `status` = 'UNPAID'
  AND `pay_deadline` < CURRENT_TIMESTAMP;

SELECT CASE
         WHEN EXISTS (
           SELECT 1
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'orders'
             AND index_name = 'idx_status_deadline'
             AND seq_in_index = 1
             AND column_name = 'status'
         )
         AND EXISTS (
           SELECT 1
           FROM information_schema.statistics
           WHERE table_schema = DATABASE()
             AND table_name = 'orders'
             AND index_name = 'idx_status_deadline'
             AND seq_in_index = 2
             AND column_name = 'pay_deadline'
         )
         THEN 'PASS: idx_status_deadline 存在；请确认上一条 EXPLAIN 的 type=range、key=idx_status_deadline'
         ELSE 'FAIL: idx_status_deadline 不存在或列顺序错误'
       END AS `result`;

ROLLBACK;

-- ===== Test 3 =====
-- 防超卖：available_stock=1 时扣减影响 1 行，=0 时扣减影响 0 行
START TRANSACTION;

DELETE FROM `stock_compensation` WHERE `activity_id` IN (910003, 910004);
DELETE FROM `seckill_activity` WHERE `id` IN (910003, 910004);

INSERT INTO `seckill_activity`
  (`id`, `goods_name`, `original_price`, `seckill_price`, `total_stock`, `available_stock`, `limit_per_user`, `start_time`, `end_time`, `status`)
VALUES
  (910003, '测试商品-库存为1', 10.00, 1.00, 1, 1, 1,
   NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR, 'ONGOING'),
  (910004, '测试商品-库存为0', 10.00, 1.00, 1, 0, 1,
   NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR, 'ONGOING');

UPDATE `seckill_activity`
SET `available_stock` = `available_stock` - 1
WHERE `id` = 910003
  AND `available_stock` >= 1;

SELECT CASE
         WHEN ROW_COUNT() = 1 THEN 'PASS: available_stock=1 扣减影响 1 行'
         ELSE 'FAIL: available_stock=1 扣减未影响 1 行'
       END AS `result`;

UPDATE `seckill_activity`
SET `available_stock` = `available_stock` - 1
WHERE `id` = 910004
  AND `available_stock` >= 1;

SELECT CASE
         WHEN ROW_COUNT() = 0 THEN 'PASS: available_stock=0 扣减影响 0 行，未发生超卖'
         ELSE 'FAIL: available_stock=0 仍然扣减，存在超卖风险'
       END AS `result`;

ROLLBACK;

-- ===== Test 4 =====
-- 外键：插入不存在的 user_id 和 activity_id，预期报外键约束错误
START TRANSACTION;

DELETE FROM `orders` WHERE `order_no` IN ('TEST-FK-USER-910005', 'TEST-FK-ACTIVITY-910005');
DELETE FROM `stock_compensation` WHERE `activity_id` = 910005;
DELETE FROM `seckill_activity` WHERE `id` = 910005;
DELETE FROM `users` WHERE `id` = 910005;

INSERT INTO `users` (`id`, `username`, `password`)
VALUES (910005, 'test_fk_user', 'test-password');

INSERT INTO `seckill_activity`
  (`id`, `goods_name`, `original_price`, `seckill_price`, `total_stock`, `available_stock`, `limit_per_user`, `start_time`, `end_time`, `status`)
VALUES
  (910005, '测试商品-外键', 10.00, 1.00, 1, 1, 1,
   NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR, 'ONGOING');

-- 预期错误：Cannot add or update a child row，user_id=999999 不存在
INSERT INTO `orders`
  (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`, `amount`, `pay_deadline`)
VALUES
  ('TEST-FK-USER-910005', 999999, 910005, '测试商品-外键', 1.00, 1, 1.00, NOW() + INTERVAL 15 MINUTE);

-- 预期错误：Cannot add or update a child row，activity_id=999999 不存在
INSERT INTO `orders`
  (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`, `amount`, `pay_deadline`)
VALUES
  ('TEST-FK-ACTIVITY-910005', 910005, 999999, '测试商品-外键', 1.00, 1, 1.00, NOW() + INTERVAL 15 MINUTE);

SELECT CASE
         WHEN NOT EXISTS (
           SELECT 1 FROM `orders`
           WHERE `order_no` IN ('TEST-FK-USER-910005', 'TEST-FK-ACTIVITY-910005')
         ) THEN 'PASS: 不存在的 user_id/activity_id 均被外键约束拒绝'
         ELSE 'FAIL: 存在非法外键订单'
       END AS `result`;

ROLLBACK;

-- ===== Test 5 =====
-- quantity=1：quantity=2 应被 CHECK 约束拒绝；CREATING/FAILED/COMPENSATING 不写入 orders。
START TRANSACTION;

DELETE FROM `orders` WHERE `activity_id` = 910006 OR `user_id` = 910006;
DELETE FROM `stock_compensation` WHERE `activity_id` = 910006;
DELETE FROM `seckill_activity` WHERE `id` = 910006;
DELETE FROM `users` WHERE `id` = 910006;

INSERT INTO `users` (`id`, `username`, `password`)
VALUES (910006, 'test_quantity_user', 'test-password');

INSERT INTO `seckill_activity`
  (`id`, `goods_name`, `original_price`, `seckill_price`, `total_stock`, `available_stock`, `limit_per_user`, `start_time`, `end_time`, `status`)
VALUES
  (910006, '测试商品-数量约束', 10.00, 1.00, 2, 2, 1,
   NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR, 'ONGOING');

-- 预期错误：Check constraint 'ck_order_quantity' is violated
INSERT INTO `orders`
  (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`, `amount`, `pay_deadline`)
VALUES
  ('TEST-QTY-910006', 910006, 910006, '测试商品-数量约束', 1.00, 2, 2.00, NOW() + INTERVAL 15 MINUTE);

SELECT CASE
         WHEN NOT EXISTS (
           SELECT 1 FROM `orders` WHERE `order_no` = 'TEST-QTY-910006'
         ) THEN 'PASS: quantity=2 已被 ck_order_quantity 拒绝'
         ELSE 'FAIL: quantity=2 进入 orders'
       END AS `result`;

ROLLBACK;

-- 执行顺序：1 -> 2 -> 3 -> 4 -> 5。
-- 推荐命令：mysql --force -u<user> -p < docs/test_v2.sql
-- 拿不准的点：
-- 1. 不同 MySQL 8.x 优化器或数据量下，Test 2 的 EXPLAIN 可能选择不同执行计划；需以实际输出为准。
-- 2. Test 1、Test 4 的预期错误依赖 mysql 客户端 --force 继续执行，其他客户端可能在错误处中止。
-- 3. Test 3 使用 ROW_COUNT() 判断受影响行数，需在对应 UPDATE 后立即执行，不能插入其它改变行数的语句。
