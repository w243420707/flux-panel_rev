package com.admin.service.impl;

import com.admin.common.dto.AwsEc2CreateDto;
import com.admin.common.dto.AwsEc2ListDto;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.admin.service.AwsEc2Service;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AwsWorkflowServiceTest {
    private AwsWorkflowService service;
    private AwsFailoverConfigMapper configs;
    private AwsFailoverJobMapper jobs;
    private NodeMapper nodes;
    private AwsAccountMapper accounts;
    private AwsEc2InstanceTemplateMapper templates;
    private AwsAccountServiceImpl accountService;
    private AwsEc2ProviderService provider;
    private AwsEc2Service ec2;
    private AwsFailoverConfig config;
    private Node old;
    private List<AwsFailoverJob> inserted;

    @BeforeEach
    void setup() {
        service = new AwsWorkflowService(); configs = mock(AwsFailoverConfigMapper.class); jobs = mock(AwsFailoverJobMapper.class);
        nodes = mock(NodeMapper.class); accounts = mock(AwsAccountMapper.class); templates = mock(AwsEc2InstanceTemplateMapper.class);
        accountService = mock(AwsAccountServiceImpl.class); provider = mock(AwsEc2ProviderService.class); ec2 = mock(AwsEc2Service.class);
        ReflectionTestUtils.setField(service, "configs", configs); ReflectionTestUtils.setField(service, "jobs", jobs);
        ReflectionTestUtils.setField(service, "nodes", nodes); ReflectionTestUtils.setField(service, "accounts", accounts);
        ReflectionTestUtils.setField(service, "templates", templates); ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "provider", provider); ReflectionTestUtils.setField(service, "ec2", ec2);
        ReflectionTestUtils.setField(service, "shares", mock(AwsShareGroupMapper.class));
        ReflectionTestUtils.setField(service, "usage", mock(NodeVpsUsageMapper.class));
        ReflectionTestUtils.setField(service, "bindings", mock(AwsNodeBindingMapper.class));
        config = new AwsFailoverConfig(); config.setId(1L); config.setEnabled(1); config.setMaxInstances(1); config.setTemplateId(9L);
        config.setBackupGroupId(2L); config.setRequiredVcpu(2); config.setConditionsJson("[\"instance_missing\"]");
        config.setNodeIdsJson("[3]"); config.setTelegramEnabled(0); config.setAccountSelection("ordered");
        old = new Node(); old.setId(3L); old.setName("old-node"); old.setCloudProvider("AWS"); old.setAwsAccountId(1L);
        old.setAwsInstanceId("i-old"); old.setAwsRegion("us-east-1");
        when(configs.selectById(1L)).thenReturn(config); when(nodes.selectById(3L)).thenReturn(old);
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(0);
        inserted = new ArrayList<>(); when(jobs.insert(any(AwsFailoverJob.class))).thenAnswer(call -> { inserted.add(call.getArgument(0)); return 1; });
    }

    @Test
    void remoteErrorsNeverBecomeMissingInstanceOrCreateReplacement() {
        when(ec2.listInstances(any(AwsEc2ListDto.class))).thenThrow(new IllegalStateException("access denied"));
        service.runCycle(config);
        verify(jobs, never()).insert(any(AwsFailoverJob.class)); verify(ec2, never()).createInstance(any(AwsEc2CreateDto.class));
    }

    @Test
    void missingQuotaDataNeverCountsAsZero() {
        config.setConditionsJson("[\"vcpu_zero\"]");
        when(ec2.listInstances(any(AwsEc2ListDto.class))).thenReturn(List.of(Map.of("instanceId", "i-old", "state", "running")));
        when(accountService.quota(1L, "us-east-1")).thenReturn(Map.of());
        service.runCycle(config);
        verify(jobs, never()).insert(any(AwsFailoverJob.class));
    }

    @Test
    void interruptedCreationLocksSourceInsteadOfRetrying() {
        stubReplacement(); when(ec2.createInstance(any(AwsEc2CreateDto.class))).thenThrow(new IllegalStateException("timeout"));
        service.replace(config, old, "source-1", "instance_missing");
        assertEquals("UNKNOWN", inserted.get(0).getStatus()); assertFalse(inserted.get(0).getMessage().contains("timeout"));
        verify(nodes, never()).replaceAwsBinding(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(1);
        service.runCycle(config); verify(ec2, times(1)).createInstance(any(AwsEc2CreateDto.class));
    }

    @Test
    void duplicateSourceClaimDoesNotCreateASecondInstance() {
        when(jobs.insert(any(AwsFailoverJob.class))).thenThrow(new DuplicateKeyException("same source"));
        service.replace(config, old, "source-1", "instance_missing");
        verify(ec2, never()).createInstance(any(AwsEc2CreateDto.class));
    }

    @Test
    void reachedCapPreventsCloudChecksAndCreations() {
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(0, 1);
        service.runCycle(config);
        verify(ec2, never()).listInstances(any(AwsEc2ListDto.class)); verify(ec2, never()).createInstance(any(AwsEc2CreateDto.class));
    }

    @Test
    void bindOnlyAfterInstanceReadyAndRetainUnknownIfOriginalBindingChanged() {
        stubReplacement(); when(ec2.createInstance(any(AwsEc2CreateDto.class))).thenReturn(Map.of("instanceId", "i-new"));
        when(provider.awaitReady(any(), any(), any(), eq("us-east-1"), eq("i-new"))).thenReturn(Map.of("publicIpv4", "203.0.113.2"));
        when(nodes.replaceAwsBinding(3L, 1L, "i-old", "us-east-1", 2L, "i-new", "us-east-1", "203.0.113.2", "203.0.113.2", "")).thenReturn(0);
        service.replace(config, old, "source-1", "instance_missing");
        var order = inOrder(ec2, provider, nodes);
        order.verify(ec2).createInstance(any(AwsEc2CreateDto.class));
        order.verify(provider).awaitReady(any(), any(), any(), eq("us-east-1"), eq("i-new"));
        order.verify(nodes).replaceAwsBinding(3L, 1L, "i-old", "us-east-1", 2L, "i-new", "us-east-1", "203.0.113.2", "203.0.113.2", "");
        assertEquals("UNKNOWN", inserted.get(0).getStatus()); assertEquals("i-new", inserted.get(0).getNewInstanceId());
    }

    @Test
    void readyTimeoutDoesNotChangeBinding() {
        stubReplacement(); when(ec2.createInstance(any(AwsEc2CreateDto.class))).thenReturn(Map.of("instanceId", "i-new"));
        when(provider.awaitReady(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("not ready"));
        service.replace(config, old, "source-1", "instance_missing");
        verify(nodes, never()).replaceAwsBinding(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals("UNKNOWN", inserted.get(0).getStatus());
    }

    @Test
    void cronUsesMinimumOneMinuteAndRejectsInvalidExpression() {
        long now = 1_700_000_000_000L;
        assertTrue(AwsWorkflowService.nextRun("* * * * * *", now) >= now + 60_000L);
        assertTrue(AwsWorkflowService.nextRun("*/10 * * * *", now) >= now + 60_000L);
        assertThrows(IllegalArgumentException.class, () -> AwsWorkflowService.nextRun("not a cron", now));
    }

    @Test
    void changedConfigurationWhileQueuedCancelsBeforeCloudCreate() {
        stubReplacement();
        AwsFailoverConfig changed = new AwsFailoverConfig(); changed.setEnabled(1); changed.setTemplateId(10L);
        when(configs.selectById(1L)).thenReturn(changed);
        service.replace(config, old, "source-1", "instance_missing");
        verify(ec2, never()).createInstance(any(AwsEc2CreateDto.class)); assertEquals("FAILED", inserted.get(0).getStatus());
    }

    @Test
    void disabledConfigurationAfterCreationDoesNotSwitchBinding() {
        stubReplacement(); when(ec2.createInstance(any(AwsEc2CreateDto.class))).thenReturn(Map.of("instanceId", "i-new"));
        when(provider.awaitReady(any(), any(), any(), any(), any())).thenReturn(Map.of("publicIpv4", "203.0.113.2"));
        AwsFailoverConfig disabled = new AwsFailoverConfig(); disabled.setEnabled(0);
        when(configs.selectById(1L)).thenReturn(config, disabled);
        service.replace(config, old, "source-1", "instance_missing");
        verify(nodes, never()).replaceAwsBinding(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals("UNKNOWN", inserted.get(0).getStatus());
    }

    private void stubReplacement() {
        AwsAccount target = new AwsAccount(); target.setId(2L); target.setRegion("us-east-1");
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(target));
        AwsEc2InstanceTemplate template = new AwsEc2InstanceTemplate(); template.setId(9L); template.setRegion("us-east-1");
        when(templates.selectById(9L)).thenReturn(template); when(accountService.quota(2L, "us-east-1")).thenReturn(Map.of("available", 4));
        when(accountService.decryptAccessSecret(any())).thenReturn("secret"); when(accountService.decryptSessionToken(any())).thenReturn("token");
    }
}
