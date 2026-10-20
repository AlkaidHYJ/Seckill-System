package com.example.flashsale.config;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.common.UserContext;
import com.example.flashsale.exception.BizException;
import io.lettuce.core.RedisConnectionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SeckillRateLimitInterceptor implements HandlerInterceptor {

    private static final Pattern BUY_PATH = Pattern.compile(
            "/api/seckill/activities/(\\d+)/buy/[^/]+");
    private static final String USER_KEY_PREFIX = "seckill:limit:user:";
    private static final String IP_KEY_PREFIX = "seckill:limit:ip:";
    private static final long WINDOW_MILLIS = 1_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final SeckillRateLimitProperties properties;

    public SeckillRateLimitInterceptor(
            StringRedisTemplate stringRedisTemplate,
            RedisScript<Long> rateLimitScript,
            SeckillRateLimitProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Matcher matcher = BUY_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            return true;
        }

        Long userId = UserContext.getRequiredUserId();
        String activityId = matcher.group(1);
        String ip = clientIp(request);
        List<String> keys = List.of(
                USER_KEY_PREFIX + userId + ":" + activityId,
                IP_KEY_PREFIX + ip);
        try {
            Long result = stringRedisTemplate.execute(
                    rateLimitScript,
                    keys,
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(WINDOW_MILLIS),
                    String.valueOf(properties.getUserPerSecond()),
                    String.valueOf(properties.getIpPerSecond()),
                    UUID.randomUUID().toString());
            if (Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result)) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
            }
            return true;
        } catch (RedisConnectionException | RedisSystemException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 不可用，限流校验失败");
        } catch (DataAccessException exception) {
            throw new BizException(ErrorCode.REDIS_UNAVAILABLE, "Redis 操作失败，限流校验失败");
        }
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}