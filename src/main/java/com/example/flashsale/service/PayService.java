package com.example.flashsale.service;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.entity.Order;
import com.example.flashsale.entity.Order.OrderStatus;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.vo.OrderVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class PayService {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    public PayService(OrderMapper orderMapper, OrderService orderService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderVO pay(String orderNo, Long userId) {
        Order order = orderService.requireOwned(orderNo, userId);
        if (order.getStatus() == OrderStatus.PAID) {
            return orderService.toVO(order);
        }

        LocalDateTime payTime = LocalDateTime.now();
        if (orderMapper.payIfUnpaid(orderNo, payTime) == 1) {
            return orderService.toVO(orderMapper.findByOrderNo(orderNo));
        }

        Order current = orderService.requireOwned(orderNo, userId);
        if (current.getStatus() == OrderStatus.PAID) {
            return orderService.toVO(current);
        }
        if (current.getStatus() == OrderStatus.CLOSED_TIMEOUT) {
            throw new BizException(ErrorCode.ORDER_TIMEOUT);
        }
        throw new BizException(ErrorCode.ORDER_NOT_PAYABLE);
    }
}