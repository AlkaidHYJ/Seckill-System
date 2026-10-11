CREATE TABLE `users` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT (now()),
  `updated_at` datetime
);

CREATE TABLE `seckill_activity` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `goods_name` varchar(128) NOT NULL,
  `goods_img` varchar(255),
  `original_price` decimal(10,2) NOT NULL DEFAULT 0,
  `seckill_price` decimal(10,2) NOT NULL DEFAULT 0,
  `total_stock` int NOT NULL DEFAULT 0,
  `available_stock` int NOT NULL DEFAULT 0,
  `limit_per_user` int NOT NULL DEFAULT 1,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` ENUM ('NOT_STARTED', 'ONGOING', 'ENDED', 'PAUSED') NOT NULL DEFAULT 'NOT_STARTED',
  `created_at` datetime NOT NULL DEFAULT (now()),
  `updated_at` datetime
);

CREATE TABLE `orders` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL,
  `user_id` bigint NOT NULL,
  `activity_id` bigint NOT NULL,
  `goods_name` varchar(128) NOT NULL,
  `seckill_price` decimal(10,2) NOT NULL DEFAULT 0,
  `quantity` int NOT NULL DEFAULT 1,
  `amount` decimal(10,2) NOT NULL DEFAULT 0,
  `status` ENUM ('UNPAID', 'PAID', 'CLOSED_TIMEOUT') NOT NULL DEFAULT 'UNPAID',
  `create_time` datetime NOT NULL DEFAULT (now()),
  `pay_deadline` datetime NOT NULL,
  `pay_time` datetime,
  `close_time` datetime,
  `close_reason` tinyint
);

CREATE TABLE `reconciliation_record` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `redis_stock` int NOT NULL,
  `mysql_available_stock` int NOT NULL,
  `mysql_sold` int NOT NULL,
  `diff` int NOT NULL DEFAULT 0,
  `result` tinyint NOT NULL,
  `detail` varchar(255),
  `created_at` datetime NOT NULL DEFAULT (now())
);

CREATE UNIQUE INDEX `uk_username` ON `users` (`username`);

CREATE INDEX `idx_status_start` ON `seckill_activity` (`status`, `start_time`);

CREATE UNIQUE INDEX `uk_order_no` ON `orders` (`order_no`);

CREATE INDEX `idx_user_ctime` ON `orders` (`user_id`, `create_time`);

CREATE INDEX `idx_status_ctime` ON `orders` (`status`, `create_time`);

CREATE INDEX `idx_act_user` ON `orders` (`activity_id`, `user_id`);

CREATE INDEX `idx_act_ctime` ON `reconciliation_record` (`activity_id`, `created_at`);

ALTER TABLE `seckill_activity` COMMENT = '单场次秒杀, 一活动=一商品(合并建模, 不拆表); 库存权威在 Redis, 本表 available_stock 落库同事务兜底扣减';

ALTER TABLE `orders` COMMENT = 'CREATING/FAILED 是落库前的 Redis 侧状态, 不入本表; 本表仅存 UNPAID/PAID/CLOSED_TIMEOUT';

ALTER TABLE `orders` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `orders` ADD FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`);

ALTER TABLE `reconciliation_record` ADD FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`);
