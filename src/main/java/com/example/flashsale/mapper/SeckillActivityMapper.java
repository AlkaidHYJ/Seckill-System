package com.example.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashsale.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    @Update("UPDATE seckill_activity SET available_stock = available_stock - 1 "
            + "WHERE id = #{activityId} AND available_stock >= 1")
    int deductStock(@Param("activityId") Long activityId);
}