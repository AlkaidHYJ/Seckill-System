package com.example.flashsale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    @TableField("activity_id")
    private Long activityId;

    @TableField("goods_name")
    private String goodsName;

    @TableField("seckill_price")
    private BigDecimal seckillPrice;

    @TableField("quantity")
    private Integer quantity;

    @TableField("amount")
    private BigDecimal amount;

    @TableField("status")
    private OrderStatus status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("pay_deadline")
    private LocalDateTime payDeadline;

    @TableField("pay_time")
    private LocalDateTime payTime;

    @TableField("close_time")
    private LocalDateTime closeTime;

    @TableField("close_reason")
    private Integer closeReason;

    public enum OrderStatus {
        CREATING,
        FAILED,
        UNPAID,
        PAID,
        CLOSED_TIMEOUT
    }
}