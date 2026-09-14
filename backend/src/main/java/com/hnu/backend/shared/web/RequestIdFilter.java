package com.hnu.backend.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为每个 HTTP 请求生成并传播唯一请求标识，便于跨日志追踪。 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {
  /** 请求对象中保存请求标识的属性名。 */
  public static final String REQUEST_ID_ATTRIBUTE = "requestId";

  /** 响应中返回请求标识的 HTTP 头名称。 */
  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  /** {@inheritDoc} */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    request.setAttribute(REQUEST_ID_ATTRIBUTE, id);
    response.setHeader(REQUEST_ID_HEADER, id);
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      // 线程池会复用工作线程，必须清理 MDC，避免后续请求串用当前请求标识。
      MDC.remove("requestId");
    }
  }
}
