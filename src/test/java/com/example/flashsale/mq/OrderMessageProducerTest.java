package com.example.flashsale.mq;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可观测（输出 1 traceId 跨 MQ 生产端 / 输出 3 mq.send.*）单元测试：
 * mock RabbitTemplate 捕获 MessagePostProcessor 与 ConfirmCallback，验证 traceId 写入 AMQP header
 * 以及 ack/nack/异常三种路径下的 mq.send 计数，不依赖真实 MQ。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("可观测 · MQ 生产端（traceId header + mq.send.*）")
class OrderMessageProducerTest {

    private static final String EXCHANGE = "seckill.order.exchange";
    private static final String ROUTING_KEY = "order.create";

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ZSetOperations<String, String> zsetOps;

    private SimpleMeterRegistry registry;
    private OrderMessageProducer producer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        producer = new OrderMessageProducer(rabbitTemplate, redis, registry, EXCHANGE, ROUTING_KEY);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private OrderMessage message(String orderNo) {
        return new OrderMessage(orderNo, 1001L, 7L, "iPhone", new BigDecimal("9.99"));
    }

    private MessagePostProcessor capturePostProcessor() {
        ArgumentCaptor<MessagePostProcessor> captor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), any(),
                captor.capture(), any(CorrelationData.class));
        return captor.getValue();
    }

    private RabbitTemplate.ConfirmCallback captureConfirmCallback() {
        ArgumentCaptor<RabbitTemplate.ConfirmCallback> captor =
                ArgumentCaptor.forClass(RabbitTemplate.ConfirmCallback.class);
        verify(rabbitTemplate).setConfirmCallback(captor.capture());
        return captor.getValue();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    @DisplayName("发送时把 MDC.traceId 写入 AMQP header（body 协议不变）")
    void writesTraceIdHeaderFromMdc() {
        MDC.put("traceId", "trace-abc-123");

        producer.send(message("SK1"));

        Message amqp = new Message(new byte[0], new MessageProperties());
        Message processed = capturePostProcessor().postProcessMessage(amqp);
        Object traceHeader = processed.getMessageProperties().getHeader("traceId");
        assertThat(traceHeader).isEqualTo("trace-abc-123");
    }

    @Test
    @DisplayName("MDC 无 traceId 时不写 header（老链路保持 N/A，不伪造）")
    void skipsHeaderWhenMdcEmpty() {
        producer.send(message("SK2"));

        Message amqp = new Message(new byte[0], new MessageProperties());
        Message processed = capturePostProcessor().postProcessMessage(amqp);
        Object traceHeader = processed.getMessageProperties().getHeader("traceId");
        assertThat(traceHeader).isNull();
    }

    @Test
    @DisplayName("Publisher Confirm ack=true → mq.send.success +1")
    void confirmAckIncrementsSuccess() {
        RabbitTemplate.ConfirmCallback callback = captureConfirmCallback();

        producer.send(message("SK3"));
        callback.confirm(new CorrelationData("SK3"), true, null);

        assertThat(counter("mq.send.success")).isEqualTo(1.0);
        assertThat(counter("mq.send.fail")).isZero();
    }

    @Test
    @DisplayName("Publisher Confirm ack=false → mq.send.fail +1 并回滚 Redis 预占")
    void confirmNackIncrementsFailAndRollsBack() {
        RabbitTemplate.ConfirmCallback callback = captureConfirmCallback();

        producer.send(message("SK4"));
        callback.confirm(new CorrelationData("SK4"), false, "broker nack");

        assertThat(counter("mq.send.fail")).isEqualTo(1.0);
        assertThat(counter("mq.send.success")).isZero();
        verify(valueOps).set(eq("order:state:SK4"), eq("FAILED"), any());
        verify(zsetOps).remove(eq("seckill:creating"), eq("SK4"));
    }

    @Test
    @DisplayName("broker 直接抛异常 → mq.send.fail +1 且异常上抛")
    void publishExceptionIncrementsFail() {
        doThrow(new RuntimeException("broker down")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        assertThatThrownBy(() -> producer.send(message("SK5")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("broker down");

        assertThat(counter("mq.send.fail")).isEqualTo(1.0);
        assertThat(counter("mq.send.success")).isZero();
    }
}