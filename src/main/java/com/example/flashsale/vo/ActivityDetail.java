package com.example.flashsale.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ActivityDetail extends ActivitySummary {

    private Integer limitPerUser;
    private String rule;
}