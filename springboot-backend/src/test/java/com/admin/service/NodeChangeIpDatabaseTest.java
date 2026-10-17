package com.admin.service;

import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NodeChangeIpDatabaseTest {
    private static final long NODE_ID = 41L;
    private JdbcTemplate jdbc;
    private NodeMapper nodes;

    @BeforeEach
    void setUpMapperAndSchema() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:change_ip_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE node (id BIGINT PRIMARY KEY, oracle_node INT NOT NULL, cloud_provider VARCHAR(16) NOT NULL DEFAULT 'NONE', "
                + "oci_account_id BIGINT, oci_instance_ocid VARCHAR(255), aws_account_id BIGINT, aws_instance_id VARCHAR(255), aws_region VARCHAR(80), "
                + "wall_monitor_status VARCHAR(32), wall_monitor_external_status VARCHAR(32), "
                + "server_ip VARCHAR(255), server_ipv4 VARCHAR(255), server_ipv6 VARCHAR(255), ip VARCHAR(255), updated_time BIGINT, "
                + "wall_monitor_last_check_at BIGINT, wall_monitor_consecutive_failures INT, wall_monitor_message VARCHAR(255), "
                + "wall_monitor_external_last_check_at BIGINT, wall_monitor_external_consecutive_failures INT, wall_monitor_external_message VARCHAR(255), "
                + "change_ip_min_interval_minutes INT, change_ip_last_attempt_at BIGINT, "
                + "change_ip_last_result VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN', change_ip_count BIGINT NOT NULL DEFAULT 0)");
        jdbc.update("INSERT INTO node (id, oracle_node, cloud_provider, oci_account_id, oci_instance_ocid) VALUES (?, 1, 'OCI', 2, 'ocid1.instance.test')",
                NODE_ID);

        MybatisSqlSessionFactoryBean builder = new MybatisSqlSessionFactoryBean();
        builder.setDataSource(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        builder.setConfiguration(configuration);
        SqlSessionFactory factory = builder.getObject();
        assertNotNull(factory);
        factory.getConfiguration().addMapper(NodeMapper.class);
        nodes = new SqlSessionTemplate(factory).getMapper(NodeMapper.class);
    }

    @Test
    void emptyAndLegacyLowIntervalsUseAtLeastThreeMinutes() {
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 500_000));
        assertEquals(0, nodes.reserveChangeIpAttempt(NODE_ID, 679_999));
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 680_000));

        jdbc.update("UPDATE node SET change_ip_min_interval_minutes = 2, change_ip_last_attempt_at = 0 WHERE id = ?",
                NODE_ID);
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 180_000));
        assertEquals(0, nodes.reserveChangeIpAttempt(NODE_ID, 359_999));
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 360_000));
    }

    @Test
    void concurrentReportsOnlyReserveOneAttemptAndFailureStillStartsCooldown() throws Exception {
        int threadCount = 12;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return nodes.reserveChangeIpAttempt(NODE_ID, 900_000);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long winners = 0;
            for (var result : results) winners += result.get(10, TimeUnit.SECONDS);
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, nodes.finishChangeIpAttempt(NODE_ID, 900_000, "FAILED"));
        assertEquals(0L, jdbc.queryForObject("SELECT change_ip_count FROM node WHERE id = ?", Long.class, NODE_ID));
        assertEquals(0, nodes.reserveChangeIpAttempt(NODE_ID, 1_079_999));
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT change_ip_last_result FROM node WHERE id = ?", String.class, NODE_ID));
    }

    @Test
    void legacyOracleFlagCanReserveWhenCloudProviderWasNotNormalized() {
        jdbc.update("UPDATE node SET cloud_provider = 'NONE', change_ip_last_attempt_at = NULL, change_ip_last_result = 'UNKNOWN' WHERE id = ?", NODE_ID);
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 2_100_000));
    }

    @Test
    void succeededUnknownCanRetryImmediatelyButBlockedCannot() {
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 2_000_000));
        assertEquals(1, nodes.finishChangeIpAttempt(NODE_ID, 2_000_000, "SUCCEEDED"));
        assertEquals(1L, jdbc.queryForObject("SELECT change_ip_count FROM node WHERE id = ?", Long.class, NODE_ID));
        jdbc.update("UPDATE node SET wall_monitor_external_status = 'UNKNOWN' WHERE id = ?", NODE_ID);
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 2_000_001));
        assertEquals(1, nodes.finishChangeIpAttempt(NODE_ID, 2_000_001, "SUCCEEDED"));
        assertEquals(2L, jdbc.queryForObject("SELECT change_ip_count FROM node WHERE id = ?", Long.class, NODE_ID));
        jdbc.update("UPDATE node SET wall_monitor_external_status = 'SUSPECTED_BLOCKED' WHERE id = ?", NODE_ID);
        assertEquals(0, nodes.reserveChangeIpAttempt(NODE_ID, 2_000_002));
    }

    @Test
    void failedUnknownStillRespectsCooldown() {
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 3_000_000));
        assertEquals(1, nodes.finishChangeIpAttempt(NODE_ID, 3_000_000, "FAILED"));
        jdbc.update("UPDATE node SET wall_monitor_external_status = 'UNKNOWN' WHERE id = ?", NODE_ID);
        assertEquals(0, nodes.reserveChangeIpAttempt(NODE_ID, 3_000_001));
    }

    @Test
    void staleCompletionCannotOverwriteNewerReservation() {
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 1_000_000));
        assertEquals(1, nodes.reserveChangeIpAttempt(NODE_ID, 1_180_000));
        assertEquals(0, nodes.finishChangeIpAttempt(NODE_ID, 1_000_000, "FAILED"));
        assertEquals(0L, jdbc.queryForObject("SELECT change_ip_count FROM node WHERE id = ?", Long.class, NODE_ID));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT change_ip_last_result FROM node WHERE id = ?", String.class, NODE_ID));
    }

    @Test
    void partialNodeStatusUpdatesDoNotClearOciBindingButExplicitUnbindDoes() {
        Node partial = new Node();
        partial.setId(NODE_ID);
        partial.setWallMonitorStatus("OK");

        assertEquals(1, nodes.updateById(partial));
        assertEquals(2L, jdbc.queryForObject("SELECT oci_account_id FROM node WHERE id = ?", Long.class, NODE_ID));
        assertEquals("ocid1.instance.test", jdbc.queryForObject(
                "SELECT oci_instance_ocid FROM node WHERE id = ?", String.class, NODE_ID));

        assertEquals(1, nodes.updateOciBinding(NODE_ID, 0, null, null));
        assertNull(jdbc.queryForObject("SELECT oci_account_id FROM node WHERE id = ?", Long.class, NODE_ID));
        assertNull(jdbc.queryForObject("SELECT oci_instance_ocid FROM node WHERE id = ?", String.class, NODE_ID));
    }
}
