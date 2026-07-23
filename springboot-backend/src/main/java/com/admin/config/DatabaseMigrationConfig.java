package com.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Slf4j
@Component
public class DatabaseMigrationConfig implements ApplicationRunner {

    @Resource
    private DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            runStep("add tunnel.in_node_ids", () -> ensureColumn(connection, "tunnel", "in_node_ids", "ALTER TABLE tunnel ADD COLUMN in_node_ids LONGTEXT NULL AFTER in_node_id"));
            runStep("add tunnel.out_node_ids", () -> ensureColumn(connection, "tunnel", "out_node_ids", "ALTER TABLE tunnel ADD COLUMN out_node_ids LONGTEXT NULL AFTER out_node_id"));
            runStep("widen tunnel ip columns", () -> widenIpColumns(connection));
            runStep("create cloudflare dns tables", () -> createCloudflareTables(connection));
            runStep("seed cloudflare dns setting", () -> seedCloudflareSetting(connection));
            runStep("backfill tunnel node arrays", () -> backfillNodeArrays(connection));
        } catch (Exception e) {
            log.warn("Database migration check failed: {}", e.getMessage());
        }
    }

    private void runStep(String name, MigrationStep step) {
        try {
            step.run();
        } catch (Exception e) {
            log.warn("Database migration step failed [{}]: {}", name, e.getMessage());
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String ddl) throws Exception {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            log.info("Database column added: {}.{}", table, column);
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private void backfillNodeArrays(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE tunnel SET in_node_ids = CONCAT('[', in_node_id, ']') WHERE (in_node_ids IS NULL OR in_node_ids = '') AND in_node_id IS NOT NULL");
            statement.executeUpdate("UPDATE tunnel SET out_node_ids = CONCAT('[', out_node_id, ']') WHERE (out_node_ids IS NULL OR out_node_ids = '') AND out_node_id IS NOT NULL");
        }
    }

    private void widenIpColumns(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE tunnel MODIFY COLUMN in_ip VARCHAR(1000) NOT NULL");
            statement.execute("ALTER TABLE tunnel MODIFY COLUMN out_ip VARCHAR(1000) NOT NULL");
        }
    }

    private void createCloudflareTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cloudflare_dns_setting (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                    "created_time BIGINT NULL," +
                    "updated_time BIGINT NULL," +
                    "status INT NULL," +
                    "enabled INT NULL," +
                    "api_token VARCHAR(1000) NULL," +
                    "zone_id VARCHAR(255) NULL," +
                    "zone_name VARCHAR(255) NULL," +
                    "ttl INT NULL," +
                    "proxied INT NULL," +
                    "record_type VARCHAR(20) NULL," +
                    "sync_interval_seconds INT NULL," +
                    "auto_update_node_ip INT NULL," +
                    "last_sync_at BIGINT NULL," +
                    "last_sync_status VARCHAR(32) NULL," +
                    "last_sync_message VARCHAR(1000) NULL" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            statement.execute("CREATE TABLE IF NOT EXISTS cloudflare_dns_binding (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY," +
                    "created_time BIGINT NULL," +
                    "updated_time BIGINT NULL," +
                    "status INT NULL," +
                    "tunnel_id BIGINT NULL," +
                    "domain VARCHAR(255) NULL," +
                    "node_ids LONGTEXT NULL," +
                    "use_tunnel_nodes INT NULL," +
                    "record_type VARCHAR(20) NULL," +
                    "last_sync_at BIGINT NULL," +
                    "last_sync_status VARCHAR(32) NULL," +
                    "last_sync_message VARCHAR(1000) NULL," +
                    "last_resolved_ips LONGTEXT NULL," +
                    "KEY idx_cloudflare_dns_binding_tunnel_id (tunnel_id)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private void seedCloudflareSetting(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT IGNORE INTO cloudflare_dns_setting " +
                    "(id, created_time, updated_time, status, enabled, ttl, proxied, record_type, sync_interval_seconds, auto_update_node_ip) VALUES " +
                    "(1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 1, 0, 1, 0, 'AUTO', 120, 1)");
        }
    }

    private interface MigrationStep {
        void run() throws Exception;
    }
}
