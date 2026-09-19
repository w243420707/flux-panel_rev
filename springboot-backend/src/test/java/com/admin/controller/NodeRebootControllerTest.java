package com.admin.controller;

import com.admin.common.aop.RoleAspect;
import com.admin.common.exception.GlobalExceptionHandler;
import com.admin.common.interceptor.JwtInterceptor;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.service.NodeRebootService;
import org.junit.jupiter.api.AfterAll;
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

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeRebootControllerTest {
    private static Object previousJwtKey;
    private NodeRebootService service;
    private MockMvc mvc;

    @BeforeAll
    static void useAnIsolatedTestSigningKey() {
        previousJwtKey = ReflectionTestUtils.getField(JwtUtil.class, "SECRET_KEY");
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", "node-reboot-tests-only-not-a-real-key");
    }

    @AfterAll
    static void restoreSigningKey() {
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", previousJwtKey);
    }

    @BeforeEach
    void setUp() {
        service = mock(NodeRebootService.class);
        when(service.reboot(anyLong())).thenReturn(R.ok());
        when(service.saveSchedule(anyLong(), anyInt())).thenReturn(R.ok());
        NodeController controller = new NodeController();
        ReflectionTestUtils.setField(controller, "nodeRebootService", service);
        AspectJProxyFactory proxy = new AspectJProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAspect(new RoleAspect());
        NodeController securedController = proxy.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(securedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new JwtInterceptor())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"reboot", "reboot-schedule"})
    void ordinaryUsersCannotInvokeEitherRebootEndpoint(String action) throws Exception {
        mvc.perform(post("/api/v1/node/" + action)
                        .header("Authorization", token(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":7,\"intervalHours\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(service);
    }

    @Test
    void administratorCanRebootTheSelectedNode() throws Exception {
        mvc.perform(post("/api/v1/node/reboot")
                        .header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(service).reboot(7L);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 720})
    void administratorCanSaveIntegerHoursIncludingTheBounds(int hours) throws Exception {
        mvc.perform(post("/api/v1/node/reboot-schedule")
                        .header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":7,\"intervalHours\":" + hours + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));

        verify(service).saveSchedule(7L, hours);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "721", "1.5", "0.001", "\"abc\"", "999999999999999999999"})
    void invalidOrFractionalHoursNeverReachTheScheduler(String value) throws Exception {
        mvc.perform(post("/api/v1/node/reboot-schedule")
                        .header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":7,\"intervalHours\":" + value + "}"))
                .andExpect(jsonPath("$.code").value(not(0)));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":null}", "{\"id\":0}", "{\"id\":-1}",
            "{\"id\":7.5}", "{\"id\":9223372036854775808}"})
    void missingOrNonpositiveNodeIdIsRejected(String body) throws Exception {
        mvc.perform(post("/api/v1/node/reboot")
                        .header("Authorization", token(0))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(not(0)));

        verifyNoInteractions(service);
    }

    @Test
    void unauthenticatedRequestsNeverReachTheService() throws Exception {
        mvc.perform(post("/api/v1/node/reboot")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":7}"))
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    private static String token(int role) {
        User user = new User();
        user.setId(1L);
        user.setUser("reboot-test");
        user.setRoleId(role);
        return JwtUtil.generateToken(user);
    }
}
