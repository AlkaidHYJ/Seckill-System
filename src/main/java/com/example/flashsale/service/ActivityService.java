package com.example.flashsale.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.entity.SeckillActivity.ActivityStatus;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.mapper.SeckillActivityMapper;
import com.example.flashsale.vo.ActivityDetail;
import com.example.flashsale.vo.ActivitySummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ActivityService {

    private static final String ACTIVITY_RULE = "每人限购 1 件，支付截止时间为下单后 15 分钟";

    private final SeckillActivityMapper activityMapper;

    public ActivityService(SeckillActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    public List<ActivitySummary> list(String status) {
        ActivityStatus activityStatus = parseStatus(status);
        LambdaQueryWrapper<SeckillActivity> query = new LambdaQueryWrapper<SeckillActivity>()
                .eq(activityStatus != null, SeckillActivity::getStatus, activityStatus)
                .orderByAsc(SeckillActivity::getStartTime);

        LocalDateTime now = LocalDateTime.now();
        return activityMapper.selectList(query).stream()
                .map(activity -> toSummary(activity, now))
                .toList();
    }

    public ActivityDetail getDetail(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }

        ActivityDetail detail = new ActivityDetail();
        copySummary(toSummary(activity, LocalDateTime.now()), detail);
        detail.setLimitPerUser(activity.getLimitPerUser());
        detail.setRule(ACTIVITY_RULE);
        return detail;
    }

    private ActivityStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ActivityStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private ActivitySummary toSummary(SeckillActivity activity, LocalDateTime now) {
        ActivitySummary summary = new ActivitySummary();
        summary.setActivityId(activity.getId());
        summary.setGoodsName(activity.getGoodsName());
        summary.setGoodsImg(activity.getGoodsImg());
        summary.setSeckillPrice(activity.getSeckillPrice());
        summary.setOriginalPrice(activity.getOriginalPrice());
        summary.setDisplayStock(activity.getAvailableStock());
        summary.setStatus(activity.getStatus());
        summary.setStartTime(toOffsetDateTime(activity.getStartTime()));
        summary.setEndTime(toOffsetDateTime(activity.getEndTime()));
        summary.setCountdownSeconds(countdownSeconds(activity, now));
        return summary;
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atOffset(ZoneOffset.ofHours(8));
    }

    private void copySummary(ActivitySummary source, ActivityDetail target) {
        target.setActivityId(source.getActivityId());
        target.setGoodsName(source.getGoodsName());
        target.setGoodsImg(source.getGoodsImg());
        target.setSeckillPrice(source.getSeckillPrice());
        target.setOriginalPrice(source.getOriginalPrice());
        target.setDisplayStock(source.getDisplayStock());
        target.setStatus(source.getStatus());
        target.setStartTime(source.getStartTime());
        target.setEndTime(source.getEndTime());
        target.setCountdownSeconds(source.getCountdownSeconds());
    }

    private long countdownSeconds(SeckillActivity activity, LocalDateTime now) {
        if (activity.getStatus() == ActivityStatus.ENDED
                || activity.getStatus() == ActivityStatus.PAUSED) {
            return 0;
        }

        LocalDateTime startTime = activity.getStartTime();
        LocalDateTime endTime = activity.getEndTime();
        if (startTime == null || endTime == null || !now.isBefore(endTime)) {
            return 0;
        }
        LocalDateTime target = now.isBefore(startTime) ? startTime : endTime;
        return Math.max(0, Duration.between(now, target).getSeconds());
    }
}