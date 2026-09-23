package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DatabaseMigrationOciTest {
    @Test
    void addsOciBindingColumnsAndAccountStorageAndNormalizesLegacyIntervals() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement metadata = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        Statement ddl = mock(Statement.class);
        when(connection.prepareStatement(anyString())).thenReturn(metadata);
        when(metadata.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(0);
        when(connection.createStatement()).thenReturn(ddl);

        ReflectionTestUtils.invokeMethod(new DatabaseMigrationConfig(), "addOciStorage", connection);

        verify(ddl).execute("CREATE TABLE IF NOT EXISTS oci_account ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "name VARCHAR(120) NOT NULL,"
                + "user_ocid VARCHAR(255) NOT NULL,"
                + "tenancy_ocid VARCHAR(255) NOT NULL,"
                + "fingerprint VARCHAR(64) NOT NULL,"
                + "region VARCHAR(80) NOT NULL,"
                + "private_key_encrypted LONGTEXT NOT NULL,"
                + "created_time BIGINT NULL, updated_time BIGINT NULL, status INT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        verify(ddl).executeUpdate("UPDATE node SET change_ip_min_interval_minutes = 3 "
                + "WHERE change_ip_min_interval_minutes IS NOT NULL AND change_ip_min_interval_minutes < 3");
        verify(ddl).execute("ALTER TABLE node ADD COLUMN change_ip_last_result VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER change_ip_last_attempt_at");
    }
}
