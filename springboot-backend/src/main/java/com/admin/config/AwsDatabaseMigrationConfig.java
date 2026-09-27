package com.admin.config;

import com.admin.common.utils.AESCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One canonical schema for fresh installs and idempotent additive upgrades of earlier AWS drafts. */
@Slf4j
@Component
public class AwsDatabaseMigrationConfig implements ApplicationRunner {
    @Resource private DataSource dataSource;
    @Value("${AWS_ENCRYPTION_KEY:}") private String encryptionSecret;
    private static final Pattern TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS `([a-z0-9_]+)`", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN = Pattern.compile("^\\s*`([a-z0-9_]+)`\\s+([^\\n]+?)(?:,)?$", Pattern.MULTILINE);
    private static final Pattern INDEX = Pattern.compile("^\\s*(UNIQUE )?KEY `([a-z0-9_]+)` (\\([^\\n]+\\)),?$", Pattern.MULTILINE);

    @Override public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            migrate(connection);
        } catch (Exception e) {
            // Never continue with a partially usable cloud database, and never echo JDBC parameters.
            log.error("AWS database migration failed, type={}", e.getClass().getSimpleName());
            throw new IllegalStateException("AWS 数据库迁移失败，请检查数据库权限和版本");
        }
    }

    public void migrate(Connection connection) throws Exception {
        String schema;
        try (var input = new ClassPathResource("aws-schema.sql").getInputStream()) { schema = StreamUtils.copyToString(input, StandardCharsets.UTF_8); }
        for (String definition : schema.split(";")) {
            if (definition.isBlank()) continue;
            Matcher tableMatch = TABLE.matcher(definition);
            if (!tableMatch.find()) throw new IllegalStateException("AWS schema definition invalid");
            String table = tableMatch.group(1);
            try (Statement statement = connection.createStatement()) { statement.execute(definition); }
            Set<String> existing = columnNames(connection, table);
            Matcher columns = COLUMN.matcher(definition);
            while (columns.find()) {
                if (!existing.contains(columns.group(1))) {
                    String type = columns.group(2).replaceFirst(",$", "");
                    try (Statement statement = connection.createStatement()) { statement.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + columns.group(1) + "` " + type); }
                }
            }
            Set<String> indices = indexNames(connection, table);
            Matcher index = INDEX.matcher(definition);
            while (index.find()) {
                if (!indices.contains(index.group(2))) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE " + (index.group(1) == null ? "" : "UNIQUE ") + "INDEX `" + index.group(2) + "` ON `" + table + "` " + index.group(3));
                    }
                }
            }
        }
        normalizeLegacyData(connection);
    }

    private static Set<String> columnNames(Connection connection, String table) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM `" + table + "` WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) result.add(metadata.getColumnName(i).toLowerCase(Locale.ROOT));
        }
        return result;
    }
    private static Set<String> indexNames(Connection connection, String table) throws SQLException {
        Set<String> result = new HashSet<>();
        for (String spelling : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, spelling, false, false)) {
                while (rows.next()) { String name = rows.getString("INDEX_NAME"); if (name != null) result.add(name.toLowerCase(Locale.ROOT)); }
            }
        }
        return result;
    }
    private void normalizeLegacyData(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE aws_account SET status=0 WHERE status IS NULL");
            statement.executeUpdate("UPDATE aws_failover_config SET enabled=0 WHERE node_ids_json IS NULL OR template_id IS NULL OR backup_group_id IS NULL");
            statement.executeUpdate("UPDATE aws_failover_config SET cron='0 */10 * * * *' WHERE cron='*/10 * * * *'");
            statement.executeUpdate("UPDATE aws_failover_config SET required_vcpu=2 WHERE required_vcpu IS NULL");
            statement.executeUpdate("UPDATE aws_failover_config SET max_instances=1 WHERE max_instances < 1");
        }
        // Reuse historical text group names without losing account membership.
        try (Statement statement = connection.createStatement(); ResultSet groups = statement.executeQuery("SELECT DISTINCT group_name FROM aws_account WHERE group_id IS NULL AND group_name IS NOT NULL AND group_name <> ''")) {
            List<String> names = new ArrayList<>(); while (groups.next()) names.add(groups.getString(1));
            for (String name : names) {
                Long groupId = null;
                try (PreparedStatement query = connection.prepareStatement("SELECT id FROM aws_account_group WHERE name=?")) { query.setString(1, name); try (ResultSet rows = query.executeQuery()) { if (rows.next()) groupId = rows.getLong(1); } }
                if (groupId == null) {
                    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO aws_account_group(name,created_time,status) VALUES(?,?,0)", Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, name); insert.setLong(2, System.currentTimeMillis()); insert.executeUpdate(); try (ResultSet keys = insert.getGeneratedKeys()) { if (keys.next()) groupId = keys.getLong(1); }
                    }
                }
                if (groupId != null) try (PreparedStatement update = connection.prepareStatement("UPDATE aws_account SET group_id=? WHERE group_id IS NULL AND group_name=?")) { update.setLong(1, groupId); update.setString(2, name); update.executeUpdate(); }
            }
        }
        if (!StringUtils.hasText(encryptionSecret)) return;
        AESCrypto crypto = EncryptionConfig.getOrCreateCrypto(encryptionSecret);
        if (crypto == null) throw new SQLException("AWS encryption initialization failed");
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT id,access_key_id FROM aws_account WHERE (access_key_encrypted IS NULL OR access_key_encrypted='')")) {
            Map<Long, String> legacy = new LinkedHashMap<>(); while (rows.next()) { String key = rows.getString(2); if (key != null && !key.contains("*") && !key.isBlank()) legacy.put(rows.getLong(1), key); }
            for (var account : legacy.entrySet()) try (PreparedStatement update = connection.prepareStatement("UPDATE aws_account SET access_key_encrypted=?,access_key_id=? WHERE id=? AND (access_key_encrypted IS NULL OR access_key_encrypted='')")) {
                String key = account.getValue(); update.setString(1, crypto.encrypt(key)); update.setString(2, "****" + key.substring(Math.max(0, key.length() - 4))); update.setLong(3, account.getKey()); update.executeUpdate();
            }
        }
    }
}
