package com.example.flashsale.service;

import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.enums.SeckillLuaResult;
import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.mapper.SeckillActivityMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import io.lettuce.core.RedisConnectionException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class SeckillLuaService {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String USER_KEY_PREFIX = "seckill:user:";
    private static final String TOKEN_KEY_PREFIX = "seckill:token:";
    private static final String ORDER_STATE_KEY_PREFIX = "order:state:";
    private static final long ORDER_STATE_TTL_SECONDS = 300L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> fullPreDecrementScript;
    private final SeckillActivityMapper activityMapper;

    public SeckillLuaService(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("fullPreDecrementScript") RedisScript<Long> fullPreDecrementScript,
            SeckillActivityMapper activityMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.fullPreDecrementScript = fullPreDecrementScript;
        this.activityMapper = activityMapper;
    }

        public SeckillLuaResult execute(
            Long activityId, Long userId, String path, String seckillToken, String orderNo) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getEndTime() == null) {
            throw new IllegalArgumentException("Activity end time is required for stock pre-deduction");
        }

        long userTtlSeconds = Math.max(1L,
                Duration.between(LocalDateTime.now(), activity.getEndTime()).getSeconds());
        List<String> keys = List.of(
            TOKEN_KEY_PREFIX + SeckillTokenService.hashPath(path),
                STOCK_KEY_PREFIX + activityId,
                USER_KEY_PREFIX + activityId + ":" + userId,
                ORDER_STATE_KEY_PREFIX + orderNo,
                "order:owner:" + orderNo,
                "order:activity:" + orderNo);

        try {
            Long result = stringRedisTemplate.execute(
                    fullPreDecrementScript,
                    keys,
                    seckillToken,
                    String.valueOf(userTtlSeconds),
                    String.valueOf(ORDER_STATE_TTL_SECONDS),
                    String.valueOf(userId),
                    String.valueOf(activityId));
            return SeckillLuaResult.fromCode(result);
        } catch (RedisConnectionException | RedisSystemException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 不可用，秒杀请求已拒绝");
        } catch (DataAccessException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 操作失败，秒杀请求已拒绝");
        }
    }

    public void addCreatingOrder(String orderNo, long expireAt) {
        try {
            stringRedisTemplate.opsForZSet().add("seckill:creating", orderNo, expireAt);
        } catch (RedisConnectionException | RedisSystemException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 不可用，订单暂未受理");
        } catch (DataAccessException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 操作失败，订单暂未受理");
        }
    }
}
