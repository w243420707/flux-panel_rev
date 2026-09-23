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

    @Update("UPDATE node SET change_ip_last_attempt_at = #{attemptAt}, change_ip_last_result = 'PENDING' "
            + "WHERE id = #{id} AND oracle_node = 1 AND oci_account_id IS NOT NULL "
            + "AND oci_instance_ocid IS NOT NULL AND TRIM(oci_instance_ocid) <> '' "
            + "AND (change_ip_last_attempt_at IS NULL OR change_ip_last_attempt_at <= "
            + "#{attemptAt} - GREATEST(COALESCE(change_ip_min_interval_minutes, 3), 3) * 60000)")
    int reserveChangeIpAttempt(@Param("id") Long id, @Param("attemptAt") long attemptAt);

    @Update("UPDATE node SET change_ip_last_result = #{result} WHERE id = #{id} "
            + "AND change_ip_last_attempt_at = #{attemptAt} AND change_ip_last_result = 'PENDING'")
    int finishChangeIpAttempt(@Param("id") Long id,
                              @Param("attemptAt") long attemptAt,
                              @Param("result") String result);

    @Update("UPDATE node SET oracle_node = #{oracleNode}, oci_account_id = #{accountId}, "
            + "oci_instance_ocid = #{instanceOcid} WHERE id = #{id}")
    int updateOciBinding(@Param("id") Long id,
                         @Param("oracleNode") int oracleNode,
                         @Param("accountId") Long accountId,
                         @Param("instanceOcid") String instanceOcid);

    @Select("SELECT COUNT(*) FROM node WHERE oci_account_id = #{accountId}")
    int countNodesUsingOciAccount(@Param("accountId") Long accountId);

}
