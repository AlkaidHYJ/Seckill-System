package com.example.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flashsale.entity.ReconciliationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReconciliationRecordMapper extends BaseMapper<ReconciliationRecord> {

    /** 追溯用：按活动取最近 N 条对账记录，走 idx_activity_ctime。 */
    @Select("SELECT * FROM reconciliation_record WHERE activity_id = #{activityId} "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<ReconciliationRecord> findRecent(@Param("activityId") Long activityId,
                                          @Param("limit") int limit);
}