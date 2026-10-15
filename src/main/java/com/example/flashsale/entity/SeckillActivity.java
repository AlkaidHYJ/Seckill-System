package com.example.flashsale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_activity")
public class SeckillActivity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("goods_name")
    private String goodsName;

    @TableField("goods_img")
    private String goodsImg;

    @TableField("original_price")
    private BigDecimal originalPrice;

    @TableField("seckill_price")
    private BigDecimal seckillPrice;

    @TableField("total_stock")
    private Integer totalStock;

    @TableField("available_stock")
    private Integer availableStock;

    @TableField("limit_per_user")
    private Integer limitPerUser;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("status")
    private ActivityStatus status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public enum ActivityStatus {
        NOT_STARTED,
        ONGOING,
        ENDED,
        PAUSED
    }
}