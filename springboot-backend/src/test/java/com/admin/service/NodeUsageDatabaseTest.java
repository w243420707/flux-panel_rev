package com.admin.service;

import com.admin.common.dto.UsageSnapshot;
import com.admin.config.NodeUsageSchema;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.NodeVpsUsageMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NodeUsageDatabaseTest {
    private static final long NODE = 7;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T08:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private NodeMapper nodes;
    private NodeVpsUsageMapper usages;
    private TransactionTemplate transactions;
    private NodeUsageService service;

    @BeforeEach
    void setUpRealMappersAndSchema() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:usage_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        String install = Files.readString(Path.of("..", "gost.sql"), StandardCharsets.UTF_8);
        int start = install.indexOf("CREATE TABLE `node` (");
        assertTrue(start >= 0, "Use the actual fresh-install node schema");
        jdbc.execute(install.substring(start, install.indexOf(';', start) + 1));
        // gost.sql adds node's primary key later, after all CREATE statements.
        jdbc.execute("ALTER TABLE node ADD PRIMARY KEY (id)");
        jdbc.execute(NodeUsageSchema.CREATE_TABLE);
        jdbc.update("INSERT INTO node (id,name,secret,port_sta,port_end,created_time,status) "
                + "VALUES (7,'test node','only-a-test-key',1000,65535,1,1)");
        MybatisSqlSessionFactoryBean builder = new MybatisSqlSessionFactoryBean();
        builder.setDataSource(source);
        MybatisConfiguration config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        builder.setConfiguration(config);
        SqlSessionFactory factory = builder.getObject();
        assertNotNull(factory);
        factory.getConfiguration().addMapper(NodeMapper.class);
        factory.getConfiguration().addMapper(NodeVpsUsageMapper.class);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory);
        nodes = sessions.getMapper(NodeMapper.class);
        usages = sessions.getMapper(NodeVpsUsageMapper.class);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        service = new NodeUsageService(nodes, usages, transactions, CLOCK);
    }

    @Test
    void persistedCountersSurviveServiceRecreationAndRealDeletesEnforceThreeRecords() {
        var initial = admit(1, 1, 100, 200);
        assertEquals(0, initial.summary().current().totalBytes());
        service.acceptSample(NODE, initial.usageId(), snapshot(1, 2, 160, 230));
        assertEquals(90, service.summary(NODE).current().totalBytes());
        service = new NodeUsageService(nodes, usages, transactions, CLOCK);
        assertEquals(initial.usageId(), nodes.selectById(NODE).getCurrentUsageId());
        assertEquals(90, service.summary(NODE).current().totalBytes());
        assertEquals(140, admit(1, 3, 180, 260).summary().current().totalBytes());
        for (int identity = 2; identity <= 5; identity++) {
            assertTrue(admit(identity, 1, 1_000, 2_000).accepted());
            assertTrue(count() <= 3);
        }
        assertEquals(3, count());
        assertNull(usages.selectById(initial.usageId()));
        var history = (NodeUsageService.UsageHistory) service.history(NODE).getData();
        assertEquals(3, history.records().size());
        assertEquals(2, history.records().stream().filter(record -> record.replacedAt() != null).count());
    }

    @Test
    void deletingTheLogicalNodeCascadesCurrentAndHistoryRows() {
        for (int identity = 1; identity <= 3; identity++) admit(identity, 1, 0, 0);
        assertEquals(3, count());

        assertEquals(1, nodes.deleteById(NODE));

        assertEquals(0, count());
        assertNull(usages.findCurrent(NODE));
    }

    @Test
    void aDatabaseWriteFailureRollsBackRetirementInsertionAndTheCurrentPointer() {
        Long current = admit(1, 1, 0, 0).usageId();
        jdbc.execute("ALTER TABLE node_vps_usage ADD CONSTRAINT reject_replacement CHECK (hardware_id <> '" + hash(2) + "')");

        assertThrows(RuntimeException.class, () -> admit(2, 1, 0, 0));

        assertEquals(1, count());
        assertEquals(current, nodes.selectById(NODE).getCurrentUsageId());
        assertNull(usages.selectById(current).getReplacedAt());
        assertEquals(0, service.summary(NODE).current().totalBytes());
    }

    private int count() { return jdbc.queryForObject("SELECT COUNT(*) FROM node_vps_usage", Integer.class); }
    private NodeUsageService.AdmissionDecision admit(int identity, long seq, long upload, long download) {
        return service.admit(NODE, "session-" + identity, snapshot(identity, seq, upload, download), false, "203.0.113.1");
    }
    private static UsageSnapshot snapshot(int identity, long seq, long upload, long download) {
        return new UsageSnapshot(hash(identity), hash(identity), "", "aaaaaaaa-1111-2222-3333-000000000001",
                "aaaaaaaa-1111-2222-3333-000000000002", seq, upload, download);
    }
    private static String hash(int value) { return String.format("%064x", value); }
}
