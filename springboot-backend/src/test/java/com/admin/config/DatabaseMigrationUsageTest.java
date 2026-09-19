package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DatabaseMigrationUsageTest {
    @Test
    void upgradingAndRepeatingMigrationDoesNotClearUsageOrAddThePointerTwice() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement metadata = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        Statement ddl = mock(Statement.class);
        when(connection.prepareStatement(anyString())).thenReturn(metadata);
        when(metadata.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(0, 1);
        when(connection.createStatement()).thenReturn(ddl);
        DatabaseMigrationConfig migration = new DatabaseMigrationConfig();

        ReflectionTestUtils.invokeMethod(migration, "addNodeUsageStorage", connection);
        ReflectionTestUtils.invokeMethod(migration, "addNodeUsageStorage", connection);

        verify(ddl).execute("ALTER TABLE node ADD COLUMN current_usage_id BIGINT NULL AFTER version");
        verify(ddl, times(2)).execute(NodeUsageSchema.CREATE_TABLE);
        verify(ddl, never()).executeUpdate(anyString());
    }
}
