package com.admin.mapper;

import com.admin.entity.Forward;
import com.admin.common.dto.ForwardWithTunnelDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
public interface ForwardMapper extends BaseMapper<Forward> {

    /**
     * 查询所有转发信息（包含隧道信息）
     * @return 转发信息列表
     */
    List<ForwardWithTunnelDto> selectAllForwardsWithTunnel();

    /**
     * 根据用户ID查询转发信息（包含隧道信息）
     * @param userId 用户ID
     * @return 转发信息列表
     */
    List<ForwardWithTunnelDto> selectForwardsWithTunnelByUserId(@Param("userId") Integer userId);

    /**
     * 显式清除已恢复转发的历史失败明细。MyBatis 默认不会更新为 null 的字段。
     */
    @Update("UPDATE forward SET pending_nodes = NULL WHERE id = #{forwardId}")
    int clearPendingNodes(@Param("forwardId") Long forwardId);

}
