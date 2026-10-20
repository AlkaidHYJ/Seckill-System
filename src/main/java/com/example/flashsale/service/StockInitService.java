package com.example.flashsale.service;

import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.mapper.SeckillActivityMapper;
import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.exception.BizException;
import io.lettuce.core.RedisConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class StockInitService {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String USER_KEY_PREFIX = "seckill:user:";

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillActivityMapper activityMapper;

    public StockInitService(
            StringRedisTemplate stringRedisTemplate,
            SeckillActivityMapper activityMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.activityMapper = activityMapper;
    }

    public void initStock(Long activityId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getAvailableStock() == null) {
            throw new IllegalArgumentException("Activity stock is required for Redis initialization");
        }
        if (activity.getStartTime() == null || !LocalDateTime.now().isBefore(activity.getStartTime())) {
            throw new IllegalStateException("Redis stock must be initialized before activity start");
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    STOCK_KEY_PREFIX + activityId,
                    String.valueOf(activity.getAvailableStock()));
        } catch (RedisConnectionException | RedisSystemException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 不可用，库存初始化失败");
        } catch (DataAccessException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 操作失败，库存初始化失败");
        }
    }

    public void cleanUserMarks(Long activityId) {
        // 仅压测前使用，生产禁用：KEYS 会遍历 Redis 全部 Key。
        try {
            Set<String> keys = stringRedisTemplate.keys(USER_KEY_PREFIX + activityId + ":*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (RedisConnectionException | RedisSystemException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 不可用，用户限购标记清理失败");
        } catch (DataAccessException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 操作失败，用户限购标记清理失败");
        }
    }
}
