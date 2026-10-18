package com.example.flashsale.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

@Component
public class CreatingCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(CreatingCompensationJob.class);
    private static final String CREATING_ZSET = "seckill:creating";
    private static final String ORDER_STATE_PREFIX = "order:state:";
    private static final String ORDER_OWNER_PREFIX = "order:owner:";
    private static final String ORDER_ACTIVITY_PREFIX = "order:activity:";
    private static final String STOCK_PREFIX = "seckill:stock:";
    private static final String USER_PREFIX = "seckill:user:";
    private static final String ATTEMPT_PREFIX = "order:compensation:attempts:";
    private static final long FAILED_STATE_TTL_SECONDS = 300L;
    private static final long ATTEMPT_TTL_SECONDS = 86_400L;
    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> rollbackScript;
    private final Counter processedCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;
    private final Counter alertCounter;

    public CreatingCompensationJob(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("rollbackScript") RedisScript<Long> rollbackScript,
            MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rollbackScript = rollbackScript;
        this.processedCounter = Counter.builder("seckill.creating.compensation.processed")
                .description("CREATING orders successfully rolled back")
                .register(meterRegistry);
        this.skippedCounter = Counter.builder("seckill.creating.compensation.skipped")
                .description("CREATING compensation entries skipped after state changed")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("seckill.creating.compensation.failed")
                .description("CREATING compensation attempts that failed")
                .register(meterRegistry);
        this.alertCounter = Counter.builder("seckill.creating.compensation.alerts")
                .description("CREATING compensation orders exceeding retry threshold")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 30_000L)
    public void compensate() {
        long now = System.currentTimeMillis();
        Set<String> orderNos = stringRedisTemplate.opsForZSet()
            .rangeByScore(CREATING_ZSET, 0, now, 0, BATCH_SIZE);
        if (orderNos == null || orderNos.isEmpty()) {
            return;
        }

        for (String orderNo : orderNos) {
            compensateOne(orderNo);
        }
    }

    private void compensateOne(String orderNo) {
        String stateKey = ORDER_STATE_PREFIX + orderNo;
        String state = stringRedisTemplate.opsForValue().get(stateKey);
        if (!"CREATING".equals(state)) {
            stringRedisTemplate.opsForZSet().remove(CREATING_ZSET, orderNo);
            stringRedisTemplate.delete(ATTEMPT_PREFIX + orderNo);
            skippedCounter.increment();
            log.info("CREATING compensation skipped because state changed, orderNo={}, state={}",
                    orderNo, state);
            return;
        }

        String activityId = stringRedisTemplate.opsForValue()
                .get(ORDER_ACTIVITY_PREFIX + orderNo);
        String userId = stringRedisTemplate.opsForValue()
                .get(ORDER_OWNER_PREFIX + orderNo);
        if (activityId == null || userId == null) {
            recordFailure(orderNo, new IllegalStateException("Missing order activity or owner key"));
            return;
        }

        try {
            Long result = stringRedisTemplate.execute(
                    rollbackScript,
                    Arrays.asList(
                            STOCK_PREFIX + activityId,
                            USER_PREFIX + activityId + ":" + userId,
                            stateKey,
                            CREATING_ZSET),
                    String.valueOf(FAILED_STATE_TTL_SECONDS),
                    orderNo);
            if (Long.valueOf(0L).equals(result)) {
                stringRedisTemplate.delete(ATTEMPT_PREFIX + orderNo);
                processedCounter.increment();
                log.info("CREATING order compensated, orderNo={}, activityId={}, userId={}",
                        orderNo, activityId, userId);
            } else {
                stringRedisTemplate.opsForZSet().remove(CREATING_ZSET, orderNo);
                stringRedisTemplate.delete(ATTEMPT_PREFIX + orderNo);
                skippedCounter.increment();
                log.info("CREATING order compensation became unnecessary, orderNo={}", orderNo);
            }
        } catch (Exception exception) {
            recordFailure(orderNo, exception);
        }
    }

    private void recordFailure(String orderNo, Exception exception) {
        Long attempts = stringRedisTemplate.opsForValue()
                .increment(ATTEMPT_PREFIX + orderNo);
        stringRedisTemplate.expire(
                ATTEMPT_PREFIX + orderNo,
                Duration.ofSeconds(ATTEMPT_TTL_SECONDS));
        failedCounter.increment();
        if (attempts != null && attempts > 3) {
            alertCounter.increment();
            log.error("CREATING compensation exceeded 3 attempts, orderNo={}", orderNo, exception);
        } else {
            log.warn("CREATING compensation failed, orderNo={}, attempt={}",
                    orderNo, attempts, exception);
        }
    }
}
