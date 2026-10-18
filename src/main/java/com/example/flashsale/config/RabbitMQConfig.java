package com.example.flashsale.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class RabbitMQConfig {

    @Value("${app.rabbitmq.order.exchange:seckill.order.exchange}")
    private String orderExchangeName;

    @Value("${app.rabbitmq.order.queue:seckill.order.queue}")
    private String orderQueueName;

    @Value("${app.rabbitmq.order.routing-key:order.create}")
    private String orderRoutingKey;

    @Value("${app.rabbitmq.order.dlx-exchange:seckill.order.dlx}")
    private String orderDlxExchangeName;

    @Value("${app.rabbitmq.order.dlx-queue:seckill.order.dlq}")
    private String orderDlxQueueName;

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(orderExchangeName, true, false);
    }

    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange(orderDlxExchangeName, true, false);
    }

    @Bean
    public Queue orderQueue() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-dead-letter-exchange", orderDlxExchangeName);
        arguments.put("x-dead-letter-routing-key", orderRoutingKey);
        return QueueBuilder.durable(orderQueueName)
                .withArguments(arguments)
                .build();
    }

    @Bean
    public Queue orderDlxQueue() {
        return QueueBuilder.durable(orderDlxQueueName).build();
    }

    @Bean
        public Binding orderBinding(
            @Qualifier("orderQueue") Queue orderQueue,
            @Qualifier("orderExchange") DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue)
                .to(orderExchange)
                .with(orderRoutingKey);
    }

    @Bean
        public Binding orderDlxBinding(
            @Qualifier("orderDlxQueue") Queue orderDlxQueue,
            @Qualifier("orderDlxExchange") DirectExchange orderDlxExchange) {
        return BindingBuilder.bind(orderDlxQueue)
                .to(orderDlxExchange)
                .with(orderRoutingKey);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}