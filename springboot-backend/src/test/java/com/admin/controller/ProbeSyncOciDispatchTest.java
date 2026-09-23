package com.admin.controller;

import com.admin.common.dto.ProbeNodeReportDto;
import com.admin.common.dto.ProbeSyncReportDto;
import com.admin.common.lang.R;
import com.admin.entity.Node;
import com.admin.entity.ViteConfig;
import com.admin.service.NodeService;
import com.admin.service.NodeWallMonitorService;
import com.admin.service.OciChangeIpService;
import com.admin.service.ViteConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProbeSyncOciDispatchTest {
    private final ViteConfigService configs = mock(ViteConfigService.class);
    private final NodeService nodes = mock(NodeService.class);
    private final NodeWallMonitorService wallMonitor = mock(NodeWallMonitorService.class);
    private final OciChangeIpService changeIp = mock(OciChangeIpService.class);
    private ProbeSyncController controller;

    @BeforeEach
    void setUp() {
        controller = new ProbeSyncController();
        ReflectionTestUtils.setField(controller, "viteConfigService", configs);
        ReflectionTestUtils.setField(controller, "nodeService", nodes);
        ReflectionTestUtils.setField(controller, "nodeWallMonitorService", wallMonitor);
        ReflectionTestUtils.setField(controller, "ociChangeIpService", changeIp);
        ViteConfig apiKey = new ViteConfig();
        apiKey.setValue("probe-test-key");
        when(configs.getOne(any(QueryWrapper.class))).thenReturn(apiKey);
        when(wallMonitor.markNodeUnavailableByExternalProbe(anyLong(), anyString())).thenReturn(R.ok());
        when(wallMonitor.markNodeAvailableByExternalProbe(anyLong(), anyString())).thenReturn(R.ok());
    }

    @Test
    void onlyUnreachableBatchReportsRequestAnOciChange() {
        ProbeNodeReportDto unreachable = result(false);
        controller.report("probe-test-key", null, report(unreachable));
        verify(changeIp).requestAfterUnreachableReport(7L);

        clearInvocations(changeIp);
        controller.report("probe-test-key", null, report(result(true)));
        verifyNoInteractions(changeIp);
    }

    @Test
    void synchronizedProbeNodesDoNotReceiveOciCredentialsOrLegacyRemoteApiSettings() {
        Node node = new Node();
        node.setId(7L);
        node.setName("OCI node");
        node.setServerIp("203.0.113.7");
        when(nodes.list(any(QueryWrapper.class))).thenReturn(Collections.singletonList(node));

        R response = controller.listNodes("probe-test-key", null);
        Map<?, ?> data = (Map<?, ?>) response.getData();
        List<?> resultNodes = (List<?>) data.get("nodes");
        Map<?, ?> item = (Map<?, ?>) resultNodes.get(0);

        assertFalse(item.containsKey("changeIpRemoteApi"));
        assertFalse(item.containsKey("changeIpMinIntervalMinutes"));
        assertFalse(item.containsKey("ociAccountId"));
        assertFalse(item.containsKey("ociInstanceOcid"));
    }

    private static ProbeNodeReportDto result(boolean reachable) {
        ProbeNodeReportDto result = new ProbeNodeReportDto();
        result.setNodeId(7L);
        result.setReachable(reachable);
        return result;
    }

    private static ProbeSyncReportDto report(ProbeNodeReportDto result) {
        ProbeSyncReportDto report = new ProbeSyncReportDto();
        report.setResults(Collections.singletonList(result));
        return report;
    }
}
