package com.admin.service.impl;

import com.admin.common.dto.AwsManualFailoverDto;
import com.admin.entity.AwsAccount;
import com.admin.entity.AwsEc2InstanceTemplate;
import com.admin.entity.AwsFailoverConfig;
import com.admin.entity.Node;
import com.admin.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwsManualFailoverTest {
    private AwsWorkflowService service;
    private AwsFailoverConfigMapper configs;
    private AwsFailoverJobMapper jobs;
    private NodeMapper nodes;
    private AwsAccountMapper accounts;
    private AwsEc2InstanceTemplateMapper templates;
    private AwsOperationService operations;
    private Node node;

    @BeforeEach
    void setup() {
        service = new AwsWorkflowService();
        configs = mock(AwsFailoverConfigMapper.class);
        jobs = mock(AwsFailoverJobMapper.class);
        nodes = mock(NodeMapper.class);
        accounts = mock(AwsAccountMapper.class);
        templates = mock(AwsEc2InstanceTemplateMapper.class);
        operations = mock(AwsOperationService.class);
        ReflectionTestUtils.setField(service, "configs", configs);
        ReflectionTestUtils.setField(service, "jobs", jobs);
        ReflectionTestUtils.setField(service, "nodes", nodes);
        ReflectionTestUtils.setField(service, "accounts", accounts);
        ReflectionTestUtils.setField(service, "templates", templates);
        ReflectionTestUtils.setField(service, "operations", operations);
        ReflectionTestUtils.setField(service, "shares", mock(AwsShareGroupMapper.class));
        ReflectionTestUtils.setField(service, "usage", mock(NodeVpsUsageMapper.class));
        ReflectionTestUtils.setField(service, "bindings", mock(AwsNodeBindingMapper.class));
        node = new Node();
        node.setId(7L); node.setCloudProvider("AWS"); node.setAwsAccountId(11L);
        node.setAwsRegion("us-east-1"); node.setAwsInstanceId("i-source");
        when(nodes.selectById(7L)).thenReturn(node);
        AwsFailoverConfig config = new AwsFailoverConfig();
        config.setId(1L); config.setEnabled(0); config.setBackupGroupId(5L); config.setTemplateId(6L);
        when(configs.selectById(1L)).thenReturn(config);
        when(templates.selectById(6L)).thenReturn(new AwsEc2InstanceTemplate());
        when(accounts.selectCount(any())).thenReturn(1);
        when(operations.submit(anyString(), anyLong(), anyString(), anyString(), eq("failover"),
                any(AwsManualFailoverDto.class), any(Callable.class)))
                .thenReturn(Map.of("operationId", "request-1", "status", "PENDING"));
    }

    @Test
    void requiresNodeOrCompleteAwsLocator() {
        AwsManualFailoverDto dto = new AwsManualFailoverDto();
        assertThrows(IllegalArgumentException.class, () -> service.submitManualReplacement(dto));
        verifyNoInteractions(operations);
    }

    @Test
    void rejectsLocatorThatDoesNotMatchNodeBinding() {
        AwsManualFailoverDto dto = new AwsManualFailoverDto();
        dto.setNodeId(7L); dto.setAccountId(99L); dto.setRegion("us-east-1"); dto.setInstanceId("i-other");
        assertThrows(IllegalArgumentException.class, () -> service.submitManualReplacement(dto));
        verifyNoInteractions(operations);
    }

    @Test
    void queuesWithRequestIdAndUsesCurrentSourceBinding() {
        AwsManualFailoverDto dto = new AwsManualFailoverDto();
        dto.setNodeId(7L); dto.setRequestId("request-1");
        Map<String, Object> result = service.submitManualReplacement(dto);
        assertEquals("request-1", result.get("operationId"));
        verify(operations).submit(eq("request-1"), eq(11L), eq("us-east-1"), eq("i-source"), eq("failover"),
                same(dto), any(Callable.class));
    }

    @Test
    void automaticSchedulerDoesNotRetryARecentManualUnknownJob() {
        AwsFailoverConfig config = new AwsFailoverConfig();
        config.setId(1L); config.setEnabled(1); config.setBackupGroupId(5L); config.setTemplateId(6L);
        config.setNodeIdsJson("[7]"); config.setConditionsJson("[\"instance_missing\"]"); config.setMaxInstances(1);
        config.setRequiredVcpu(2); config.setAccountSelection("ordered");
        when(configs.selectById(1L)).thenReturn(config);
        when(jobs.selectCount(any())).thenReturn(1);
        service.runCycle(config);
        verifyNoInteractions(operations);
        verifyNoInteractions(templates);
    }
}
