package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseMigrationRebootTest {
    @Test
    void existingNodeTableGetsDisabledDefaultsAndANullDeadlineOnlyOnce() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement metadata = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        Statement ddl = mock(Statement.class);
        when(connection.prepareStatement(anyString())).thenReturn(metadata);
        when(metadata.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(0);
        when(connection.createStatement()).thenReturn(ddl);
        DatabaseMigrationConfig migration = new DatabaseMigrationConfig();

        ReflectionTestUtils.invokeMethod(migration, "addNodeRebootSettings", connection);
        when(result.getInt(1)).thenReturn(1);
        ReflectionTestUtils.invokeMethod(migration, "addNodeRebootSettings", connection);

        verify(ddl, times(1)).execute("ALTER TABLE node ADD COLUMN reboot_interval_hours INT NOT NULL DEFAULT 0 AFTER version");
        verify(ddl, times(1)).execute("ALTER TABLE node ADD COLUMN reboot_next_at BIGINT NULL AFTER reboot_interval_hours");
        verify(ddl, times(1)).execute("ALTER TABLE node ADD INDEX idx_node_reboot_next_at (reboot_next_at)");
        verify(connection, times(3)).createStatement();
        verify(ddl, never()).executeUpdate(anyString());
    }

    @Test
    void aFullyMigratedDatabaseDoesNotRewriteSchedulesOrSchema() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement metadata = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(metadata);
        when(metadata.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);

        ReflectionTestUtils.invokeMethod(new DatabaseMigrationConfig(), "addNodeRebootSettings", connection);

        verify(connection, never()).createStatement();
        verify(metadata).setString(2, "reboot_interval_hours");
        verify(metadata).setString(2, "reboot_next_at");
        verify(metadata).setString(2, "idx_node_reboot_next_at");
    }
}
