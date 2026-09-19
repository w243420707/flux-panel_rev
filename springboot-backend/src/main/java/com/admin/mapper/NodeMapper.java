package com.admin.mapper;

import com.admin.entity.Node;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
public interface NodeMapper extends BaseMapper<Node> {

    @Update("UPDATE node SET reboot_interval_hours = #{hours}, reboot_next_at = #{nextAt} WHERE id = #{id}")
    int updateRebootSchedule(@Param("id") Long id, @Param("hours") int hours, @Param("nextAt") Long nextAt);

    @Update("UPDATE node SET reboot_next_at = #{nextAt} WHERE id = #{id}")
    int updateRebootNextAt(@Param("id") Long id, @Param("nextAt") Long nextAt);

    // Consume a due occurrence before sending: a restart or another worker cannot dispatch it twice.
    @Update("UPDATE node SET reboot_next_at = NULL WHERE id = #{id} AND reboot_interval_hours > 0 "
            + "AND reboot_next_at = #{expectedAt} AND reboot_next_at <= #{now}")
    int claimDueReboot(@Param("id") Long id, @Param("expectedAt") long expectedAt, @Param("now") long now);

    @Select("SELECT * FROM node WHERE reboot_interval_hours > 0 AND reboot_next_at <= #{now} ORDER BY reboot_next_at LIMIT 100")
    List<Node> findDueReboots(@Param("now") long now);

}
