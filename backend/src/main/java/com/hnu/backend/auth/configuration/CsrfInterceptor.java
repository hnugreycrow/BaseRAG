package com.hnu.backend.auth.configuration;

import com.hnu.backend.auth.service.CsrfTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 对有副作用的 API 请求执行双重提交之外的 Session nonce 校验。 */
final class CsrfInterceptor implements HandlerInterceptor {
  private final CsrfTokenService csrfTokenService;

  /**
   * 创建 CSRF 拦截器。
   *
   * @param csrfTokenService nonce 服务
   */
  CsrfInterceptor(CsrfTokenService csrfTokenService) {
    this.csrfTokenService = csrfTokenService;
  }

  /**
   * 在 Controller 执行前校验非安全方法的 CSRF Header。
   *
   * @param request HTTP 请求
   * @param response HTTP 响应
   * @param handler 目标处理器
   * @return 始终为 {@code true}
   */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    String method = request.getMethod();
    if (!("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method))) {
      csrfTokenService.requireValid(request.getHeader(CsrfTokenService.HEADER));
    }
    return true;
  }
}
