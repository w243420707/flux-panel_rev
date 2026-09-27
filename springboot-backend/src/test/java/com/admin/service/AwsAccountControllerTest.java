package com.admin.service;

import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.AwsAccountIdDto;
import com.admin.controller.AwsAccountController;
import com.admin.controller.AwsAccountResourcesController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AwsAccountControllerTest {
    @Test void everyAccountAndResourceRouteRequiresAdministrator() {
        for (Class<?> controller : java.util.List.of(AwsAccountController.class, AwsAccountResourcesController.class)) {
            for (var method : controller.getDeclaredMethods()) if (method.isAnnotationPresent(PostMapping.class)) assertTrue(method.isAnnotationPresent(RequireRole.class), method.getName());
        }
    }
    @Test void privateEditorResponseIsNotCacheableAndExceptionsAreSanitized() {
        AwsAccountService service = mock(AwsAccountService.class); when(service.getPrivateSecrets(1L)).thenReturn(Map.of("accessKeyId", "example-private-key"));
        AwsAccountController controller = new AwsAccountController(); ReflectionTestUtils.setField(controller, "service", service);
        AwsAccountIdDto dto = new AwsAccountIdDto(); dto.setId(1L); MockHttpServletResponse response = new MockHttpServletResponse();
        assertEquals(0, controller.privateSecret(dto, response).getCode()); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertFalse(controller.sanitizedError(new RuntimeException("private-token")).getMsg().contains("private-token"));
    }
}
