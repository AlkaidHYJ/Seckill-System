package com.example.flashsale.mq;

import com.example.flashsale.entity.Order;
import com.example.flashsale.entity.Order.OrderStatus;
import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.mapper.SeckillActivityMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OrderPersistenceService {

    private final OrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;

    public OrderPersistenceService(
            OrderMapper orderMapper,
            SeckillActivityMapper activityMapper) {
        this.orderMapper = orderMapper;
        this.activityMapper = activityMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void persist(OrderMessage message) {
        if (orderMapper.findByOrderNo(message.getOrderNo()) != null) {
            return;
        }

        SeckillActivity activity = activityMapper.selectById(message.getActivityId());
        if (activity == null || activity.getSeckillPrice() == null) {
            throw new IllegalStateException("Activity not found for order message");
        }
        if (activityMapper.deductStock(message.getActivityId()) == 0) {
            throw new IllegalStateException("MySQL stock deduction returned zero");
        }

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setOrderNo(message.getOrderNo());
        order.setUserId(message.getUserId());
        order.setActivityId(message.getActivityId());
        order.setGoodsName(message.getGoodsName());
        order.setSeckillPrice(activity.getSeckillPrice());
        order.setQuantity(1);
        order.setAmount(message.getAmount());
        order.setStatus(OrderStatus.UNPAID);
        order.setCreateTime(now);
        order.setPayDeadline(now.plusMinutes(15));
        orderMapper.insert(order);
    }
}
