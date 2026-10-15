CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 1,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `seckill_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `goods_name` varchar(128) NOT NULL,
  `goods_img` varchar(255) DEFAULT NULL,
  `original_price` decimal(10,2) NOT NULL DEFAULT 0.00,
  `seckill_price` decimal(10,2) NOT NULL DEFAULT 0.00,
  `total_stock` int NOT NULL DEFAULT 0,
  `available_stock` int NOT NULL DEFAULT 0,
  `limit_per_user` int NOT NULL DEFAULT 1,
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `status` enum('NOT_STARTED','ONGOING','ENDED','PAUSED') NOT NULL DEFAULT 'NOT_STARTED',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_start` (`status`, `start_time`),
  CONSTRAINT `ck_activity_limit_per_user` CHECK (`limit_per_user` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL,
  `user_id` bigint NOT NULL,
  `activity_id` bigint NOT NULL,
  `goods_name` varchar(128) NOT NULL,
  `seckill_price` decimal(10,2) NOT NULL DEFAULT 0.00,
  `quantity` int NOT NULL DEFAULT 1,
  `amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `status` enum('UNPAID','PAID','CLOSED_TIMEOUT') NOT NULL DEFAULT 'UNPAID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `pay_deadline` datetime NOT NULL,
  `pay_time` datetime DEFAULT NULL,
  `close_time` datetime DEFAULT NULL,
  `close_reason` tinyint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_activity_user` (`activity_id`, `user_id`),
  KEY `idx_user_ctime` (`user_id`, `create_time`),
  KEY `idx_status_deadline` (`status`, `pay_deadline`),
  CONSTRAINT `ck_order_quantity` CHECK (`quantity` = 1),
  CONSTRAINT `ck_order_close_reason` CHECK (`close_reason` IS NULL OR `close_reason` = 1),
  CONSTRAINT `fk_orders_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_orders_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reconciliation_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `redis_stock` int NOT NULL,
  `mysql_available_stock` int NOT NULL,
  `sold_quantity` int NOT NULL,
  `redis_diff` int NOT NULL DEFAULT 0,
  `mysql_diff` int NOT NULL DEFAULT 0,
  `result` tinyint NOT NULL,
  `detail` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_activity_ctime` (`activity_id`, `created_at`),
  CONSTRAINT `fk_reconciliation_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `stock_compensation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(32) NOT NULL,
  `activity_id` bigint NOT NULL,
  `compensation_type` enum('CREATE_ROLLBACK','TIMEOUT_REFUND') NOT NULL,
  `status` enum('PENDING','COMPENSATING','SUCCESS','FAILED') NOT NULL DEFAULT 'PENDING',
  `retry_count` int NOT NULL DEFAULT 0,
  `last_error` varchar(255) DEFAULT NULL,
  `next_retry_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_compensation_order_type` (`order_no`, `compensation_type`),
  KEY `idx_compensation_status_retry` (`status`, `next_retry_at`),
  CONSTRAINT `fk_compensation_activity` FOREIGN KEY (`activity_id`) REFERENCES `seckill_activity` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
