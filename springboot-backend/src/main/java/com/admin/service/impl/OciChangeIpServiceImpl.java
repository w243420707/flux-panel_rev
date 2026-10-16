package com.admin.service.impl;

import com.admin.common.utils.WebSocketServer;
import com.admin.entity.Node;
import com.admin.mapper.NodeMapper;
import com.admin.service.OciChangeIpService;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class OciChangeIpServiceImpl implements OciChangeIpService {
    private static final String RESULT_PENDING = "PENDING";
    private static final String RESULT_SUCCEEDED = "SUCCEEDED";
    private static final String RESULT_FAILED = "FAILED";

    @Resource
    private NodeMapper nodeMapper;

    @Resource
    private OciAccountServiceImpl accountService;

    @Resource
    private OciCloudService ociCloudService;

    @Resource
    private AwsEc2ServiceImpl awsEc2Service;

    @Resource
    private com.admin.service.NodeService nodeService;

    @Resource(name = "ociChangeIpExecutor")
    private Executor executor;

    @Resource(name = "awsNodeChangeIpExecutor")
    private Executor awsNodeChangeIpExecutor;

    @Override
    public void requestAfterUnreachableReport(Long nodeId) {
        requestAfterUnreachableReport(nodeId, null, null);
    }

    @Override
    public void requestAfterUnreachableReport(Long nodeId, String reportedHost, Long checkedAt) {
        if (nodeId == null) return;
        Node current = nodeMapper.selectById(nodeId);
        if (current == null || !matchesCurrentProbeAddress(current, reportedHost)) return;
        long attemptAt = System.currentTimeMillis();
        if (nodeMapper.reserveChangeIpAttempt(nodeId, attemptAt) != 1) return;
        broadcast(nodeId, attemptAt, RESULT_PENDING);
        try {
            executor.execute(() -> performChange(nodeId, attemptAt));
        } catch (RuntimeException rejected) {
            finish(nodeId, attemptAt, RESULT_FAILED);
            log.warn("Cloud change IP task rejected, nodeId={}", nodeId);
        }
    }

    private void performChange(Long nodeId, long attemptAt) {
        String result = RESULT_FAILED;
        boolean deferred = false;
        try {
            Node node = nodeMapper.selectById(nodeId);
            if (node == null || !java.util.Objects.equals(node.getChangeIpLastAttemptAt(), attemptAt)) {
                return;
            }
            if ("AWS".equalsIgnoreCase(node.getCloudProvider())) {
                if (node.getAwsAccountId() == null || node.getAwsInstanceId() == null || node.getAwsRegion() == null) return;
                awsNodeChangeIpExecutor.execute(() -> performAwsChange(nodeId, attemptAt,
                        node.getAwsAccountId(), node.getAwsInstanceId(), node.getAwsRegion()));
                deferred = true;
            } else if ("OCI".equalsIgnoreCase(node.getCloudProvider())
                    || (node.getCloudProvider() == null && Integer.valueOf(1).equals(node.getOracleNode()))) {
                if (node.getOciAccountId() == null || node.getOciInstanceOcid() == null) return;
                var account = accountService.requireAccount(node.getOciAccountId());
                String privateKey = accountService.decryptPrivateKey(account);
                String newIp = ociCloudService.changePublicIp(account, privateKey, node.getOciInstanceOcid());
                if (!refreshNodeAddress(nodeId, attemptAt, newIp)) {
                    throw new IllegalStateException("OCI 已换出新 IP，但节点地址回写失败");
                }
            } else {
                return;
            }
            result = RESULT_SUCCEEDED;
        } catch (Exception e) {
            log.warn("Cloud change IP failed, nodeId={}, errorType={}", nodeId, e.getClass().getSimpleName());
        } finally {
            if (!deferred) finish(nodeId, attemptAt, result);
        }
    }

    private void performAwsChange(Long nodeId, long attemptAt, Long accountId, String instanceId, String region) {
        String result = RESULT_FAILED;
        try {
            var changed = awsEc2Service.changeIpForNode(accountId, instanceId, region);
            String newIp = changed == null || changed.get("publicIp") == null
                    ? null : String.valueOf(changed.get("publicIp")).trim();
            if (changed != null && "SUCCEEDED".equals(changed.get("status"))
                    && refreshNodeAddress(nodeId, attemptAt, newIp)) {
                result = RESULT_SUCCEEDED;
            }
        } catch (Exception e) {
            log.warn("AWS change IP failed, nodeId={}, errorType={}", nodeId, e.getClass().getSimpleName());
        } finally {
            finish(nodeId, attemptAt, result);
        }
    }

    private void finish(Long nodeId, long attemptAt, String result) {
        if (nodeMapper.finishChangeIpAttempt(nodeId, attemptAt, result) == 1) {
            broadcast(nodeId, attemptAt, result);
        }
    }

    private boolean refreshNodeAddress(Long nodeId, long attemptAt, String newIp) {
        if (newIp == null || newIp.trim().isEmpty()) return false;
        return nodeService.refreshAddressAfterChangeIp(nodeId, attemptAt, newIp.trim());
    }

    private boolean matchesCurrentProbeAddress(Node node, String reportedHost) {
        if (reportedHost == null || reportedHost.trim().isEmpty()) return true;
        String host = reportedHost.trim();
        return host.equals(node.getServerIp()) || host.equals(node.getServerIpv4())
                || host.equals(node.getServerIpv6()) || host.equals(node.getIp());
    }

    private void broadcast(Long nodeId, long attemptAt, String result) {
        JSONObject data = new JSONObject();
        data.put("changeIpLastAttemptAt", attemptAt);
        data.put("changeIpLastResult", result);
        JSONObject event = new JSONObject();
        event.put("id", nodeId);
        event.put("type", "changeIp");
        event.put("data", data);
        WebSocketServer.broadcastMessage(event.toJSONString());
    }
}
