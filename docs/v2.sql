-- Flyway migration: V1__init_schema.sql
-- 数据库名为 seckill；生产环境也可由 Flyway 连接配置指定目标数据库。

CREATE DATABASE IF NOT EXISTS `seckill`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `seckill`;

CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID, 主键',
  `username` varchar(64) NOT NULL COMMENT '登录名, 唯一, 登录鉴权用',
  `password` varchar(128) NOT NULL COMMENT 'BCrypt 加密密码, 可改密',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '账号状态: 1正常 0封禁',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE `seckill_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '活动/商品ID, 主键',
  `goods_name` varchar(128) NOT NULL COMMENT '商品名称, 下单时快照到订单',
  `goods_img` varchar(255) DEFAULT NULL COMMENT '商品主图URL',
  `original_price` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '原价',
  `seckill_price` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '秒杀价',
  `total_stock` int NOT NULL DEFAULT 0 COMMENT '总库存, 活动初始化时同步到 Redis',
  `available_stock` int NOT NULL DEFAULT 0 COMMENT 'MySQL 剩余库存, 落库时条件扣减兜底防超卖',
  `limit_per_user` int NOT NULL DEFAULT 1 COMMENT '每人限购数, 固定为 1',
  `start_time` datetime NOT NULL COMMENT '开抢时间',
  `end_time` datetime NOT NULL COMMENT '结束时间',
  `status` enum('NOT_STARTED','ONGOING','ENDED','PAUSED') NOT NULL DEFAULT 'NOT_STARTED' COMMENT '活动状态: 未开始/进行中/已结束/已暂停',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_start` (`status`, `start_time`),
  CONSTRAINT `ck_activity_limit_per_user` CHECK (`limit_per_user` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单场次秒杀活动, 一活动=一商品; 库存权威在 Redis, available_stock 为 MySQL 事务兜底库存';

CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单自增主键, 内部聚簇索引',
  `order_no` varchar(32) NOT NULL COMMENT '业务订单号, 对外唯一标识',
  `user_id` bigint NOT NULL COMMENT '下单用户, 关联 users.id',
  `activity_id` bigint NOT NULL COMMENT '秒杀活动, 关联 seckill_activity.id',
  `goods_name` varchar(128) NOT NULL COMMENT '商品名称快照, 防活动改名',
  `seckill_price` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '成交单价快照, 防活动改价',
  `quantity` int NOT NULL DEFAULT 1 COMMENT '购买数量, 固定为 1',
  `amount` decimal(10,2) NOT NULL DEFAULT 0.00 COMMENT '应付金额, 等于成交单价乘购买数量',
  `status` enum('UNPAID','PAID','CLOSED_TIMEOUT') NOT NULL DEFAULT 'UNPAID' COMMENT '订单状态: 待支付/已支付/超时关闭',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间, 超时判定基准',
  `pay_deadline` datetime NOT NULL COMMENT '支付截止时间, 由下单时间加支付时长计算',
  `pay_time` datetime DEFAULT NULL COMMENT '支付完成时间',
  `close_time` datetime DEFAULT NULL COMMENT '订单关闭时间',
  `close_reason` tinyint DEFAULT NULL COMMENT '关闭原因: 1超时',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_activity_user` (`activity_id`, `user_id`),
  KEY `idx_user_ctime` (`user_id`, `create_time`),
  KEY `idx_status_deadline` (`status`, `pay_deadline`),
  CONSTRAINT `ck_order_quantity` CHECK (`quantity` = 1),
  CONSTRAINT `ck_order_close_reason` CHECK (`close_reason` IS NULL OR `close_reason` = 1),
  CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_orders_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表; 仅存 UNPAID/PAID/CLOSED_TIMEOUT, CREATING/FAILED 存于 Redis';

CREATE TABLE `reconciliation_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '对账记录主键',
  `activity_id` bigint NOT NULL COMMENT '对账活动, 关联 seckill_activity.id',
  `redis_stock` int NOT NULL COMMENT '对账时 Redis 剩余库存快照',
  `mysql_available_stock` int NOT NULL COMMENT '对账时 MySQL 剩余库存快照',
  `sold_quantity` int NOT NULL COMMENT '有效售出件数, UNPAID 加 PAID 的 quantity 总和',
  `redis_diff` int NOT NULL DEFAULT 0 COMMENT 'Redis 差异值, total_stock - redis_stock - sold_quantity',
  `mysql_diff` int NOT NULL DEFAULT 0 COMMENT 'MySQL 差异值, total_stock - mysql_available_stock - sold_quantity',
  `result` tinyint NOT NULL COMMENT '对账结论: 0一致 1疑似超卖 2疑似少卖',
  `detail` varchar(255) DEFAULT NULL COMMENT '差异明细或告警信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '对账时间, append-only 日志',
  PRIMARY KEY (`id`),
  KEY `idx_activity_ctime` (`activity_id`, `created_at`),
  CONSTRAINT `fk_reconciliation_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存对账记录表, 只记录差异并告警, 不自动修正线上数据';

CREATE TABLE `stock_compensation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '库存补偿记录主键',
  `order_no` varchar(32) NOT NULL COMMENT '关联订单号, 业务幂等键',
  `activity_id` bigint NOT NULL COMMENT '关联活动ID',
  `compensation_type` enum('CREATE_ROLLBACK','TIMEOUT_REFUND') NOT NULL COMMENT '补偿类型: 创建回滚/超时回补',
  `status` enum('PENDING','COMPENSATING','SUCCESS','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '补偿状态: 待处理/处理中/成功/失败',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `last_error` varchar(255) DEFAULT NULL COMMENT '最近一次失败原因',
  `next_retry_at` datetime DEFAULT NULL COMMENT '下次重试时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_compensation_order_type` (`order_no`, `compensation_type`),
  KEY `idx_compensation_status_retry` (`status`, `next_retry_at`),
  CONSTRAINT `fk_compensation_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存补偿任务表, 按订单和补偿类型幂等重试';