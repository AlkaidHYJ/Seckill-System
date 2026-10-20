package com.example.flashsale.job;

import com.example.flashsale.entity.Order;
import com.example.flashsale.entity.StockCompensation;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.mapper.StockCompensationMapper;
import com.example.flashsale.service.StockRefundService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * JOB-02 库存补偿：每 15 秒扫描到期的 PENDING 任务（LIMIT 50），
 * CAS 领取后按「Redis 回补 Lua → MySQL 归还 + 置 SUCCESS 同事务」执行。
 * 失败指数退避重试（10s/30s/2min），retry_count 达 3 次置 FAILED 并告警，
 * 不跳过重试、不自动修复线上数据。
 */
@Component
public class StockCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(StockCompensationJob.class);
    private static final String STOCK_PREFIX = "seckill:stock:";
    private static final String USER_PREFIX = "seckill:user:";
    private static final String REFUND_PREFIX = "seckill:refund:";
    private static final String TRACE_ID_KEY = "traceId";
    private static final DateTimeFormatter BATCH_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRY = 3;
    private static final long REFUND_MARKER_TTL_SECONDS = 86_400L;
    private static final Duration[] BACKOFF = {
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2)
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> refundStockScript;
    private final OrderMapper orderMapper;
    private final StockCompensationMapper compensationMapper;
    private final StockRefundService stockRefundService;
    private final Counter refundedCounter;
    private final Counter skippedCounter;
    private final Counter retryCounter;
    private final Counter alertCounter;

    public StockCompensationJob(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("refundStockScript") RedisScript<Long> refundStockScript,
            OrderMapper orderMapper,
            StockCompensationMapper compensationMapper,
            StockRefundService stockRefundService,
            MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.refundStockScript = refundStockScript;
        this.orderMapper = orderMapper;
        this.compensationMapper = compensationMapper;
        this.stockRefundService = stockRefundService;
        this.refundedCounter = Counter.builder("seckill.compensation.refunded")
                .description("Compensation tasks completed")
                .register(meterRegistry);
        this.skippedCounter = Counter.builder("seckill.compensation.skipped")
                .description("Compensation tasks skipped by claim CAS")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("seckill.compensation.retried")
                .description("Compensation attempts failed and scheduled for retry")
                .register(meterRegistry);
        this.alertCounter = Counter.builder("seckill.compensation.alerts")
                .description("Compensation tasks marked FAILED after max retries")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 15_000L)
    public void compensate() {
        int stale = compensationMapper.reclaimStale();
        if (stale > 0) {
            log.warn("JOB-02 reclaimed stale COMPENSATING tasks, count={}", stale);
        }

        List<StockCompensation> due = compensationMapper.findDue(BATCH_SIZE);
        if (due.isEmpty()) {
            return;
        }

        MDC.put(TRACE_ID_KEY, "job02-" + LocalDateTime.now().format(BATCH_TIME));
        try {
            int completed = 0;
            for (StockCompensation record : due) {
                if (compensateOne(record)) {
                    completed++;
                }
            }
            log.info("JOB-02 batch finished, scanned={}, completed={}", due.size(), completed);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private boolean compensateOne(StockCompensation record) {
        if (compensationMapper.claim(record.getId()) == 0) {
            skippedCounter.increment();
            return false;
        }
        try {
            Order order = orderMapper.findByOrderNo(record.getOrderNo());
            if (order == null) {
                throw new IllegalStateException("Order not found for compensation");
            }
            refundRedis(record, order);
            stockRefundService.restoreAndComplete(record);
            refundedCounter.increment();
            log.info("TIMEOUT_REFUND compensated, orderNo={}, activityId={}, retryCount={}",
                    record.getOrderNo(), record.getActivityId(), record.getRetryCount());
            return true;
        } catch (Exception exception) {
            handleFailure(record, exception);
            return false;
        }
    }

    /**
     * Redis 回补幂等：refund 标记存在则 Lua 直接返回 1（已回补过），
     * DB 分支失败重试时跳过 INCR/DEL，只重做 MySQL 归还。
     */
    private void refundRedis(StockCompensation record, Order order) {
        Long result = stringRedisTemplate.execute(
                refundStockScript,
                Arrays.asList(
                        STOCK_PREFIX + record.getActivityId(),
                        USER_PREFIX + record.getActivityId() + ":" + order.getUserId(),
                        REFUND_PREFIX + record.getOrderNo()),
                String.valueOf(REFUND_MARKER_TTL_SECONDS),
                record.getOrderNo());
        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException("Refund Lua returned unexpected result: " + result);
        }
        if (result == 1L) {
            log.info("Redis refund already applied, replaying DB step only, orderNo={}",
                    record.getOrderNo());
        }
    }

    private void handleFailure(StockCompensation record, Exception exception) {
        int nextRetry = (record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1;
        String lastError = truncateError(exception);
        try {
            if (nextRetry >= MAX_RETRY) {
                compensationMapper.markFailed(record.getId(), lastError);
                alertCounter.increment();
                log.error("Compensation exceeded {} attempts, marked FAILED, orderNo={}, activityId={}",
                        MAX_RETRY, record.getOrderNo(), record.getActivityId(), exception);
            } else {
                Duration backoff = BACKOFF[Math.min(nextRetry - 1, BACKOFF.length - 1)];
                compensationMapper.scheduleRetry(
                        record.getId(), LocalDateTime.now().plus(backoff), lastError);
                retryCounter.increment();
                log.warn("Compensation failed, retry scheduled, orderNo={}, nextRetry={}, backoff={}",
                        record.getOrderNo(), nextRetry, backoff, exception);
            }
        } catch (Exception updateException) {
            log.error("Compensation failure bookkeeping failed, orderNo={}",
                    record.getOrderNo(), updateException);
        }
    }

    private String truncateError(Exception exception) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return message.length() <= 255 ? message : message.substring(0, 255);
    }
}
