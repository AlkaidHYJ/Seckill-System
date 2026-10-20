package com.example.flashsale.job;

import com.example.flashsale.entity.Order;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.service.TimeoutCloseService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * JOB-01 超时取消：每 30 秒扫描支付到期的 UNPAID 订单（LIMIT 100，走 idx_status_deadline），
 * 逐单 CAS 关闭为 CLOSED_TIMEOUT，关闭成功则同事务创建 TIMEOUT_REFUND 补偿任务。
 * 关闭与支付互斥依赖双向 CAS，重复触发不会二次迁移状态。
 */
@Component
public class TimeoutCloseJob {

    private static final Logger log = LoggerFactory.getLogger(TimeoutCloseJob.class);
    private static final int BATCH_SIZE = 100;
    private static final String TRACE_ID_KEY = "traceId";
    private static final DateTimeFormatter BATCH_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderMapper orderMapper;
    private final TimeoutCloseService timeoutCloseService;
    private final Counter closedCounter;
    private final Counter failedCounter;

    public TimeoutCloseJob(
            OrderMapper orderMapper,
            TimeoutCloseService timeoutCloseService,
            MeterRegistry meterRegistry) {
        this.orderMapper = orderMapper;
        this.timeoutCloseService = timeoutCloseService;
        this.closedCounter = Counter.builder("seckill.timeout.closed")
                .description("Orders closed by pay_deadline scan")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("seckill.timeout.failed")
                .description("Timeout close attempts finished with exception")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 30_000L)
    public void closeExpiredOrders() {
        List<Order> expired = orderMapper.findExpiredUnpaid(BATCH_SIZE);
        if (expired.isEmpty()) {
            return;
        }

        MDC.put(TRACE_ID_KEY, "job01-" + LocalDateTime.now().format(BATCH_TIME));
        try {
            int closed = 0;
            for (Order order : expired) {
                if (closeOne(order)) {
                    closed++;
                }
            }
            log.info("JOB-01 batch finished, scanned={}, closed={}", expired.size(), closed);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private boolean closeOne(Order order) {
        try {
            boolean closed = timeoutCloseService
                    .closeAndCreateCompensation(order, LocalDateTime.now());
            if (closed) {
                closedCounter.increment();
                log.info("Timeout order closed and refund task created, orderNo={}, activityId={}",
                        order.getOrderNo(), order.getActivityId());
            } else {
                log.info("Timeout close CAS skipped (paid or already closed), orderNo={}",
                        order.getOrderNo());
            }
            return closed;
        } catch (Exception exception) {
            failedCounter.increment();
            log.error("Timeout close failed, orderNo={}", order.getOrderNo(), exception);
            return false;
        }
    }
}
