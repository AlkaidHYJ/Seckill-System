package com.example.flashsale.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class SeckillRequest {

    @NotNull
    @Positive
    private Long activityId;
}