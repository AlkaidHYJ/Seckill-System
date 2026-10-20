package com.example.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashsale.entity.StockCompensation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StockCompensationMapper extends BaseMapper<StockCompensation> {

    @Select("SELECT * FROM stock_compensation WHERE status = 'PENDING' "
            + "AND (next_retry_at IS NULL OR next_retry_at <= NOW()) "
            + "ORDER BY next_retry_at ASC LIMIT #{limit}")
    List<StockCompensation> findDue(@Param("limit") int limit);

    /** CAS 领取：并发/重复调度下只有一个执行者能把 PENDING 推进为 COMPENSATING。 */
    @Update("UPDATE stock_compensation SET status = 'COMPENSATING' "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int claim(@Param("id") Long id);

    @Update("UPDATE stock_compensation SET status = 'SUCCESS', last_error = NULL "
            + "WHERE id = #{id} AND status = 'COMPENSATING'")
    int markSuccess(@Param("id") Long id);

    @Update("UPDATE stock_compensation SET status = 'PENDING', "
            + "retry_count = retry_count + 1, next_retry_at = #{nextRetryAt}, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'COMPENSATING'")
    int scheduleRetry(@Param("id") Long id,
                      @Param("nextRetryAt") LocalDateTime nextRetryAt,
                      @Param("lastError") String lastError);

    @Update("UPDATE stock_compensation SET status = 'FAILED', "
            + "retry_count = retry_count + 1, last_error = #{lastError} "
            + "WHERE id = #{id} AND status = 'COMPENSATING'")
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /** 崩溃恢复：领取后未完结且超过 5 分钟的 COMPENSATING 记录退回 PENDING 重新调度。 */
    @Update("UPDATE stock_compensation SET status = 'PENDING', next_retry_at = NOW() "
            + "WHERE status = 'COMPENSATING' AND updated_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)")
    int reclaimStale();

    /** 在途补偿（PENDING/COMPENSATING）计数：JOB-03 对账在途豁免，避免回补未收敛的噪声误报。 */
    @Select("SELECT COUNT(*) FROM stock_compensation WHERE activity_id = #{activityId} "
            + "AND status IN ('PENDING', 'COMPENSATING')")
    int countInFlight(@Param("activityId") Long activityId);
}
