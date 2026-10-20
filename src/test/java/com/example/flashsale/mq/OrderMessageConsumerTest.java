package com.example.flashsale.mq;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可观测（输出 1 traceId 跨 MQ 消费端 / 输出 3 mq.consume.*）单元测试：
 * 验证消费时从 header 取回 traceId 写入 MDC、处理完清理 MDC，以及成功/重试/失败三路径的计数与 ack/nack。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("可观测 · MQ 消费端（traceId MDC + mq.consume.*）")
class OrderMessageConsumerTest {

    private static final String ORDER_NO = "SK-CONSUME-1";
    private static final long DELIVERY_TAG = 42L;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zsetOps;
    @Mock
    private OrderPersistenceService persistenceService;
    @Mock
    private Channel channel;

    private SimpleMeterRegistry registry;
    private OrderMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        consumer = new OrderMessageConsumer(redis, persistenceService, registry);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private OrderMessage message() {
        return new OrderMessage(ORDER_NO, 1001L, 7L, "iPhone", new BigDecimal("9.99"));
    }

    private Message raw(String traceIdHeader) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        if (traceIdHeader != null) {
            props.setHeader("traceId", traceIdHeader);
        }
        return new Message(new byte[0], props);
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    @DisplayName("从 header 读回 traceId 写入 MDC，处理完清理 MDC，成功 ack 计数")
    void restoresTraceIdFromHeaderAndClearsMdc() throws Exception {
        when(valueOps.get("order:state:" + ORDER_NO)).thenReturn("CREATING");
        AtomicReference<String> duringPersist = new AtomicReference<>();
        doAnswer(inv -> {
            duringPersist.set(MDC.get("traceId"));
            return null;
        }).when(persistenceService).persist(any());

        consumer.consume(message(), channel, raw("trace-from-producer"));

        assertThat(duringPersist.get()).isEqualTo("trace-from-producer");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(counter("mq.consume.success")).isEqualTo(1.0);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("无 traceId header 时不写 MDC（日志退化为 N/A）")
    void keepsMdcEmptyWhenHeaderMissing() throws Exception {
        when(valueOps.get("order:state:" + ORDER_NO)).thenReturn("CREATING");
        AtomicReference<String> duringPersist = new AtomicReference<>("unset");
        doAnswer(inv -> {
            duringPersist.set(MDC.get("traceId"));
            return null;
        }).when(persistenceService).persist(any());

        consumer.consume(message(), channel, raw(null));

        assertThat(duringPersist.get()).isNull();
    }

    @Test
    @DisplayName("状态非 CREATING → 直接 ack，不计 success（幂等短路）")
    void ackWithoutMetricWhenNotCreating() throws Exception {
        when(valueOps.get("order:state:" + ORDER_NO)).thenReturn("UNPAID");

        consumer.consume(message(), channel, raw("t"));

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(counter("mq.consume.success")).isZero();
    }

    @Test
    @DisplayName("落库始终失败 → 重试 2 次(不计末次)、nack、consume.fail/retry 计数、MDC 清理")
    void retriesThenNacks() throws Exception {
        when(valueOps.get("order:state:" + ORDER_NO)).thenReturn("CREATING");
        doThrow(new RuntimeException("db down")).when(persistenceService).persist(any());

        consumer.consume(message(), channel, raw("t"));

        assertThat(counter("mq.consume.retry")).isEqualTo(2.0); // MAX_ATTEMPTS - 1
        assertThat(counter("mq.consume.fail")).isEqualTo(1.0);
        assertThat(counter("mq.consume.success")).isZero();
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        assertThat(MDC.get("traceId")).isNull();
    }
}