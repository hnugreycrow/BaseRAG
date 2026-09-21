package com.hnu.backend.shared.error;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import com.hnu.backend.shared.web.ApiResponse;
import com.hnu.backend.shared.web.RequestIdFilter;
import io.lettuce.core.RedisException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  public record FieldViolation(String field, String message) {}

  private String requestId(HttpServletRequest request) {
    return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiResponse<Void>> api(ApiException e, HttpServletRequest request) {
    if (e.status().is5xxServerError()) {
      log.error(
          "requestId={} code={} status={} exceptionType={} safeStack={}",
          requestId(request),
          e.code(),
          e.status().value(),
          e.getClass().getSimpleName(),
          SafeExceptionLog.render(e));
    } else {
      log.warn(
          "requestId={} code={} status={} publicMessage={}",
          requestId(request),
          e.code(),
          e.status().value(),
          e.getMessage());
    }
    return failure(e.status(), e.code(), e.getMessage(), request);
  }

  /**
   * 将 Sa-Token 未登录、过期和被踢下线场景统一映射为 401。
   *
   * @param e Sa-Token 未登录异常
   * @param request 当前请求
   * @return 统一认证失败响应
   */
  @ExceptionHandler(NotLoginException.class)
  ResponseEntity<ApiResponse<Void>> notLoggedIn(NotLoginException e, HttpServletRequest request) {
    return failure(ErrorCode.AUTH_REQUIRED, request);
  }

  /**
   * 将 Sa-Token 角色校验失败映射为 403。
   *
   * @param e Sa-Token 角色异常
   * @param request 当前请求
   * @return 统一无权限响应
   */
  @ExceptionHandler(NotRoleException.class)
  ResponseEntity<ApiResponse<Void>> roleDenied(NotRoleException e, HttpServletRequest request) {
    return failure(ErrorCode.FORBIDDEN, request);
  }

  /**
   * Redis 不可用时显式关闭认证，避免回退到非持久会话。
   *
   * @param request 当前请求
   * @return 统一认证存储故障响应
   */
  @ExceptionHandler({
    RedisConnectionFailureException.class,
    RedisSystemException.class,
    RedisException.class
  })
  ResponseEntity<ApiResponse<Void>> redisUnavailable(
      RuntimeException error, HttpServletRequest request) {
    return redisUnavailableResponse(error, request);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ApiResponse<Void>> size(HttpServletRequest request) {
    return failure(ErrorCode.UPLOAD_REQUEST_TOO_LARGE, request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiResponse<List<FieldViolation>>> invalidBody(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    List<FieldViolation> violations =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            ApiResponse.failure(
                ErrorCode.INVALID_REQUEST.code(), "请求参数校验失败", violations, requestId(request)));
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestPartException.class,
    ServletRequestBindingException.class,
    ConstraintViolationException.class
  })
  ResponseEntity<ApiResponse<Void>> invalid(HttpServletRequest request) {
    return failure(ErrorCode.INVALID_REQUEST, request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpServletRequest request) {
    return failure(ErrorCode.METHOD_NOT_ALLOWED, request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiResponse<Void>> mediaTypeNotSupported(HttpServletRequest request) {
    return failure(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiResponse<Void>> notFound(HttpServletRequest request) {
    return failure(ErrorCode.RESOURCE_NOT_FOUND, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiResponse<Void>> unexpected(Exception e, HttpServletRequest request) {
    if (isAuthStoreFailure(e)) return redisUnavailableResponse(e, request);
    // Do not log raw provider responses, SQL values, credentials or document text.
    log.error(
        "requestId={} code={} exceptionType={} safeStack={}",
        requestId(request),
        ErrorCode.INTERNAL_ERROR.code(),
        e.getClass().getSimpleName(),
        SafeExceptionLog.render(e));
    return failure(ErrorCode.INTERNAL_ERROR, request);
  }

  /**
   * 识别被 Sa-Token 或基础设施适配层包装的 Redis 异常。
   *
   * @param error 顶层异常
   * @return 异常链中是否存在 Redis 客户端故障
   */
  private boolean isAuthStoreFailure(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof RedisConnectionFailureException
          || current instanceof RedisSystemException
          || current instanceof RedisException) return true;
      current = current.getCause();
    }
    return false;
  }

  private ResponseEntity<ApiResponse<Void>> failure(
      HttpStatusCode status, String code, String message, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(ApiResponse.failure(code, message, null, requestId(request)));
  }

  private ResponseEntity<ApiResponse<Void>> redisUnavailableResponse(
      Throwable error, HttpServletRequest request) {
    log.error(
        "requestId={} code={} exceptionType={} safeStack={}",
        requestId(request),
        ErrorCode.AUTH_STORE_UNAVAILABLE.code(),
        error.getClass().getSimpleName(),
        SafeExceptionLog.render(error));
    return failure(ErrorCode.AUTH_STORE_UNAVAILABLE, request);
  }

  private ResponseEntity<ApiResponse<Void>> failure(
      ErrorCode errorCode, HttpServletRequest request) {
    return failure(errorCode.status(), errorCode.code(), errorCode.defaultMessage(), request);
  }
}
