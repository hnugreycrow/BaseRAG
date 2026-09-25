package com.hnu.backend.shared.web;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 自动包装普通 Controller 返回值；SSE 和 204 响应保持原协议。 */
@RestControllerAdvice(basePackages = "com.hnu.backend")
public class ApiResponseAdvice implements ResponseBodyAdvice<@NonNull Object> {
  @Override
  public boolean supports(
      @NonNull MethodParameter returnType,
      @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
    Class<?> type = returnType.getParameterType();
    return type != Void.TYPE && !SseEmitter.class.isAssignableFrom(type);
  }

  @Override
  public @Nullable Object beforeBodyWrite(
      @Nullable Object body,
      @NonNull MethodParameter returnType,
      @NonNull MediaType selectedContentType,
      @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
      @NonNull ServerHttpRequest request,
      @NonNull ServerHttpResponse response) {
    if (!MediaType.APPLICATION_JSON.isCompatibleWith(selectedContentType)) return body;
    if (response instanceof ServletServerHttpResponse servletResponse
        && servletResponse.getServletResponse().getStatus() == HttpStatus.NO_CONTENT.value()) {
      return body;
    }
    if (body instanceof ApiResponse<?>) return body;
    String requestId = null;
    if (request instanceof ServletServerHttpRequest servletRequest) {
      requestId =
          (String)
              servletRequest.getServletRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
    return ApiResponse.success(body, requestId);
  }
}
