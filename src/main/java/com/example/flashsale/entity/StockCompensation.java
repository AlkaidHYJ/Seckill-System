package com.example.flashsale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存补偿任务：按 (order_no, compensation_type) 唯一键幂等，
 * 生命周期 PENDING -> COMPENSATING -> SUCCESS，失败退避重试，超过上限置 FAILED 只告警。
 */
@Data
@TableName("stock_compensation")
public class StockCompensation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("activity_id")
    private Long activityId;

    @TableField("compensation_type")
    private CompensationType compensationType;

    @TableField("status")
    private CompensationStatus status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("last_error")
    private String lastError;

    @TableField("next_retry_at")
    private LocalDateTime nextRetryAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public enum CompensationType {
        CREATE_ROLLBACK,
        TIMEOUT_REFUND
    }

    public enum CompensationStatus {
        PENDING,
        COMPENSATING,
        SUCCESS,
        FAILED
    }
}
