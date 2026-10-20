package com.example.flashsale.mq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class OrderMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageProducer.class);
    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String USER_KEY_PREFIX = "seckill:user:";
    private static final String ORDER_STATE_KEY_PREFIX = "order:state:";
    private static final String CREATING_ZSET = "seckill:creating";
    private static final Duration FAILED_STATE_TTL = Duration.ofSeconds(300);
    private static final String TRACE_ID_KEY = "traceId";

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final String orderExchange;
    private final String orderRoutingKey;
    private final Counter sendSuccessCounter;
    private final Counter sendFailCounter;
    private final ConcurrentMap<String, OrderMessage> pendingMessages = new ConcurrentHashMap<>();

    public OrderMessageProducer(
            RabbitTemplate rabbitTemplate,
            StringRedisTemplate stringRedisTemplate,
            MeterRegistry meterRegistry,
            @Value("${app.rabbitmq.order.exchange:seckill.order.exchange}") String orderExchange,
            @Value("${app.rabbitmq.order.routing-key:order.create}") String orderRoutingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.orderExchange = orderExchange;
        this.orderRoutingKey = orderRoutingKey;
        this.sendSuccessCounter = Counter.builder("mq.send.success")
                .description("Order messages confirmed acknowledged by RabbitMQ broker")
                .register(meterRegistry);
        this.sendFailCounter = Counter.builder("mq.send.fail")
                .description("Order messages failed to publish or confirmed negatively")
                .register(meterRegistry);
        this.rabbitTemplate.setConfirmCallback(this::handleConfirm);
    }

    public void send(OrderMessage message) {
        CorrelationData correlationData = new CorrelationData(message.getOrderNo());
        pendingMessages.put(message.getOrderNo(), message);
        String traceId = MDC.get(TRACE_ID_KEY);
        MessagePostProcessor postProcessor = amqpMessage -> {
            if (traceId != null) {
                amqpMessage.getMessageProperties().setHeader(TRACE_ID_KEY, traceId);
            }
            return amqpMessage;
        };
        try {
            rabbitTemplate.convertAndSend(
                    orderExchange,
                    orderRoutingKey,
                    message,
                    postProcessor,
                    correlationData);
            log.info("发送订单消息: orderNo={}, activityId={}, traceId={}",
                    message.getOrderNo(), message.getActivityId(), traceId);
        } catch (RuntimeException exception) {
            pendingMessages.remove(message.getOrderNo());
            sendFailCounter.increment();
            rollback(message, exception);
            throw exception;
        }
    }

    private void handleConfirm(CorrelationData correlationData, boolean acknowledged, String cause) {
        if (correlationData == null) {
            return;
        }
        OrderMessage message = pendingMessages.remove(correlationData.getId());
        if (message == null) {
            return;
        }
        if (acknowledged) {
            sendSuccessCounter.increment();
        } else {
            sendFailCounter.increment();
            rollback(message, new IllegalStateException("Publisher confirm failed: " + cause));
        }
    }

    private void rollback(OrderMessage message, Exception cause) {
        stringRedisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + message.getActivityId());
        stringRedisTemplate.delete(USER_KEY_PREFIX + message.getActivityId() + ":" + message.getUserId());
        stringRedisTemplate.opsForValue().set(
                ORDER_STATE_KEY_PREFIX + message.getOrderNo(),
                "FAILED",
                FAILED_STATE_TTL);
        stringRedisTemplate.opsForZSet().remove(CREATING_ZSET, message.getOrderNo());
        log.error("Order message publish failed and Redis reservation rolled back, orderNo={}",
                message.getOrderNo(), cause);
    }
}
