package com.example.flashsale.vo;

import com.example.flashsale.entity.Order.OrderStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class SeckillResponse {

    private String orderNo;
    private OrderStatus status;
    private OffsetDateTime acceptedAt;

    public SeckillResponse(String orderNo, OrderStatus status, OffsetDateTime acceptedAt) {
        this.orderNo = orderNo;
        this.status = status;
        this.acceptedAt = acceptedAt;
    }
}