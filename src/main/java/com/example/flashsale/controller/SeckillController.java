package com.example.flashsale.controller;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.common.Result;
import com.example.flashsale.common.UserContext;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.service.SeckillService;
import com.example.flashsale.service.SeckillTokenService;
import com.example.flashsale.vo.SeckillResponse;
import com.example.flashsale.vo.SeckillTokenResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.constraints.Positive;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/seckill/activities")
public class SeckillController {

    private static final String REQUEST_TOTAL_METRIC = "seckill.request.total";

    private final SeckillService seckillService;
    private final SeckillTokenService seckillTokenService;
    private final MeterRegistry meterRegistry;

    public SeckillController(SeckillService seckillService, SeckillTokenService seckillTokenService,
                             MeterRegistry meterRegistry) {
        this.seckillService = seckillService;
        this.seckillTokenService = seckillTokenService;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/{activityId}/token")
    public Result<SeckillTokenResponse> token(@PathVariable @Positive Long activityId) {
        return Result.success(
                seckillTokenService.issue(activityId, UserContext.getRequiredUserId()), traceId());
    }

    @PostMapping("/{activityId}/buy/{path}")
    public Result<SeckillResponse> buy(
            @PathVariable @Positive Long activityId,
            @PathVariable String path,
            @RequestHeader(value = "X-Seckill-Token", required = false) String seckillToken) {
        try {
            if (seckillToken == null || seckillToken.isBlank()) {
                throw new BizException(ErrorCode.MISSING_PARAMETER);
            }
            SeckillResponse response = seckillService.createOrder(
                    activityId, UserContext.getRequiredUserId(), path, seckillToken);
            recordRequestMetric(ErrorCode.SUCCESS);
            return Result.success(response, traceId());
        } catch (BizException exception) {
            recordRequestMetric(exception.getErrorCode());
            throw exception;
        } catch (RuntimeException exception) {
            recordRequestMetric(ErrorCode.SYSTEM_ERROR);
            throw exception;
        }
    }

    private void recordRequestMetric(ErrorCode errorCode) {
        meterRegistry.counter(REQUEST_TOTAL_METRIC, "code", String.valueOf(errorCode.getCode()))
                .increment();
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}