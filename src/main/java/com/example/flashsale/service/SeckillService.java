package com.example.flashsale.service;

import com.example.flashsale.common.ErrorCode;
import com.example.flashsale.common.DuplicateKeyResolver;
import com.example.flashsale.common.DuplicateKeyResolver.DuplicateKey;
import com.example.flashsale.entity.Order;
import com.example.flashsale.entity.Order.OrderStatus;
import com.example.flashsale.entity.SeckillActivity;
import com.example.flashsale.entity.SeckillActivity.ActivityStatus;
import com.example.flashsale.exception.BizException;
import com.example.flashsale.mapper.OrderMapper;
import com.example.flashsale.mapper.SeckillActivityMapper;
import com.example.flashsale.vo.SeckillResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class SeckillService {

    private static final int MAX_ORDER_NO_RETRIES = 3;
    private static final int ORDER_QUANTITY = 1;
    private static final DateTimeFormatter ORDER_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final SeckillActivityMapper activityMapper;
    private final OrderMapper orderMapper;

    public SeckillService(SeckillActivityMapper activityMapper, OrderMapper orderMapper) {
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public SeckillResponse createOrder(Long activityId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        SeckillActivity activity = activityMapper.selectById(activityId);
        validateActivity(activity, now);

        if (activityMapper.deductStock(activityId) == 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_STOCK);
        }

        LocalDateTime payDeadline = now.plusMinutes(15);
        for (int attempt = 0; attempt < MAX_ORDER_NO_RETRIES; attempt++) {
            try {
                String orderNo = generateOrderNo(now);
                insertOrder(orderNo, activity, userId, now, payDeadline);
                return new SeckillResponse(orderNo, OrderStatus.UNPAID, toOffsetDateTime(now));
            } catch (DuplicateKeyException exception) {
                DuplicateKey duplicateKey = DuplicateKeyResolver.resolve(exception);
                if (duplicateKey == DuplicateKey.ACTIVITY_USER) {
                    throw new BizException(ErrorCode.ORDER_ALREADY_EXISTS);
                }
                if (duplicateKey != DuplicateKey.ORDER_NO
                        || attempt == MAX_ORDER_NO_RETRIES - 1) {
                    throw new BizException(ErrorCode.DATABASE_ERROR);
                }
            }
        }

        throw new BizException(ErrorCode.DATABASE_ERROR);
    }

    private void validateActivity(SeckillActivity activity, LocalDateTime now) {
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        if (activity.getStatus() == ActivityStatus.PAUSED) {
            throw new BizException(ErrorCode.ACTIVITY_PAUSED);
        }
        if (activity.getStatus() == ActivityStatus.ENDED
                || activity.getEndTime() == null
                || !now.isBefore(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        if (activity.getStatus() == ActivityStatus.NOT_STARTED
                || activity.getStartTime() == null
                || now.isBefore(activity.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (activity.getStatus() != ActivityStatus.ONGOING) {
            throw new BizException(ErrorCode.ACTIVITY_UNAVAILABLE);
        }
    }

    private void insertOrder(String orderNo, SeckillActivity activity, Long userId,
                             LocalDateTime now, LocalDateTime payDeadline) {
        BigDecimal price = activity.getSeckillPrice();
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setActivityId(activity.getId());
        order.setGoodsName(activity.getGoodsName());
        order.setSeckillPrice(price);
        order.setQuantity(ORDER_QUANTITY);
        order.setAmount(price.multiply(BigDecimal.valueOf(ORDER_QUANTITY)));
        order.setStatus(OrderStatus.UNPAID);
        order.setCreateTime(now);
        order.setPayDeadline(payDeadline);
        orderMapper.insert(order);
    }

    private String generateOrderNo(LocalDateTime now) {
        return "SK" + now.format(ORDER_DATE_FORMAT)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime.atOffset(ZoneOffset.ofHours(8));
    }
}