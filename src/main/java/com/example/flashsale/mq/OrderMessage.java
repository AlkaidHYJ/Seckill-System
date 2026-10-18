package com.example.flashsale.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessage {

    private String orderNo;
    private Long userId;
    private Long activityId;
    private String goodsName;
    private BigDecimal amount;
}
