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
            + "WHERE id = #{id} AND cloud_provider IN ('OCI', 'AWS') "
            + "AND ((cloud_provider = 'OCI' AND oci_account_id IS NOT NULL AND oci_instance_ocid IS NOT NULL AND TRIM(oci_instance_ocid) <> '') "
            + "OR (cloud_provider = 'AWS' AND aws_account_id IS NOT NULL AND aws_instance_id IS NOT NULL AND TRIM(aws_instance_id) <> '' AND aws_region IS NOT NULL AND TRIM(aws_region) <> '')) "
            + "AND ((change_ip_last_attempt_at IS NULL OR change_ip_last_attempt_at <= "
            + "#{attemptAt} - GREATEST(COALESCE(change_ip_min_interval_minutes, 3), 3) * 60000) "
            + "OR (change_ip_last_result = 'SUCCEEDED' AND wall_monitor_external_status = 'UNKNOWN'))")
    int reserveChangeIpAttempt(@Param("id") Long id, @Param("attemptAt") long attemptAt);

    @Update("UPDATE node SET ip = CASE WHEN ip IS NULL OR ip = '' OR ip = server_ip OR ip = server_ipv4 OR ip = server_ipv6 THEN #{ip} ELSE ip END, "
            + "server_ip = #{ip}, server_ipv4 = #{ip}, updated_time = #{attemptAt}, "
            + "wall_monitor_status = 'UNKNOWN', wall_monitor_last_check_at = NULL, "
            + "wall_monitor_consecutive_failures = 0, wall_monitor_message = '公网 IP 已更换，等待 APK 确认', "
            + "wall_monitor_external_status = 'UNKNOWN', wall_monitor_external_last_check_at = NULL, "
            + "wall_monitor_external_consecutive_failures = 0, wall_monitor_external_message = '公网 IP 已更换，等待 APK 确认' "
            + "WHERE id = #{id} AND change_ip_last_attempt_at = #{attemptAt} AND change_ip_last_result = 'PENDING'")
    int updateAddressAfterChangeIp(@Param("id") Long id,
                                   @Param("attemptAt") long attemptAt,
                                   @Param("ip") String ip);

    @Update("UPDATE node SET change_ip_last_result = #{result} WHERE id = #{id} "
            + "AND change_ip_last_attempt_at = #{attemptAt} AND change_ip_last_result = 'PENDING'")
    int finishChangeIpAttempt(@Param("id") Long id,
                              @Param("attemptAt") long attemptAt,
                              @Param("result") String result);

    @Update("UPDATE node SET oracle_node = #{oracleNode}, oci_account_id = #{accountId}, "
            + "oci_instance_ocid = #{instanceOcid}, cloud_provider = #{cloudProvider}, "
            + "aws_account_id = #{awsAccountId}, aws_instance_id = #{awsInstanceId}, aws_region = #{awsRegion} WHERE id = #{id}")
    int updateCloudBinding(@Param("id") Long id,
                         @Param("oracleNode") int oracleNode,
                         @Param("accountId") Long accountId,
                         @Param("instanceOcid") String instanceOcid,
                         @Param("cloudProvider") String cloudProvider,
                         @Param("awsAccountId") Long awsAccountId,
                         @Param("awsInstanceId") String awsInstanceId,
                         @Param("awsRegion") String awsRegion);

    /** Compatibility alias used by older tests and integrations. */
    @Update("UPDATE node SET oracle_node = #{oracleNode}, oci_account_id = #{accountId}, "
            + "oci_instance_ocid = #{instanceOcid} WHERE id = #{id}")
    int updateOciBinding(@Param("id") Long id,
                         @Param("oracleNode") int oracleNode,
                         @Param("accountId") Long accountId,
                         @Param("instanceOcid") String instanceOcid);

    @Update("UPDATE node SET aws_account_id=#{account}, aws_instance_id=#{instance}, aws_region=#{region}, "
            + "server_ip=#{ip},server_ipv4=#{ipv4},server_ipv6=#{ipv6}, wall_monitor_external_status='UNKNOWN', "
            + "wall_monitor_external_last_check_at=NULL, wall_monitor_external_message='实例已替补，等待 APK 确认', "
            + "change_ip_last_attempt_at=NULL,change_ip_last_result='UNKNOWN' "
            + "WHERE id=#{id} AND cloud_provider='AWS' AND aws_account_id=#{oldAccount} "
            + "AND aws_instance_id=#{oldInstance} AND aws_region=#{oldRegion}")
    int replaceAwsBinding(@Param("id") Long id, @Param("oldAccount") Long oldAccount,
                          @Param("oldInstance") String oldInstance, @Param("oldRegion") String oldRegion,
                          @Param("account") Long account, @Param("instance") String instance,
                          @Param("region") String region, @Param("ip") String ip,
                          @Param("ipv4") String ipv4, @Param("ipv6") String ipv6);

    @Update("UPDATE node SET change_ip_last_attempt_at=NULL,change_ip_last_result='UNKNOWN' WHERE id=#{id}")
    int invalidateChangeIpAttempt(@Param("id") Long id);

    /** Backward-compatible OCI-only update used by older callers/tests. */
    @Select("SELECT COUNT(*) FROM node WHERE oci_account_id = #{accountId}")
    int countNodesUsingOciAccount(@Param("accountId") Long accountId);

}
