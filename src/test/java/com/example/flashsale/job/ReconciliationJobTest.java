package com.example.flashsale.job;

import com.example.flashsale.entity.ReconciliationRecord;
import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.mapper.ReconciliationRecordMapper;
import com.example.flashsale.mapper.SeckillActivityMapper;
import com.example.flashsale.mapper.StockCompensationMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JOB-03 三方对账单元测试：mock Redis / 三个 Mapper，聚焦「双 diff 计算、结论判定、
 * 在途豁免、Redis key 缺失、异常隔离、落表与计数」等纯逻辑分支，不依赖真实中间件。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JOB-03 三方对账")
class ReconciliationJobTest {

    private static final String STOCK_PREFIX = "seckill:stock:";
    private static final String C_INCONSISTENT = "seckill.reconciliation.inconsistent";
    private static final String C_EXEMPTED = "seckill.reconciliation.exempted";
    private static final String C_REDIS_MISSING = "seckill.reconciliation.redis_missing";
    private static final String C_FAILED = "seckill.reconciliation.failed";
    private static final String C_DIFF_TOTAL = "seckill.reconciliation.diff.total";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SeckillActivityMapper activityMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private StockCompensationMapper compensationMapper;
    @Mock
    private ReconciliationRecordMapper reconciliationMapper;

