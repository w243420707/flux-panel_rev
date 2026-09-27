package com.admin.service.impl;

import com.admin.entity.AwsOperation;
import com.admin.mapper.AwsOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwsOperationServiceTest {
    private AwsOperationService service;
    private AwsOperationMapper mapper;
    private Executor executor;
    private Map<String, AwsOperation> store;

    @BeforeEach
    void setup() {
        service = new AwsOperationService(); mapper = mock(AwsOperationMapper.class); executor = mock(Executor.class);
        ReflectionTestUtils.setField(service, "mapper", mapper); ReflectionTestUtils.setField(service, "executor", executor);
        store = new HashMap<>();
        when(mapper.selectById(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        when(mapper.insert(any(AwsOperation.class))).thenAnswer(call -> { AwsOperation operation = call.getArgument(0); store.put(operation.getId(), operation); return 1; });
        when(mapper.finish(anyString(), anyString(), anyString(), nullable(String.class), anyLong())).thenAnswer(call -> {
            AwsOperation operation = store.get(call.getArgument(0)); operation.setStatus(call.getArgument(1)); operation.setMessage(call.getArgument(2));
            operation.setResultJson(call.getArgument(3)); operation.setActiveTarget(null); return 1;
        });
    }

    @Test
    void sameIdOnlyQueuesOnceAndDifferentTargetIsRejected() throws Exception {
        Callable<Map<String, Object>> task = mock(Callable.class);
        service.submit("request-1", 1L, "us-east-1", "i-1", "stop", task);
        service.submit("request-1", 1L, "us-east-1", "i-1", "stop", task);
        verify(executor, times(1)).execute(any()); verify(task, never()).call();
        assertThrows(IllegalArgumentException.class, () -> service.submit("request-1", 2L, "us-east-1", "i-2", "stop", task));
    }

    @Test
    void queueRejectionRecordsFailureWithoutRunningCloudTask() throws Exception {
        doThrow(new RejectedExecutionException("private detail")).when(executor).execute(any());
        Callable<Map<String, Object>> task = mock(Callable.class);
        Map<String, Object> result = service.submit("request-1", 1L, "us-east-1", "i-1", "stop", task);
        assertEquals("FAILED", result.get("status")); assertFalse(result.toString().contains("private detail"));
        verify(task, never()).call();
    }

    @Test
    void instanceBusyDoesNotQueueASecondOperation() {
        when(mapper.insert(any(AwsOperation.class))).thenThrow(new DuplicateKeyException("active target"));
        assertThrows(IllegalStateException.class, () -> service.submit("request-2", 1L, "us-east-1", "i-1", "stop", Map::of));
        verify(executor, never()).execute(any());
    }

    @Test
    void failedAwsTaskDoesNotPersistSdkSecret() {
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any());
        Map<String, Object> result = service.submit("request-1", 1L, "us-east-1", "i-1", "stop", () -> {
            throw new IllegalStateException("X-Amz-Security-Token=private-detail");
        });
        assertEquals("FAILED", result.get("status")); assertFalse(result.toString().contains("private-detail")); assertNull(result.get("result"));
    }

    @Test
    void sameTargetDifferentPayloadIsNotAReplay() {
        service.submit("request-1", 1L, "us-east-1", "i-1", "modify", Map.of("rootVolumeSize", 30), Map::of);
        assertThrows(IllegalArgumentException.class, () -> service.submit("request-1", 1L, "us-east-1", "i-1", "modify",
                Map.of("rootVolumeSize", 300), Map::of));
        verify(executor, times(1)).execute(any());
    }

    @Test
    void duplicateInsertRaceRevalidatesTargetBeforeReturningResult() {
        when(mapper.insert(any(AwsOperation.class))).thenAnswer(call -> {
            AwsOperation other = new AwsOperation(); other.setId("request-1"); other.setAccountId(99L);
            other.setRegion("us-east-1"); other.setInstanceId("i-private"); other.setAction("stop"); other.setStatus("PENDING");
            store.put("request-1", other); throw new DuplicateKeyException("raced");
        });
        assertThrows(IllegalArgumentException.class,
                () -> service.submit("request-1", 1L, "us-east-1", "i-1", "stop", Map::of));
        verify(executor, never()).execute(any());
    }
}
