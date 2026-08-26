package com.admin.mapper;

import com.admin.entity.SiteTraffic;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for the singleton site traffic counter.
 */
public interface SiteTrafficMapper extends BaseMapper<SiteTraffic> {

    @Update("UPDATE site_traffic "
            + "SET total_in_flow = total_in_flow + #{inFlow}, "
            + "total_out_flow = total_out_flow + #{outFlow}, "
            + "updated_time = #{updatedTime} "
            + "WHERE id = 1")
    int increment(@Param("inFlow") long inFlow,
                  @Param("outFlow") long outFlow,
                  @Param("updatedTime") long updatedTime);

    @Select("SELECT id, total_in_flow AS totalInFlow, total_out_flow AS totalOutFlow, "
            + "created_time AS createdTime, updated_time AS updatedTime "
            + "FROM site_traffic WHERE id = 1")
    SiteTraffic selectSingleton();
}
