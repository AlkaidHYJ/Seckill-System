-- =============================================================
-- test_v4_timeout.sql  v4 超时取消 + 库存回补 功能测试数据准备
-- 执行方式：
--   powershell -NoProfile -ExecutionPolicy Bypass -File docs\loadtest\run_sql.ps1 -Sql custom -CustomFile docs\loadtest\test_v4_timeout.sql
-- 设计：
--   活动 9001（主链路）：total=10 available=9，1 笔过期 UNPAID → 应关闭并成功回补
--   活动 9002（防超还/FAILED 残窗）：total=5 available=5（已满），2 笔过期 UNPAID
--     → 关闭成功，但 MySQL 归还被 available<total 守卫拒绝 → 重试 3 次 → FAILED
--   活动 9001 另一笔 PAID 过期单 → 扫描必须跳过
-- 幂等：可重复执行（先删后插）；需配套清理 Redis key（见测试报告命令）
-- =============================================================

USE `seckill`;

-- 1. 测试活动
INSERT INTO `seckill_activity`
    (`id`, `goods_name`, `original_price`, `seckill_price`, `total_stock`, `available_stock`,
     `limit_per_user`, `start_time`, `end_time`, `status`)
VALUES
    (9001, 'v4-test-refund', 10.00, 1.00, 10, 9, 1,
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 'ONGOING'),
    (9002, 'v4-test-fulllock', 10.00, 1.00, 5, 5, 1,
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 1 DAY), 'ONGOING')
ON DUPLICATE KEY UPDATE
    `total_stock`     = VALUES(`total_stock`),
    `available_stock` = VALUES(`available_stock`),
    `status`          = 'ONGOING',
    `start_time`      = VALUES(`start_time`),
    `end_time`        = VALUES(`end_time`);

-- 2. 测试用户
INSERT INTO `users` (`username`, `password`, `status`)
VALUES
    ('v4_user_a', '$2a$12$x1c/ntFonKoX5KEAFl8VH.61czbA2JaRqoc5troAf0MHLVub71h12', 1),
    ('v4_user_b', '$2a$12$x1c/ntFonKoX5KEAFl8VH.61czbA2JaRqoc5troAf0MHLVub71h12', 1)
ON DUPLICATE KEY UPDATE `status` = 1;

-- 3. 清理上一轮测试残留（顺序：补偿 → 订单）
DELETE FROM `stock_compensation` WHERE `order_no` LIKE 'SKV4TEST%';
DELETE FROM `orders` WHERE `order_no` LIKE 'SKV4TEST%';

-- 4. 测试订单
-- 001: 9001/user_a 过期 UNPAID → 期望关闭+回补 SUCCESS
INSERT INTO `orders`
    (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`,
     `amount`, `status`, `create_time`, `pay_deadline`)
SELECT 'SKV4TEST001', u.`id`, 9001, 'v4-test-refund', 1.00, 1, 1.00, 'UNPAID',
       DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE)
FROM `users` u WHERE u.`username` = 'v4_user_a';

-- 002: 9002/user_a 过期 UNPAID → 期望关闭+回补 FAILED（MySQL 已满，防超还拒绝）
INSERT INTO `orders`
    (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`,
     `amount`, `status`, `create_time`, `pay_deadline`)
SELECT 'SKV4TEST002', u.`id`, 9002, 'v4-test-fulllock', 1.00, 1, 1.00, 'UNPAID',
       DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE)
FROM `users` u WHERE u.`username` = 'v4_user_a';

-- 003: 9001/user_b 已支付（pay_deadline 也过期）→ 期望扫描完全不碰
INSERT INTO `orders`
    (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`,
     `amount`, `status`, `create_time`, `pay_deadline`, `pay_time`)
SELECT 'SKV4TEST003', u.`id`, 9001, 'v4-test-refund', 1.00, 1, 1.00, 'PAID',
       DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE),
       DATE_SUB(NOW(), INTERVAL 25 MINUTE)
FROM `users` u WHERE u.`username` = 'v4_user_b';

-- 004: 9002/user_b 过期 UNPAID → 与 002 同路径（FAILED），兼作关闭后支付 CAS 验证对象
INSERT INTO `orders`
    (`order_no`, `user_id`, `activity_id`, `goods_name`, `seckill_price`, `quantity`,
     `amount`, `status`, `create_time`, `pay_deadline`)
SELECT 'SKV4TEST004', u.`id`, 9002, 'v4-test-fulllock', 1.00, 1, 1.00, 'UNPAID',
       DATE_SUB(NOW(), INTERVAL 35 MINUTE), DATE_SUB(NOW(), INTERVAL 20 MINUTE)
FROM `users` u WHERE u.`username` = 'v4_user_b';

-- 5. 准备结果自检（期望: 001/002/004 UNPAID，003 PAID，补偿 0 条）
SELECT `order_no`, `status`, `pay_deadline` < NOW() AS expired
FROM `orders` WHERE `order_no` LIKE 'SKV4TEST%' ORDER BY `order_no`;
SELECT COUNT(*) AS compensation_rows FROM `stock_compensation` WHERE `order_no` LIKE 'SKV4TEST%';
SELECT `id`, `total_stock`, `available_stock` FROM `seckill_activity` WHERE `id` IN (9001, 9002);
