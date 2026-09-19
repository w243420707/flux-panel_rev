package com.admin.mapper;

import com.admin.entity.Node;
import com.admin.entity.NodeVpsUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface NodeVpsUsageMapper extends BaseMapper<NodeVpsUsage> {
    @Select("SELECT id, current_usage_id FROM node WHERE id = #{nodeId} FOR UPDATE")
    Node lockNode(@Param("nodeId") Long nodeId);

    @Select("SELECT u.* FROM node_vps_usage u JOIN node n ON n.current_usage_id = u.id "
            + "WHERE n.id = #{nodeId} AND u.node_id = n.id AND u.replaced_at IS NULL")
    NodeVpsUsage findCurrent(@Param("nodeId") Long nodeId);

    @Select("SELECT * FROM node_vps_usage WHERE node_id = #{nodeId} ORDER BY id DESC")
    List<NodeVpsUsage> listByNode(@Param("nodeId") Long nodeId);

    @Update("UPDATE node SET current_usage_id = #{usageId} WHERE id = #{nodeId}")
    int setCurrent(@Param("nodeId") Long nodeId, @Param("usageId") Long usageId);
}
