package com.example.flashsale.vo;

import com.example.flashsale.entity.Order.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
public class OrderVO {

    private String orderNo;
    private Long activityId;
    private String goodsName;
    private Integer quantity;
    private BigDecimal amount;
    private OrderStatus status;
    private OffsetDateTime payDeadline;
    private OffsetDateTime payTime;
    private OffsetDateTime closeTime;
    private Integer closeReason;
}