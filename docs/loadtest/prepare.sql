-- =============================================================
-- prepare.sql  压测数据准备（活动 id=2，库存 100，测试用户 200）
-- 执行方式：
--   1) PowerShell（不支持 `<` 重定向，用管道）:
--      Get-Content docs\loadtest\prepare.sql -Raw | docker exec -i flash-sale-mysql mysql -uroot -p<密码> seckill
--   2) bash / Git-Bash:
--      docker exec -i flash-sale-mysql mysql -uroot -p<密码> seckill < docs/loadtest/prepare.sql
--   3) 客户端（Navicat/DBeaver）连本地 3307 端口后整段执行
-- 可重复执行（幂等）
-- 已在 MySQL 8.0 事务内试跑通过（最后 ROLLBACK，未留存数据）；
-- 第 3 步会带 1 个 warning：VALUES() 在 8.0.20+ 已标记弃用，不影响执行结果
-- =============================================================

USE `seckill`;

-- -------------------------------------------------------------
-- 1. 重置活动 id=2 的库存与状态
--    available_stock 归零到 100，total_stock 保持 100 作为对账基准
--    状态置为 ONGOING，时间窗口放开，保证压测期间不被"未开始/已结束"拦截
-- -------------------------------------------------------------
UPDATE `seckill_activity`
SET `available_stock` = 100,
    `total_stock`     = 100,
    `status`          = 'ONGOING',
    `start_time`      = DATE_SUB(NOW(), INTERVAL 1 DAY),
    `end_time`        = DATE_ADD(NOW(), INTERVAL 1 DAY)
WHERE `id` = 2;

-- -------------------------------------------------------------
-- 2. 清空 activity_id=2 的历史订单
--    注意: orders 对 seckill_activity / users 有外键，删订单不影响活动与用户
-- -------------------------------------------------------------
DELETE FROM `orders` WHERE `activity_id` = 2;

-- 可选：清空上一轮的补偿与对账记录，避免干扰复盘（首次压测可跳过）
-- DELETE FROM `stock_compensation` WHERE `activity_id` = 2;
-- DELETE FROM `reconciliation_record` WHERE `activity_id` = 2;

-- -------------------------------------------------------------
-- 3. 插入 200 个测试用户 loadtest1 ~ loadtest200
--    统一明文密码：12345678（恰好 8 位，满足 LoginRequest @Size(min=8, max=72)）
--    下方哈希已由 Python bcrypt(rounds=12, prefix=b'2a') 生成并用 checkpw 自检通过
--    已存在则更新密码，保证幂等
-- -------------------------------------------------------------
INSERT INTO `users` (`username`, `password`, `status`)
SELECT CONCAT('loadtest', `t`.`n`),
       '$2a$12$x1c/ntFonKoX5KEAFl8VH.61czbA2JaRqoc5troAf0MHLVub71h12',
       1
FROM (
    SELECT `a`.`d` + `b`.`d` * 10 + `c`.`d` * 100 + 1 AS `n`
    FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) `a`,
         (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) `b`,
         (SELECT 0 d UNION ALL SELECT 1) `c`
) `t`
ON DUPLICATE KEY UPDATE `password` = VALUES(`password`),
                        `status`   = 1;

-- -------------------------------------------------------------
-- 4. 准备结果自检（期望: 用户 200，活动库存 100，订单 0）
-- -------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM `users` WHERE `username` LIKE 'loadtest%')          AS loadtest_users,
       (SELECT `available_stock` FROM `seckill_activity` WHERE `id` = 2)         AS stock,
       (SELECT COUNT(*) FROM `orders` WHERE `activity_id` = 2)                   AS orders;

-- =============================================================
-- BCrypt 哈希怎么生成（本项目要求 rounds=12、前缀 $2a$，输出 60 字符）
-- 已写入上面的哈希；若要换明文，按下面重新生成并替掉 password 列
--
-- 方式 A：Python bcrypt（本项目验证可用，bcrypt 4.0.1）
--   pip install bcrypt
--   python -c "import bcrypt; print(bcrypt.hashpw(b'12345678', bcrypt.gensalt(rounds=12, prefix=b'2a')).decode())"
--
-- 方式 B：项目依赖 jbcrypt 生成（与登录校验完全一致）
--   BCrypt.hashpw("12345678", BCrypt.gensalt(12))            // 输出 $2a$12$...
--   入库前自检: BCrypt.checkpw("12345678", hash) == true
--
-- Windows PowerShell 注意：哈希里的 `$` 会被 PowerShell 当作变量前缀
--   - 打印/替换时一律用单引号包裹哈希，例如 '$2a$12$xxx'
--   - 不要把哈希先存进 shell 变量再拼接 SQL；直接把生成结果粘进本文件
--
-- 明文密码 = 12345678（generate-jwt.md 中的登录脚本使用同一值）
-- =============================================================
