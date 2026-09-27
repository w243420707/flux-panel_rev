package com.admin.mapper;

import com.admin.entity.AwsFailoverConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AwsFailoverConfigMapper extends BaseMapper<AwsFailoverConfig> {
    @Update("UPDATE aws_failover_config SET next_check_at=#{next}, lease_until=#{lease} "
            + "WHERE id=1 AND enabled=1 AND (next_check_at IS NULL OR next_check_at<=#{now}) "
            + "AND (lease_until IS NULL OR lease_until<=#{now})")
    int claim(@Param("now") long now, @Param("next") long next, @Param("lease") long lease);

    @Update("UPDATE aws_failover_config SET lease_until=NULL WHERE id=1 AND lease_until=#{lease}")
    int release(@Param("lease") long lease);
}
