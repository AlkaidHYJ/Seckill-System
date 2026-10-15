package com.example.flashsale.controller;

import com.example.flashsale.common.Result;
import com.example.flashsale.service.ActivityService;
import com.example.flashsale.vo.ActivityDetail;
import com.example.flashsale.vo.ActivitySummary;
import jakarta.validation.constraints.Positive;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/seckill/activities")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public Result<List<ActivitySummary>> list(
            @RequestParam(required = false) String status) {
        return Result.success(activityService.list(status), traceId());
    }

    @GetMapping("/{activityId}")
    public Result<ActivityDetail> detail(@PathVariable @Positive Long activityId) {
        return Result.success(activityService.getDetail(activityId), traceId());
    }

    private String traceId() {
        return MDC.get("traceId");
    }
}