package com.example.flashsale.service;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.entity.SeckillActivity.ActivityStatus;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.mapper.SeckillActivityMapper;
import com.example.flashsale.vo.SeckillTokenResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import io.lettuce.core.RedisConnectionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class SeckillTokenService {

    private static final String TOKEN_KEY_PREFIX = "seckill:token:";
    private static final long TOKEN_TTL_SECONDS = 60L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillActivityMapper activityMapper;

    public SeckillTokenService(
            StringRedisTemplate stringRedisTemplate, SeckillActivityMapper activityMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.activityMapper = activityMapper;
    }

    public SeckillTokenResponse issue(Long activityId, Long userId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        validateActivity(activity, LocalDateTime.now());

        String seckillToken = randomUrlSafeValue(32);
        String path = randomUrlSafeValue(24);
        long expiresAt = System.currentTimeMillis() / 1000 + TOKEN_TTL_SECONDS;
        String key = TOKEN_KEY_PREFIX + hashPath(path);
        try {
            stringRedisTemplate.opsForHash().putAll(key, Map.of(
                    "token", seckillToken,
                    "userId", String.valueOf(userId),
                    "activityId", String.valueOf(activityId),
                    "expiresAt", String.valueOf(expiresAt),
                    "status", "READY"));
            stringRedisTemplate.expire(key, TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (RedisConnectionException | RedisSystemException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 不可用，秒杀凭证暂时不可用");
        } catch (DataAccessException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "秒杀凭证暂时不可用");
        }

        return new SeckillTokenResponse(
                seckillToken,
                path,
                Instant.ofEpochSecond(expiresAt).atOffset(ZoneOffset.ofHours(8)));
    }

    public static String hashPath(String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String randomUrlSafeValue(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private void validateActivity(SeckillActivity activity, LocalDateTime now) {
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        if (activity.getStatus() == ActivityStatus.PAUSED) {
            throw new BizException(ErrorCode.ACTIVITY_PAUSED);
        }
        if (activity.getStatus() == ActivityStatus.ENDED
                || activity.getEndTime() == null
                || !now.isBefore(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        if (activity.getStatus() == ActivityStatus.NOT_STARTED
                || activity.getStartTime() == null
                || now.isBefore(activity.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (activity.getStatus() != ActivityStatus.ONGOING) {
            throw new BizException(ErrorCode.ACTIVITY_UNAVAILABLE);
        }
    }
}
