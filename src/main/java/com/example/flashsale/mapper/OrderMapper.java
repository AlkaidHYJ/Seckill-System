package com.example.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashsale.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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
}