    private SimpleMeterRegistry registry;
    private ReconciliationJob job;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        job = new ReconciliationJob(redis, activityMapper, orderMapper,
                compensationMapper, reconciliationMapper, registry);
    }

    private SeckillActivity activity(long id, int total, int available) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(id);
        activity.setTotalStock(total);
        activity.setAvailableStock(available);
        return activity;
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private double taggedCounter(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    private ReconciliationRecord captureSingleInsert() {
        ArgumentCaptor<ReconciliationRecord> captor =
                ArgumentCaptor.forClass(ReconciliationRecord.class);
        verify(reconciliationMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("三方一致：双 diff 为 0，result=0，不告警，写 1 条记录")
    void consistent() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of(activity(1L, 100, 60)));
        when(orderMapper.sumValidQuantity(1L)).thenReturn(40);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "1")).thenReturn("60");
        when(compensationMapper.countInFlight(1L)).thenReturn(0);

        job.reconcile();

        ReconciliationRecord record = captureSingleInsert();
        assertThat(record.getResult()).isEqualTo(0);
        assertThat(record.getRedisStock()).isEqualTo(60);
        assertThat(record.getMysqlAvailableStock()).isEqualTo(60);
        assertThat(record.getSoldQuantity()).isEqualTo(40);
        assertThat(record.getRedisDiff()).isZero();
        assertThat(record.getMysqlDiff()).isZero();
        assertThat(counter(C_INCONSISTENT)).isZero();
    }

    @Test
    @DisplayName("Redis 侧 diff<0：疑似超卖 result=1，告警计数 +1")
    void redisOversold() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of(activity(1L, 100, 60)));
        when(orderMapper.sumValidQuantity(1L)).thenReturn(40);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "1")).thenReturn("70"); // redis_diff = 100 - 70 - 40 = -10
        when(compensationMapper.countInFlight(1L)).thenReturn(0);

        job.reconcile();

        ReconciliationRecord record = captureSingleInsert();
        assertThat(record.getResult()).isEqualTo(1);
        assertThat(record.getRedisDiff()).isEqualTo(-10);
        assertThat(counter(C_INCONSISTENT)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("MySQL 侧 diff>0：疑似少卖 result=2，告警计数 +1")
    void mysqlUndersold() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of(activity(1L, 100, 50)));
        when(orderMapper.sumValidQuantity(1L)).thenReturn(40);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "1")).thenReturn("60");
        when(compensationMapper.countInFlight(1L)).thenReturn(0);

        job.reconcile();

        ReconciliationRecord record = captureSingleInsert();
        assertThat(record.getResult()).isEqualTo(2);
        assertThat(record.getMysqlDiff()).isEqualTo(10);
        assertThat(counter(C_INCONSISTENT)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("有在途补偿：diff 非 0 但豁免，不计告警，detail 标注 exempted，仍落表")
    void exemptedByInFlightCompensation() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of(activity(1L, 100, 50)));
        when(orderMapper.sumValidQuantity(1L)).thenReturn(40);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "1")).thenReturn("60");
        when(compensationMapper.countInFlight(1L)).thenReturn(2);

        job.reconcile();

        ReconciliationRecord record = captureSingleInsert();
        assertThat(record.getResult()).isEqualTo(2);
        assertThat(record.getDetail()).contains("in_flight_compensation=2(exempted)");
        assertThat(counter(C_EXEMPTED)).isEqualTo(1.0);
        assertThat(counter(C_INCONSISTENT)).isZero();
    }

    @Test
    @DisplayName("Redis key 缺失：redis_stock 落 0，detail 标注，redis_missing 计数 +1")
    void redisKeyMissing() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of(activity(1L, 100, 100)));
        when(orderMapper.sumValidQuantity(1L)).thenReturn(0);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "1")).thenReturn(null);
        when(compensationMapper.countInFlight(1L)).thenReturn(0);

        job.reconcile();

        ReconciliationRecord record = captureSingleInsert();
        assertThat(record.getRedisStock()).isZero();
        assertThat(record.getDetail()).contains("redis_stock_key_missing");
        assertThat(counter(C_REDIS_MISSING)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("单活动异常隔离：failed 计数 +1，同批其他活动仍被处理")
    void exceptionIsolatedPerActivity() {
        when(activityMapper.findByStatus("ONGOING"))
                .thenReturn(List.of(activity(1L, 100, 60), activity(2L, 100, 60)));
        when(orderMapper.sumValidQuantity(1L)).thenThrow(new RuntimeException("boom"));
        when(orderMapper.sumValidQuantity(2L)).thenReturn(40);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "2")).thenReturn("60");
        when(compensationMapper.countInFlight(2L)).thenReturn(0);

        job.reconcile();

        ReconciliationRecord record = captureSingleInsert();
        assertThat(record.getActivityId()).isEqualTo(2L);
        assertThat(counter(C_FAILED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("无 ONGOING 活动：不写记录、不计数、不读 Redis")
    void noOngoingActivity() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of());

        job.reconcile();

        verify(reconciliationMapper, never()).insert(any(ReconciliationRecord.class));
        verify(redis, never()).opsForValue();
        assertThat(counter(C_INCONSISTENT)).isZero();
        assertThat(counter(C_EXEMPTED)).isZero();
        assertThat(counter(C_FAILED)).isZero();
    }

    @Test
    @DisplayName("Redis 侧 diff 非 0 → diff.total{type=redis} +1，mysql 侧为 0")
    void redisDiffTaggedCounter() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of(activity(1L, 100, 60)));
        when(orderMapper.sumValidQuantity(1L)).thenReturn(40);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "1")).thenReturn("70"); // redis_diff=-10, mysql_diff=0
        when(compensationMapper.countInFlight(1L)).thenReturn(0);

        job.reconcile();

        assertThat(taggedCounter(C_DIFF_TOTAL, "type", "redis")).isEqualTo(1.0);
        assertThat(taggedCounter(C_DIFF_TOTAL, "type", "mysql")).isZero();
    }

    @Test
    @DisplayName("MySQL 侧 diff 非 0 → diff.total{type=mysql} +1，redis 侧为 0")
    void mysqlDiffTaggedCounter() {
        when(activityMapper.findByStatus("ONGOING")).thenReturn(List.of(activity(1L, 100, 50)));
        when(orderMapper.sumValidQuantity(1L)).thenReturn(40);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(STOCK_PREFIX + "1")).thenReturn("60"); // mysql_diff=10, redis_diff=0
        when(compensationMapper.countInFlight(1L)).thenReturn(0);

        job.reconcile();

        assertThat(taggedCounter(C_DIFF_TOTAL, "type", "mysql")).isEqualTo(1.0);
        assertThat(taggedCounter(C_DIFF_TOTAL, "type", "redis")).isZero();
    }
}