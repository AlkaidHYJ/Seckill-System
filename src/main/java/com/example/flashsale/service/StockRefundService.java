package com.example.flashsale.service;

import com.example.flashsale.entity.StockCompensation;
import com.example.flashsale.mapper.SeckillActivityMapper;
import com.example.flashsale.mapper.StockCompensationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JOB-02 的 DB 事务单元：MySQL 库存归还与补偿任务置 SUCCESS 原子提交。
 * 任一步影响行数为 0 都整体回滚、任务留在 COMPENSATING 等待重试或失败分支，
 * Redis 侧由回补标记保证重放时不重复 INCR。
 */
@Service
public class StockRefundService {

    private final SeckillActivityMapper activityMapper;
    private final StockCompensationMapper compensationMapper;

    public StockRefundService(
            SeckillActivityMapper activityMapper,
            StockCompensationMapper compensationMapper) {
        this.activityMapper = activityMapper;
        this.compensationMapper = compensationMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void restoreAndComplete(StockCompensation record) {
        if (activityMapper.restoreStock(record.getActivityId()) == 0) {
            throw new IllegalStateException(
                    "MySQL stock restore rejected, activityId=" + record.getActivityId());
        }
        if (compensationMapper.markSuccess(record.getId()) == 0) {
            // 领取权已丢失（如被 reclaimStale 重置后并发执行），回滚归还避免重复补库存
            throw new IllegalStateException(
                    "Compensation claim lost before success, orderNo=" + record.getOrderNo());
        }
    }
}
