package com.example.flashsale.service;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.entity.Order;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.vo.OrderVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class OrderService {

    private static final String ORDER_STATE_KEY_PREFIX = "order:state:";
    private static final String ORDER_OWNER_KEY_PREFIX = "order:owner:";

    private final OrderMapper orderMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public OrderService(OrderMapper orderMapper, StringRedisTemplate stringRedisTemplate) {
        this.orderMapper = orderMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public OrderVO findOwned(String orderNo, Long userId) {
        Order order = requireOwned(orderNo, userId);
        return toVO(order);
    }

    public Order requireOwned(String orderNo, Long userId) {
        String redisState = stringRedisTemplate.opsForValue()
                .get(ORDER_STATE_KEY_PREFIX + orderNo);
        if ("CREATING".equals(redisState) || "FAILED".equals(redisState)) {
            String ownerId = stringRedisTemplate.opsForValue()
                    .get(ORDER_OWNER_KEY_PREFIX + orderNo);
            if (ownerId == null) {
                throw new BizException(ErrorCode.ORDER_NOT_FOUND);
            }
            if (!String.valueOf(userId).equals(ownerId)) {
                throw new BizException(ErrorCode.FORBIDDEN);
            }
            Order pendingOrder = new Order();
            pendingOrder.setOrderNo(orderNo);
            pendingOrder.setStatus(Order.OrderStatus.valueOf(redisState));
            return pendingOrder;
        }

        Order order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return order;
    }

    public OrderVO toVO(Order order) {
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setActivityId(order.getActivityId());
        vo.setGoodsName(order.getGoodsName());
        vo.setQuantity(order.getQuantity());
        vo.setAmount(order.getAmount());
        vo.setStatus(order.getStatus());
        vo.setPayDeadline(toOffsetDateTime(order.getPayDeadline()));
        vo.setPayTime(toOffsetDateTime(order.getPayTime()));
        vo.setCloseTime(toOffsetDateTime(order.getCloseTime()));
        vo.setCloseReason(order.getCloseReason());
        return vo;
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atOffset(ZoneOffset.ofHours(8));
    }
}