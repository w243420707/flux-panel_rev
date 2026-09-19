package com.admin.controller;

import com.admin.common.aop.RoleAspect;
import com.admin.common.dto.NodeUsageConfirmDto;
import com.admin.common.dto.UsageSnapshot;
import com.admin.common.exception.GlobalExceptionHandler;
import com.admin.common.interceptor.JwtInterceptor;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.User;
import com.admin.service.NodeUsageService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeUsageControllerTest {
    private static final String CONFIRM_BODY = "{\"id\":7,\"expectedUsageId\":42,\"candidateId\":\"candidate-7\",\"decision\":\"replace\"}";
    private static Object previousJwtKey;
    private NodeUsageService service;
    private WebSocketServer socketServer;
    private MockMvc mvc;

    @BeforeAll
    static void useAnIsolatedTestSigningKey() {
        previousJwtKey = ReflectionTestUtils.getField(JwtUtil.class, "SECRET_KEY");
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", "node-usage-tests-only-not-a-real-key");
    }

    @AfterAll
    static void restoreSigningKey() {
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", previousJwtKey);
    }

    @BeforeEach
    void setUp() {
        clearCandidates();
        service = mock(NodeUsageService.class);
        socketServer = mock(WebSocketServer.class);
        NodeController controller = new NodeController();
        ReflectionTestUtils.setField(controller, "nodeUsageService", service);
        ReflectionTestUtils.setField(controller, "webSocketServer", socketServer);
        AspectJProxyFactory proxy = new AspectJProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAspect(new RoleAspect());
        NodeController secured = proxy.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(secured)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new JwtInterceptor())
                .build();
    }

    @AfterEach
    void clearCandidates() {
        candidates().clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"usage-history", "usage-confirm"})
    void ordinaryUsersCannotReadOrConfirmVpsRecords(String action) throws Exception {
        mvc.perform(post("/api/v1/node/" + action).header("Authorization", token(1))
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(service, socketServer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"usage-history", "usage-confirm"})
    void unauthenticatedRequestsCannotReachEitherService(String action) throws Exception {
        mvc.perform(post("/api/v1/node/" + action)
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service, socketServer);
    }

    @Test
    void administratorCanReadTheSelectedNodesStructuredRecords() throws Exception {
        NodeUsageService.UsageSummary summary = active();
        when(service.history(7L)).thenReturn(R.ok(new NodeUsageService.UsageHistory(
                summary.status(), summary.current(), summary.pending(), List.of(summary.current()))));

        mvc.perform(post("/api/v1/node/usage-history").header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current.id").value(43))
                .andExpect(jsonPath("$.data.records[0].uploadBytes").value(100))
                .andExpect(jsonPath("$.data.records[0].totalBytes").value(300))
                .andExpect(jsonPath("$.data.pending").value(nullValue()));

        verify(service).history(7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "null"})
    void administratorConfirmationPassesTheExpectedRecordAndActivatesOnlyTheAcceptedSession(String expected) throws Exception {
        candidate();
        when(service.confirm(any())).thenReturn(new NodeUsageService.ConfirmationResult("pending-7", 43L, active()));

        mvc.perform(post("/api/v1/node/usage-confirm").header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRM_BODY.replace("\"expectedUsageId\":42", "\"expectedUsageId\":" + expected)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current.id").value(43));

        verify(service).confirm(argThat(request -> request.getId() == 7L
                && "candidate-7".equals(request.getCandidateId()) && "replace".equals(request.getDecision())
                && ("null".equals(expected) ? request.getExpectedUsageId() == null : request.getExpectedUsageId() == 42L)));
        verify(socketServer).activateUsageCandidate(7L, "pending-7", 43L);
    }

    @Test
    void staleCandidateOrExpectedRecordDoesNotActivateAnyConnection() throws Exception {
        candidate();
        when(service.confirm(any(NodeUsageConfirmDto.class)))
                .thenThrow(new IllegalArgumentException("待确认的 VPS 或当前统计已变化，请刷新后重新确认"));

        mvc.perform(post("/api/v1/node/usage-confirm").header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(jsonPath("$.code").value(not(0)))
                .andExpect(jsonPath("$.msg").value(containsString("已变化")));

        verify(service).confirm(any(NodeUsageConfirmDto.class));
        verifyNoInteractions(socketServer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "closed", "unparsed"})
    void absentClosedOrUnparsedCandidatesCannotBeConfirmed(String state) throws Exception {
        if (!"missing".equals(state)) {
            WebSocketSession session = candidate();
            if ("closed".equals(state)) when(session.isOpen()).thenReturn(false);
            if ("unparsed".equals(state)) session.getAttributes().remove("usageSnapshot");
        }

        mvc.perform(post("/api/v1/node/usage-confirm").header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON).content(CONFIRM_BODY))
                .andExpect(jsonPath("$.code").value(not(0)))
                .andExpect(jsonPath("$.msg").value(containsString("已断开")));

        verifyNoInteractions(service, socketServer);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"candidateId\":\"candidate-7\",\"decision\":\"replace\"}",
            "{\"id\":0,\"candidateId\":\"candidate-7\",\"decision\":\"replace\"}",
            "{\"id\":7,\"expectedUsageId\":1.5,\"candidateId\":\"candidate-7\",\"decision\":\"same\"}",
            "{\"id\":7,\"expectedUsageId\":-1,\"candidateId\":\"candidate-7\",\"decision\":\"same\"}",
            "{\"id\":7,\"candidateId\":\" \",\"decision\":\"replace\"}",
            "{\"id\":7,\"candidateId\":\"candidate-7\",\"decision\":\"reset\"}"
    })
    void invalidConfirmationFieldsNeverReachTheUsageService(String body) throws Exception {
        candidate();
        mvc.perform(post("/api/v1/node/usage-confirm").header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(not(0)));

        verifyNoInteractions(service, socketServer);
    }

    @Test
    void fractionalNodeIdIsRejectedForHistory() throws Exception {
        mvc.perform(post("/api/v1/node/usage-history").header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":7.5}"))
                .andExpect(jsonPath("$.code").value(not(0)));
        verifyNoInteractions(service);
    }

    private NodeUsageService.UsageSummary active() {
        return new NodeUsageService.UsageSummary("active", new NodeUsageService.UsageRecord(43L,
                1000L, null, 100L, 200L, 300L, 60L, 61000L, "192.0.2.7"), null);
    }

    private WebSocketSession candidate() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("usageSnapshot", new UsageSnapshot("", "", "", "",
                "20000000-0000-0000-0000-000000000001", 0, 0, 0));
        when(session.getId()).thenReturn("pending-7");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        candidates().put(7L, session);
        return session;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, WebSocketSession> candidates() {
        return (Map<Long, WebSocketSession>) ReflectionTestUtils.getField(WebSocketServer.class, "usageCandidateSessions");
    }

    private static String token(int role) {
        User user = new User();
        user.setId(1L);
        user.setUser("usage-test");
        user.setRoleId(role);
        return JwtUtil.generateToken(user);
    }
}
