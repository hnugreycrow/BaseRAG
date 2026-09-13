package com.hnu.backend.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  public record ErrorBody(String code, String message, String requestId) {}

  private ErrorBody body(String code, String message, HttpServletRequest request) {
    return new ErrorBody(code, message, (String) request.getAttribute("requestId"));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorBody> api(ApiException e, HttpServletRequest request) {
    log.warn("requestId={} code={}", request.getAttribute("requestId"), e.code());
    return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage(), request));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorBody> size(HttpServletRequest request) {
    return ResponseEntity.status(413).body(body("FILE_TOO_LARGE", "文件不能超过 5 MiB", request));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestPartException.class
  })
  ResponseEntity<ErrorBody> invalid(HttpServletRequest request) {
    return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "请检查请求参数及文件", request));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> unexpected(Exception e, HttpServletRequest request) {
    // Do not log raw provider responses, SQL values, credentials or document text.
    log.error(
        "requestId={} exceptionType={}",
        request.getAttribute("requestId"),
        e.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(body("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", request));
  }
}
