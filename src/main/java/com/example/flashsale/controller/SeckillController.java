package com.example.flashsale.controller;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.common.Result;
import com.example.flashsale.common.UserContext;
import com.example.flashsale.dto.SeckillRequest;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.service.SeckillService;
import com.example.flashsale.vo.SeckillResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/seckill/activities")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @PostMapping("/{activityId}/buy")
    public Result<SeckillResponse> buy(
            @PathVariable @Positive Long activityId,
            @Valid @RequestBody SeckillRequest request) {
        if (!activityId.equals(request.getActivityId())) {
            throw new BizException(ErrorCode.INVALID_PARAMETER);
        }
        return Result.success(seckillService.createOrder(activityId, UserContext.getRequiredUserId()), traceId());
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}