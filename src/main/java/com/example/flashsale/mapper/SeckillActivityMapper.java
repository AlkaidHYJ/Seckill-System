package com.example.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashsale.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /** 对账遍历：按活动状态取活动列表（JOB-03 只扫 ONGOING）。 */
    @Select("SELECT * FROM seckill_activity WHERE status = #{status} ORDER BY id ASC")
    List<SeckillActivity> findByStatus(@Param("status") String status);

    @Update("UPDATE seckill_activity SET available_stock = available_stock - 1 "
            + "WHERE id = #{activityId} AND available_stock >= 1")
    int deductStock(@Param("activityId") Long activityId);

    /** 超时回补归还 MySQL 库存：条件更新防超还，库存已回满或活动不存在时影响行数为 0。 */
    @Update("UPDATE seckill_activity SET available_stock = available_stock + 1 "
            + "WHERE id = #{activityId} AND available_stock < total_stock")
    int restoreStock(@Param("activityId") Long activityId);
}