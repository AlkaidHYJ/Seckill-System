package com.example.flashsale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账记录（append-only 日志）：一次对账写入一行三方快照与双 diff，只告警不修数，便于追溯。
 * result 口径沿用 DDL tinyint：0 一致、1 疑似超卖、2 疑似少卖（见 {@code enums.ReconciliationResult}）。
 */
@Data
@TableName("reconciliation_record")
public class ReconciliationRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("activity_id")
    private Long activityId;

    @TableField("redis_stock")
    private Integer redisStock;

    @TableField("mysql_available_stock")
    private Integer mysqlAvailableStock;

    @TableField("sold_quantity")
    private Integer soldQuantity;

    @TableField("redis_diff")
    private Integer redisDiff;

    @TableField("mysql_diff")
    private Integer mysqlDiff;

    @TableField("result")
    private Integer result;

    @TableField("detail")
    private String detail;

    @TableField("created_at")
    private LocalDateTime createdAt;
}