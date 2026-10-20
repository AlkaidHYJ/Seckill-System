package com.example.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashsale.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("SELECT * FROM orders WHERE order_no = #{orderNo} LIMIT 1")
    Order findByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM orders WHERE activity_id = #{activityId} "
            + "AND user_id = #{userId} LIMIT 1")
    Order findByActivityIdAndUserId(@Param("activityId") Long activityId,
                                    @Param("userId") Long userId);

    @Update("UPDATE orders SET status = 'PAID', pay_time = #{payTime} "
            + "WHERE order_no = #{orderNo} AND status = 'UNPAID'")
    int payIfUnpaid(@Param("orderNo") String orderNo,
                    @Param("payTime") LocalDateTime payTime);

    /** 超时扫表：走 idx_status_deadline，每批限量，与支付的双向 CAS 由 closeIfUnpaid 兜住。 */
    @Select("SELECT * FROM orders WHERE status = 'UNPAID' AND pay_deadline < NOW() "
            + "ORDER BY pay_deadline ASC LIMIT #{limit}")
    List<Order> findExpiredUnpaid(@Param("limit") int limit);

    /** 关闭 CAS：影响行数=1 才会发生状态迁移，重复扫描/与支付并发时先到先得。close_reason=1 表示超时。 */
    @Update("UPDATE orders SET status = 'CLOSED_TIMEOUT', close_time = #{closeTime}, close_reason = 1 "
            + "WHERE order_no = #{orderNo} AND status = 'UNPAID'")
    int closeIfUnpaid(@Param("orderNo") String orderNo,
                      @Param("closeTime") LocalDateTime closeTime);

    /** 对账口径：有效售出件数 = UNPAID+PAID 的 quantity 之和（CLOSED_TIMEOUT 已回补、不计入），无记录返回 0。 */
    @Select("SELECT COALESCE(SUM(quantity), 0) FROM orders "
            + "WHERE activity_id = #{activityId} AND status IN ('UNPAID', 'PAID')")
    Integer sumValidQuantity(@Param("activityId") Long activityId);
}