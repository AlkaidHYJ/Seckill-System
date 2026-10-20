package com.example.flashsale.job;

import com.example.flashsale.entity.ReconciliationRecord;
import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.enums.ReconciliationResult;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.mapper.ReconciliationRecordMapper;
import com.example.flashsale.mapper.SeckillActivityMapper;
import com.example.flashsale.mapper.StockCompensationMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * JOB-03 三方对账（只告警、不修数）：每 5 分钟遍历 ONGOING 活动，读取
 * Redis 库存（seckill:stock:{activityId}）、MySQL available_stock 与订单有效售出件数（UNPAID+PAID），
 * 计算双 diff 并写 reconciliation_record（append-only，便于追溯）。
 * diff 非 0 且该活动无在途补偿时输出 ERROR 日志并计数；存在在途补偿（PENDING/COMPENSATING）时本轮豁免不告警。
 * 任何分支都不修改线上库存或订单。
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final String STOCK_PREFIX = "seckill:stock:";
    private static final String TRACE_ID_KEY = "traceId";
    private static final String ONGOING = "ONGOING";
    private static final DateTimeFormatter BATCH_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillActivityMapper activityMapper;
    private final OrderMapper orderMapper;
    private final StockCompensationMapper compensationMapper;
    private final ReconciliationRecordMapper reconciliationMapper;
    private final Counter inconsistentCounter;
    private final Counter exemptedCounter;
    private final Counter redisMissingCounter;
    private final Counter failedCounter;
    private final Counter redisDiffCounter;
    private final Counter mysqlDiffCounter;

    public ReconciliationJob(
            StringRedisTemplate stringRedisTemplate,
            SeckillActivityMapper activityMapper,
            OrderMapper orderMapper,
            StockCompensationMapper compensationMapper,
            ReconciliationRecordMapper reconciliationMapper,
            MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
        this.compensationMapper = compensationMapper;
        this.reconciliationMapper = reconciliationMapper;
        this.inconsistentCounter = Counter.builder("seckill.reconciliation.inconsistent")
                .description("Reconciliation rounds with non-zero diff (alerted)")
                .register(meterRegistry);
        this.exemptedCounter = Counter.builder("seckill.reconciliation.exempted")
                .description("Reconciliation rounds with non-zero diff but in-flight compensation")
                .register(meterRegistry);
        this.redisMissingCounter = Counter.builder("seckill.reconciliation.redis_missing")
                .description("Reconciliation rounds where Redis stock key is missing")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("seckill.reconciliation.failed")
                .description("Reconciliation rounds finished with exception")
                .register(meterRegistry);
        this.redisDiffCounter = Counter.builder("seckill.reconciliation.diff.total")
                .description("Reconciliation rounds with non-zero diff, tagged by side")
                .tag("type", "redis")
                .register(meterRegistry);
        this.mysqlDiffCounter = Counter.builder("seckill.reconciliation.diff.total")
                .description("Reconciliation rounds with non-zero diff, tagged by side")
                .tag("type", "mysql")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 300_000L)
    public void reconcile() {
        List<SeckillActivity> activities = activityMapper.findByStatus(ONGOING);
        if (activities.isEmpty()) {
            return;
        }

        MDC.put(TRACE_ID_KEY, "job03-" + LocalDateTime.now().format(BATCH_TIME));
        try {
            int consistent = 0;
            int inconsistent = 0;
            for (SeckillActivity activity : activities) {
                ReconciliationResult result = reconcileOne(activity);
                if (result == ReconciliationResult.CONSISTENT) {
                    consistent++;
                } else if (result != null) {
                    inconsistent++;
                }
            }
            log.info("JOB-03 batch finished, activities={}, consistent={}, inconsistent={}",
                    activities.size(), consistent, inconsistent);
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private ReconciliationResult reconcileOne(SeckillActivity activity) {
        Long activityId = activity.getId();
        try {
            int totalStock = nvl(activity.getTotalStock());
            int mysqlAvailable = nvl(activity.getAvailableStock());
            int soldQuantity = nvl(orderMapper.sumValidQuantity(activityId));

            String rawStock = stringRedisTemplate.opsForValue().get(STOCK_PREFIX + activityId);
            boolean redisMissing = false;
            int redisStock = 0;
            if (rawStock == null) {
                redisMissing = true;
            } else {
                try {
                    redisStock = Integer.parseInt(rawStock.trim());
                } catch (NumberFormatException exception) {
                    redisMissing = true;
                }
            }

            int redisDiff = totalStock - redisStock - soldQuantity;
            int mysqlDiff = totalStock - mysqlAvailable - soldQuantity;
            if (redisDiff != 0) {
                redisDiffCounter.increment();
            }
            if (mysqlDiff != 0) {
                mysqlDiffCounter.increment();
            }
            ReconciliationResult result = judge(redisDiff, mysqlDiff);

            int inFlight = compensationMapper.countInFlight(activityId);
            boolean exempted = inFlight > 0
                    && (redisDiff != 0 || mysqlDiff != 0);

            writeRecord(activityId, redisStock, mysqlAvailable, soldQuantity,
                    redisDiff, mysqlDiff, result, redisMissing, inFlight, exempted);

            if (redisMissing) {
                redisMissingCounter.increment();
                log.error("JOB-03 redis stock key missing, activityId={}, total={}, sold={}",
                        activityId, totalStock, soldQuantity);
            }

            if (result != ReconciliationResult.CONSISTENT) {
                if (exempted) {
                    exemptedCounter.increment();
                    log.warn("JOB-03 diff under in-flight compensation, exempted this round, "
                                    + "activityId={}, redisDiff={}, mysqlDiff={}, inFlight={}",
                            activityId, redisDiff, mysqlDiff, inFlight);
                } else {
                    inconsistentCounter.increment();
                    log.error("JOB-03 inconsistency detected, activityId={}, total={}, redisStock={}, "
                                    + "mysqlAvailable={}, sold={}, redisDiff={}, mysqlDiff={}, result={}",
                            activityId, totalStock, redisStock, mysqlAvailable, soldQuantity,
                            redisDiff, mysqlDiff, result.getLabel());
                }
            }
            return result;
        } catch (Exception exception) {
            failedCounter.increment();
            log.error("JOB-03 reconcile failed, activityId={}", activityId, exception);
            return null;
        }
    }

    private void writeRecord(Long activityId, int redisStock, int mysqlAvailable, int soldQuantity,
                             int redisDiff, int mysqlDiff, ReconciliationResult result,
                             boolean redisMissing, int inFlight, boolean exempted) {
        ReconciliationRecord record = new ReconciliationRecord();
        record.setActivityId(activityId);
        record.setRedisStock(redisStock);
        record.setMysqlAvailableStock(mysqlAvailable);
        record.setSoldQuantity(soldQuantity);
        record.setRedisDiff(redisDiff);
        record.setMysqlDiff(mysqlDiff);
        record.setResult(result.getCode());
        record.setDetail(buildDetail(redisDiff, mysqlDiff, redisMissing, inFlight, exempted));
        record.setCreatedAt(LocalDateTime.now());
        reconciliationMapper.insert(record);
    }

    private ReconciliationResult judge(int redisDiff, int mysqlDiff) {
        if (redisDiff < 0 || mysqlDiff < 0) {
            return ReconciliationResult.OVERSOLD;
        }
        if (redisDiff > 0 || mysqlDiff > 0) {
            return ReconciliationResult.UNDERSOLD;
        }
        return ReconciliationResult.CONSISTENT;
    }

    private String buildDetail(int redisDiff, int mysqlDiff,
                               boolean redisMissing, int inFlight, boolean exempted) {
        StringBuilder detail = new StringBuilder()
                .append("redis_diff=").append(redisDiff)
                .append(",mysql_diff=").append(mysqlDiff);
        if (redisMissing) {
            detail.append(",redis_stock_key_missing");
        }
        if (inFlight > 0) {
            detail.append(",in_flight_compensation=").append(inFlight);
            if (exempted) {
                detail.append("(exempted)");
            }
        }
        return detail.length() <= 255 ? detail.toString() : detail.substring(0, 255);
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}