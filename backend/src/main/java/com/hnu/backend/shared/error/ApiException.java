package com.hnu.backend.shared.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final String code;
  private final HttpStatus status;

  public ApiException(String code, String message, HttpStatus status) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public static ApiException bad(String code, String message) {
    return new ApiException(code, message, HttpStatus.BAD_REQUEST);
  }

  public static ApiException upstream(String code, String message) {
    return new ApiException(code, message, HttpStatus.BAD_GATEWAY);
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(code, message, HttpStatus.CONFLICT);
  }

  public static ApiException notFound(String code, String message) {
    return new ApiException(code, message, HttpStatus.NOT_FOUND);
  }

  public static ApiException cancelled() {
    return conflict("GENERATION_CANCELLED", "生成已停止");
  }
}
