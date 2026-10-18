package com.example.flashsale.mq;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;

@Service
public class OrderMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumer.class);
    private static final String ORDER_STATE_KEY_PREFIX = "order:state:";
    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String USER_KEY_PREFIX = "seckill:user:";
    private static final String CREATING_ZSET = "seckill:creating";
    private static final int MAX_ATTEMPTS = 3;

    private final StringRedisTemplate stringRedisTemplate;
    private final OrderPersistenceService persistenceService;

    public OrderMessageConsumer(
            StringRedisTemplate stringRedisTemplate,
            OrderPersistenceService persistenceService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.persistenceService = persistenceService;
    }

    @RabbitListener(queues = "${app.rabbitmq.order.queue:seckill.order.queue}")
    public void consume(OrderMessage message, Channel channel, Message rawMessage) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        String state = getState(message);
        if (!"CREATING".equals(state)) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            retryWithBackoff(() -> persistenceService.persist(message));
            stringRedisTemplate.opsForValue().set(
                    ORDER_STATE_KEY_PREFIX + message.getOrderNo(),
                    "UNPAID",
                    Duration.ofSeconds(300));
            stringRedisTemplate.opsForZSet().remove(CREATING_ZSET, message.getOrderNo());
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            rollback(message, exception);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void retryWithBackoff(Runnable task) {
        long[] delays = {100L, 500L, 1000L};
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                task.run();
                return;
            } catch (RuntimeException exception) {
                log.warn("Order message processing failed, attempt={}/{}",
                        attempt + 1, MAX_ATTEMPTS, exception);
                if (attempt == MAX_ATTEMPTS - 1) {
                    throw exception;
                }
                try {
                    Thread.sleep(delays[attempt]);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Order message retry interrupted", interruptedException);
                }
            }
        }
    }

    private String getState(OrderMessage message) {
        return stringRedisTemplate.opsForValue().get(
                ORDER_STATE_KEY_PREFIX + message.getOrderNo());
    }

    private void rollback(OrderMessage message, Exception cause) {
        stringRedisTemplate.opsForValue().increment(STOCK_KEY_PREFIX + message.getActivityId());
        stringRedisTemplate.delete(USER_KEY_PREFIX + message.getActivityId() + ":" + message.getUserId());
        stringRedisTemplate.opsForValue().set(
                ORDER_STATE_KEY_PREFIX + message.getOrderNo(),
                "FAILED",
                Duration.ofSeconds(300));
        stringRedisTemplate.opsForZSet().remove(CREATING_ZSET, message.getOrderNo());
        log.error("Order message exhausted retries and Redis reservation rolled back, orderNo={}",
                message.getOrderNo(), cause);
    }
}
