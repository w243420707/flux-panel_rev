package com.admin.controller;

import com.admin.common.lang.R;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Prevent the application's generic exception handler from echoing signed SDK/proxy requests. */
@Order(-10)
@RestControllerAdvice(assignableTypes = {AwsEc2Controller.class, AwsAccountController.class,
        AwsAccountResourcesController.class, AwsShareController.class})
public class AwsExceptionHandler {
    @ExceptionHandler(com.admin.common.exception.UnauthorizedException.class)
    public R unauthorized() { return R.err(401, "需要管理员权限"); }
    @ExceptionHandler(Exception.class)
    public R safeError() { return R.err("AWS 操作失败，请检查配置、账号权限和资源状态"); }
}
