package com.admin.controller;

import com.admin.common.aop.RoleAspect;
import com.admin.common.dto.PanelUpdateSettingsRequest;
import com.admin.common.exception.GlobalExceptionHandler;
import com.admin.common.interceptor.JwtInterceptor;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.entity.User;
import com.admin.service.PanelUpdateService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PanelUpdateControllerTest {
    private static Object previousJwtKey;
    private PanelUpdateService service;
    private MockMvc mvc;

    @BeforeAll
    static void useIsolatedSigningKey() {
        previousJwtKey = ReflectionTestUtils.getField(JwtUtil.class, "SECRET_KEY");
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", "panel-update-tests-only-not-a-real-key");
    }

    @AfterAll
    static void restoreSigningKey() {
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", previousJwtKey);
    }

    @BeforeEach
    void setUp() {
        service = mock(PanelUpdateService.class);
        when(service.getSettings()).thenReturn(R.ok(Map.of(
                "enabled", false,
                "checkIntervalSeconds", 86400,
                "githubTokenConfigured", false,
                "githubTokenMasked", "")));
        when(service.getStatus()).thenReturn(R.ok(Map.of("currentVersion", "1.0.17")));
        when(service.saveSettings(any(PanelUpdateSettingsRequest.class))).thenReturn(R.ok());
        when(service.checkNow()).thenReturn(R.ok());

        PanelUpdateController controller = new PanelUpdateController();
        ReflectionTestUtils.setField(controller, "panelUpdateService", service);
        AspectJProxyFactory proxy = new AspectJProxyFactory(controller);
        proxy.setProxyTargetClass(true);
        proxy.addAspect(new RoleAspect());
        PanelUpdateController securedController = proxy.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(securedController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new JwtInterceptor())
                .build();
    }

    @Test
    void allEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/update/settings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/v1/update/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/api/v1/update/check"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(put("/api/v1/update/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }

    @Test
    void ordinaryUsersCannotAccessUpdateSettings() throws Exception {
        String token = token(1);
        mvc.perform(get("/api/v1/update/settings").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/v1/update/status").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/v1/update/check").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(put("/api/v1/update/settings").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }

    @Test
    void administratorCanUseAllUpdateEndpoints() throws Exception {
        String token = token(0);
        mvc.perform(get("/api/v1/update/settings").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/v1/update/status").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/v1/update/check").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/v1/update/settings").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(service).getSettings();
        verify(service).getStatus();
        verify(service).checkNow();
        verify(service).saveSettings(any(PanelUpdateSettingsRequest.class));
    }

    private static String token(int role) {
        User user = new User();
        user.setId(1L);
        user.setUser("panel-update-test");
        user.setRoleId(role);
        return JwtUtil.generateToken(user);
    }
}
