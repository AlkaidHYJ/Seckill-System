package com.example.flashsale.service;

import com.example.flashsale.entity.Order;
import com.example.flashsale.entity.StockCompensation;
import com.example.flashsale.entity.StockCompensation.CompensationStatus;
import com.example.flashsale.entity.StockCompensation.CompensationType;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.mapper.StockCompensationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * JOB-01 的关闭事务单元：订单 CAS 关闭与 TIMEOUT_REFUND 补偿任务原子提交，
 * 消除「已关闭但无补偿任务」的孤儿窗口；关闭失败（影响行数 0）不建任务、不回补。
 */
@Service
public class TimeoutCloseService {

    private final OrderMapper orderMapper;
    private final StockCompensationMapper compensationMapper;

    public TimeoutCloseService(
            OrderMapper orderMapper,
            StockCompensationMapper compensationMapper) {
        this.orderMapper = orderMapper;
        this.compensationMapper = compensationMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean closeAndCreateCompensation(Order order, LocalDateTime now) {
        if (orderMapper.closeIfUnpaid(order.getOrderNo(), now) == 0) {
            // 已支付或已被并发关闭，先到先得，本单不做任何回补动作
            return false;
        }

        StockCompensation task = new StockCompensation();
        task.setOrderNo(order.getOrderNo());
        task.setActivityId(order.getActivityId());
        task.setCompensationType(CompensationType.TIMEOUT_REFUND);
        task.setStatus(CompensationStatus.PENDING);
        task.setRetryCount(0);
        task.setNextRetryAt(now);
        try {
            compensationMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            // uk_compensation_order_type 已存在同类型任务：幂等跳过，不产生第二份回补
        }
        return true;
    }
}
