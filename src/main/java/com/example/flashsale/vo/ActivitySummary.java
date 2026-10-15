package com.example.flashsale.vo;

import com.example.flashsale.entity.SeckillActivity.ActivityStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
public class ActivitySummary {

    private Long activityId;
    private String goodsName;
    private String goodsImg;
    private BigDecimal seckillPrice;
    private BigDecimal originalPrice;
    private Integer displayStock;
    private ActivityStatus status;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private long countdownSeconds;

    public ActivitySummary(Long activityId, String goodsName, String goodsImg,
                           BigDecimal seckillPrice, BigDecimal originalPrice,
                           Integer displayStock, ActivityStatus status,
                           OffsetDateTime startTime, OffsetDateTime endTime,
                           long countdownSeconds) {
        this.activityId = activityId;
        this.goodsName = goodsName;
        this.goodsImg = goodsImg;
        this.seckillPrice = seckillPrice;
        this.originalPrice = originalPrice;
        this.displayStock = displayStock;
        this.status = status;
        this.startTime = startTime;
        this.endTime = endTime;
        this.countdownSeconds = countdownSeconds;
    }
}