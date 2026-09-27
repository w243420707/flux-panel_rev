package com.admin.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class AwsDatabaseMigrationConfigTest {
    @Test
    void createsAllAwsTablesAndIsIdempotentForExistingDraftSchema() throws Exception {
        JdbcDataSource source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:aws_migration;MODE=MySQL;DB_CLOSE_DELAY=-1");
        AwsDatabaseMigrationConfig migration = new AwsDatabaseMigrationConfig();
        try (Connection connection = source.getConnection()) {
            migration.migrate(connection); migration.migrate(connection);
            for (String table : new String[]{"AWS_ACCOUNT", "AWS_ACCOUNT_GROUP", "AWS_PROXY_POOL", "AWS_EC2_INSTANCE_PROFILE", "AWS_EC2_INSTANCE_TEMPLATE", "AWS_NODE_BINDING", "AWS_SHARE_GROUP", "AWS_FAILOVER_CONFIG", "AWS_OPERATION", "AWS_FAILOVER_JOB"}) {
                try (ResultSet rows = connection.getMetaData().getTables(null, null, table, null)) { assertTrue(rows.next(), table); }
            }
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT * FROM aws_account WHERE 1=0")) {
                assertNotNull(rows.getMetaData().getColumnLabel(3)); assertNotNull(rows.getMetaData().getColumnLabel(4));
            }
        }
    }

    @Test
    void upgradesOlderTablesPreservesBindingsAndEncryptsOldAccessKeys() throws Exception {
        JdbcDataSource source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:aws_legacy;MODE=MySQL;DB_CLOSE_DELAY=-1");
        AwsDatabaseMigrationConfig migration = new AwsDatabaseMigrationConfig(); ReflectionTestUtils.setField(migration, "encryptionSecret", "migration-test-only-key");
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE aws_account(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(120) NOT NULL,access_key_id VARCHAR(128) NOT NULL,access_secret_encrypted LONGTEXT NOT NULL,region VARCHAR(80) NOT NULL,group_name VARCHAR(120))");
            statement.execute("INSERT INTO aws_account(id,name,access_key_id,access_secret_encrypted,region,group_name) VALUES(7,'legacy','AKIA1234567890TEST','stored-secret','us-east-1','original')");
            statement.execute("CREATE TABLE aws_ec2_instance_template(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(255) NOT NULL)");
            statement.execute("INSERT INTO aws_ec2_instance_template(id,name) VALUES(3,'original-template')");
            statement.execute("CREATE TABLE aws_share_group(id BIGINT AUTO_INCREMENT PRIMARY KEY,name VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE aws_failover_config(id BIGINT PRIMARY KEY,enabled TINYINT DEFAULT 0,max_instances INT DEFAULT 0,cron VARCHAR(128) DEFAULT '*/10 * * * *')");
            statement.execute("INSERT INTO aws_failover_config(id,enabled) VALUES(1,1)");
            statement.execute("CREATE TABLE aws_node_binding(node_id BIGINT PRIMARY KEY,account_id BIGINT NOT NULL,instance_id VARCHAR(64) NOT NULL,region VARCHAR(80),updated_time BIGINT)");
            statement.execute("INSERT INTO aws_node_binding(node_id,account_id,instance_id,region) VALUES(11,7,'i-fixed-instance','us-east-1')");
            migration.migrate(connection); migration.migrate(connection);
            try (ResultSet row = statement.executeQuery("SELECT * FROM aws_account WHERE id=7")) {
                assertTrue(row.next()); assertEquals("****TEST", row.getString("access_key_id"));
                assertEquals("AKIA1234567890TEST", new com.admin.common.utils.AESCrypto("migration-test-only-key").decryptString(row.getString("access_key_encrypted")));
                assertTrue(row.getLong("group_id") > 0); assertEquals("stored-secret", row.getString("access_secret_encrypted"));
            }
            try (ResultSet row = statement.executeQuery("SELECT instance_id FROM aws_node_binding WHERE node_id=11")) { assertTrue(row.next()); assertEquals("i-fixed-instance", row.getString(1)); }
            try (ResultSet row = statement.executeQuery("SELECT enabled,cron FROM aws_failover_config WHERE id=1")) { assertTrue(row.next()); assertEquals(0, row.getInt(1)); assertEquals("0 */10 * * * *", row.getString(2)); }
            try (ResultSet row = statement.executeQuery("SELECT name,password_encrypted,availability_zone,spot FROM aws_ec2_instance_template WHERE id=3")) { assertTrue(row.next()); assertEquals("original-template", row.getString(1)); }
        }
    }

    @Test
    void canonicalSchemaAndNewInstallSqlHaveIdenticalAwsColumnsAndIndices() throws Exception {
        String canonical = Files.readString(Path.of("src/main/resources/aws-schema.sql"));
        String installer = Files.readString(Path.of("../gost.sql"));
        assertEquals(tableColumns(canonical), tableColumns(installer));
        Set<String> expected = namedIndices(canonical), actual = namedIndices(installer);
        assertEquals(expected, actual);
    }
    private Map<String, Set<String>> tableColumns(String sql) {
        Map<String, Set<String>> result = new TreeMap<>();
        Matcher tables = Pattern.compile("CREATE TABLE IF NOT EXISTS `(aws_[a-z0-9_]+)` \\((.*?)\\) ENGINE", Pattern.DOTALL).matcher(sql);
        while (tables.find()) {
            Set<String> columns = new TreeSet<>(); Matcher fields = Pattern.compile("(?m)^\\s*`([a-z0-9_]+)` ").matcher(tables.group(2));
            while (fields.find()) columns.add(fields.group(1)); result.put(tables.group(1), columns);
        }
        return result;
    }
    private Set<String> namedIndices(String sql) {
        Set<String> result = new TreeSet<>(); Matcher matches = Pattern.compile("(?:UNIQUE )?KEY `((?:idx|uk)_aws_[a-z0-9_]+)`").matcher(sql);
        while (matches.find()) result.add(matches.group(1)); return result;
    }
}
