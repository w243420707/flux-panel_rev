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
            ensureColumn(connection, "tunnel", "in_node_ids", "ALTER TABLE tunnel ADD COLUMN in_node_ids LONGTEXT NULL AFTER in_node_id");
            ensureColumn(connection, "tunnel", "out_node_ids", "ALTER TABLE tunnel ADD COLUMN out_node_ids LONGTEXT NULL AFTER out_node_id");
            widenIpColumns(connection);
            backfillNodeArrays(connection);
        } catch (Exception e) {
            log.warn("Database migration check failed: {}", e.getMessage());
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
}
