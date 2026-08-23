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
            runStep("add node dual-stack runtime ip columns", () -> addNodeRuntimeIpColumns(connection));
            runStep("add node wall monitor columns", () -> addNodeWallMonitorColumns(connection));
            runStep("relax node ip columns", () -> relaxNodeIpColumns(connection));
            runStep("widen tunnel ip columns", () -> widenIpColumns(connection));
            runStep("create cloudflare dns tables", () -> createCloudflareTables(connection));
            runStep("add cloudflare dns smart pool columns", () -> addCloudflareSmartPoolColumns(connection));
            runStep("widen cloudflare dns domain storage", () -> widenCloudflareDnsDomainStorage(connection));
            runStep("seed cloudflare dns setting", () -> seedCloudflareSetting(connection));
            runStep("normalize cloudflare dns ttl", () -> normalizeCloudflareDnsTtl(connection));
            runStep("backfill cloudflare auto node ip setting", () -> backfillCloudflareAutoNodeIpSetting(connection));
            runStep("add query indexes", () -> addQueryIndexes(connection));
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

    private void addQueryIndexes(Connection connection) throws Exception {
        ensureIndex(connection, "forward", "idx_forward_user_created",
                "ALTER TABLE `forward` ADD INDEX `idx_forward_user_created` (`user_id`, `created_time`)");
        ensureIndex(connection, "forward", "idx_forward_user_tunnel_status",
                "ALTER TABLE `forward` ADD INDEX `idx_forward_user_tunnel_status` (`user_id`, `tunnel_id`, `status`)");
        ensureIndex(connection, "forward", "idx_forward_tunnel",
                "ALTER TABLE `forward` ADD INDEX `idx_forward_tunnel` (`tunnel_id`)");
        ensureIndex(connection, "forward", "idx_forward_status",
                "ALTER TABLE `forward` ADD INDEX `idx_forward_status` (`status`)");

        ensureIndex(connection, "statistics_flow", "idx_statistics_flow_user_id_id",
                "ALTER TABLE `statistics_flow` ADD INDEX `idx_statistics_flow_user_id_id` (`user_id`, `id`)");
        ensureIndex(connection, "statistics_flow", "idx_statistics_flow_created_time",
                "ALTER TABLE `statistics_flow` ADD INDEX `idx_statistics_flow_created_time` (`created_time`)");

        ensureIndex(connection, "user_tunnel", "idx_user_tunnel_user_tunnel",
                "ALTER TABLE `user_tunnel` ADD INDEX `idx_user_tunnel_user_tunnel` (`user_id`, `tunnel_id`)");
        ensureIndex(connection, "user_tunnel", "idx_user_tunnel_status_exp",
                "ALTER TABLE `user_tunnel` ADD INDEX `idx_user_tunnel_status_exp` (`status`, `exp_time`)");
        ensureIndex(connection, "user_tunnel", "idx_user_tunnel_flow_reset",
                "ALTER TABLE `user_tunnel` ADD INDEX `idx_user_tunnel_flow_reset` (`flow_reset_time`)");

        ensureIndex(connection, "user", "idx_user_status_exp",
                "ALTER TABLE `user` ADD INDEX `idx_user_status_exp` (`status`, `exp_time`)");
        ensureIndex(connection, "user", "idx_user_flow_reset",
                "ALTER TABLE `user` ADD INDEX `idx_user_flow_reset` (`flow_reset_time`)");
        ensureIndex(connection, "user", "idx_user_role_status",
                "ALTER TABLE `user` ADD INDEX `idx_user_role_status` (`role_id`, `status`)");

        ensureIndex(connection, "tunnel", "idx_tunnel_in_node_id",
                "ALTER TABLE `tunnel` ADD INDEX `idx_tunnel_in_node_id` (`in_node_id`)");
        ensureIndex(connection, "tunnel", "idx_tunnel_out_node_id",
                "ALTER TABLE `tunnel` ADD INDEX `idx_tunnel_out_node_id` (`out_node_id`)");
        ensureIndex(connection, "tunnel", "idx_tunnel_status",
                "ALTER TABLE `tunnel` ADD INDEX `idx_tunnel_status` (`status`)");

        ensureIndex(connection, "speed_limit", "idx_speed_limit_tunnel_id",
                "ALTER TABLE `speed_limit` ADD INDEX `idx_speed_limit_tunnel_id` (`tunnel_id`)");
        ensureIndex(connection, "node", "idx_node_secret",
                "ALTER TABLE `node` ADD INDEX `idx_node_secret` (`secret`)");
        ensureIndex(connection, "node", "idx_node_status",
                "ALTER TABLE `node` ADD INDEX `idx_node_status` (`status`)");
        ensureIndex(connection, "node", "idx_node_wall_monitor_status",
                "ALTER TABLE `node` ADD INDEX `idx_node_wall_monitor_status` (`wall_monitor_status`)");
        ensureIndex(connection, "cloudflare_dns_binding", "idx_cloudflare_dns_binding_status",
                "ALTER TABLE `cloudflare_dns_binding` ADD INDEX `idx_cloudflare_dns_binding_status` (`status`)");
    }

    private void ensureIndex(Connection connection, String table, String index, String ddl) throws Exception {
        if (hasIndex(connection, table, index)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            log.info("Database index added: {}.{}", table, index);
        }
    }

    private boolean hasIndex(Connection connection, String table, String index) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
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

    private void relaxNodeIpColumns(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE node SET server_ip = '' WHERE server_ip IS NULL");
            statement.executeUpdate("UPDATE node SET server_ipv4 = '' WHERE server_ipv4 IS NULL");
            statement.executeUpdate("UPDATE node SET server_ipv6 = '' WHERE server_ipv6 IS NULL");
            statement.execute("ALTER TABLE node MODIFY COLUMN server_ip VARCHAR(100) NOT NULL DEFAULT ''");
            statement.execute("ALTER TABLE node MODIFY COLUMN server_ipv4 VARCHAR(100) NOT NULL DEFAULT ''");
            statement.execute("ALTER TABLE node MODIFY COLUMN server_ipv6 VARCHAR(100) NOT NULL DEFAULT ''");
            statement.execute("ALTER TABLE node MODIFY COLUMN ip LONGTEXT NULL");
        }
    }

    private void addNodeRuntimeIpColumns(Connection connection) throws Exception {
        ensureColumn(connection, "node", "server_ipv4", "ALTER TABLE node ADD COLUMN server_ipv4 VARCHAR(100) NOT NULL DEFAULT '' AFTER server_ip");
        ensureColumn(connection, "node", "server_ipv6", "ALTER TABLE node ADD COLUMN server_ipv6 VARCHAR(100) NOT NULL DEFAULT '' AFTER server_ipv4");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE node SET server_ipv4 = server_ip WHERE server_ipv4 = '' AND server_ip REGEXP '^[0-9]{1,3}(\\\\.[0-9]{1,3}){3}$'");
            statement.executeUpdate("UPDATE node SET server_ipv6 = server_ip WHERE server_ipv6 = '' AND server_ip LIKE '%:%'");
        }
    }

    private void addNodeWallMonitorColumns(Connection connection) throws Exception {
        ensureColumn(connection, "node", "wall_monitor_enabled",
                "ALTER TABLE node ADD COLUMN wall_monitor_enabled TINYINT NOT NULL DEFAULT 1 AFTER version");
        ensureColumn(connection, "node", "wall_monitor_status",
                "ALTER TABLE node ADD COLUMN wall_monitor_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER wall_monitor_enabled");
        ensureColumn(connection, "node", "wall_monitor_last_check_at",
                "ALTER TABLE node ADD COLUMN wall_monitor_last_check_at BIGINT NULL AFTER wall_monitor_status");
        ensureColumn(connection, "node", "wall_monitor_consecutive_failures",
                "ALTER TABLE node ADD COLUMN wall_monitor_consecutive_failures INT NOT NULL DEFAULT 0 AFTER wall_monitor_last_check_at");
        ensureColumn(connection, "node", "wall_monitor_china_success_count",
                "ALTER TABLE node ADD COLUMN wall_monitor_china_success_count INT NOT NULL DEFAULT 0 AFTER wall_monitor_consecutive_failures");
        ensureColumn(connection, "node", "wall_monitor_china_total_count",
                "ALTER TABLE node ADD COLUMN wall_monitor_china_total_count INT NOT NULL DEFAULT 0 AFTER wall_monitor_china_success_count");
        ensureColumn(connection, "node", "wall_monitor_global_success_count",
                "ALTER TABLE node ADD COLUMN wall_monitor_global_success_count INT NOT NULL DEFAULT 0 AFTER wall_monitor_china_total_count");
        ensureColumn(connection, "node", "wall_monitor_global_total_count",
                "ALTER TABLE node ADD COLUMN wall_monitor_global_total_count INT NOT NULL DEFAULT 0 AFTER wall_monitor_global_success_count");
        ensureColumn(connection, "node", "wall_monitor_latency_ms",
                "ALTER TABLE node ADD COLUMN wall_monitor_latency_ms DOUBLE NULL AFTER wall_monitor_global_total_count");
        ensureColumn(connection, "node", "wall_monitor_message",
                "ALTER TABLE node ADD COLUMN wall_monitor_message VARCHAR(1000) NULL AFTER wall_monitor_latency_ms");
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
                    "domain LONGTEXT NULL," +
                    "node_ids LONGTEXT NULL," +
                    "use_tunnel_nodes INT NULL," +
                    "record_type VARCHAR(20) NULL," +
                    "smart_pool_enabled TINYINT NOT NULL DEFAULT 0," +
                    "smart_pool_preferred_node_ids LONGTEXT NULL," +
                    "smart_pool_active_node_ids LONGTEXT NULL," +
                    "smart_pool_backup_node_ids LONGTEXT NULL," +
                    "smart_pool_last_switch_at BIGINT NULL," +
                    "smart_pool_next_rotate_at BIGINT NULL," +
                    "last_sync_at BIGINT NULL," +
                    "last_sync_status VARCHAR(32) NULL," +
                    "last_sync_message VARCHAR(1000) NULL," +
                    "last_resolved_ips LONGTEXT NULL," +
                    "KEY idx_cloudflare_dns_binding_tunnel_id (tunnel_id)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private void addCloudflareSmartPoolColumns(Connection connection) throws Exception {
        ensureColumn(connection, "cloudflare_dns_binding", "smart_pool_enabled",
                "ALTER TABLE cloudflare_dns_binding ADD COLUMN smart_pool_enabled TINYINT NOT NULL DEFAULT 0 AFTER record_type");
        ensureColumn(connection, "cloudflare_dns_binding", "smart_pool_preferred_node_ids",
                "ALTER TABLE cloudflare_dns_binding ADD COLUMN smart_pool_preferred_node_ids LONGTEXT NULL AFTER smart_pool_enabled");
        ensureColumn(connection, "cloudflare_dns_binding", "smart_pool_active_node_ids",
                "ALTER TABLE cloudflare_dns_binding ADD COLUMN smart_pool_active_node_ids LONGTEXT NULL AFTER smart_pool_preferred_node_ids");
        ensureColumn(connection, "cloudflare_dns_binding", "smart_pool_backup_node_ids",
                "ALTER TABLE cloudflare_dns_binding ADD COLUMN smart_pool_backup_node_ids LONGTEXT NULL AFTER smart_pool_active_node_ids");
        ensureColumn(connection, "cloudflare_dns_binding", "smart_pool_last_switch_at",
                "ALTER TABLE cloudflare_dns_binding ADD COLUMN smart_pool_last_switch_at BIGINT NULL AFTER smart_pool_backup_node_ids");
        ensureColumn(connection, "cloudflare_dns_binding", "smart_pool_next_rotate_at",
                "ALTER TABLE cloudflare_dns_binding ADD COLUMN smart_pool_next_rotate_at BIGINT NULL AFTER smart_pool_last_switch_at");
    }

    private void widenCloudflareDnsDomainStorage(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE cloudflare_dns_binding MODIFY COLUMN domain LONGTEXT NULL");
        }
    }

    private void seedCloudflareSetting(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT IGNORE INTO cloudflare_dns_setting " +
                    "(id, created_time, updated_time, status, enabled, ttl, proxied, record_type, sync_interval_seconds, auto_update_node_ip) VALUES " +
                    "(1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 1, 0, 60, 0, 'AUTO', 120, 1)");
        }
    }

    private void normalizeCloudflareDnsTtl(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE cloudflare_dns_setting SET ttl = 60 WHERE ttl IS NULL OR ttl < 60");
        }
    }

    private void backfillCloudflareAutoNodeIpSetting(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE cloudflare_dns_setting SET auto_update_node_ip = 1 WHERE auto_update_node_ip IS NULL");
        }
    }

    private interface MigrationStep {
        void run() throws Exception;
    }
}
