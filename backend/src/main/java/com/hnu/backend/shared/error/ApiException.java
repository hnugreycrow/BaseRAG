package com.hnu.backend.shared.error;

import org.springframework.http.HttpStatus;

/** 携带稳定业务错误码和 HTTP 状态的 API 异常。 */
public class ApiException extends RuntimeException {
  private final String code;
  private final HttpStatus status;

  /**
   * 创建 API 异常。
   *
   * @param code 供客户端识别的稳定错误码
   * @param message 面向用户的错误信息
   * @param status 对应的 HTTP 状态
   */
  public ApiException(String code, String message, HttpStatus status) {
    super(message);
    this.code = code;
    this.status = status;
  }

  /**
   * 返回业务错误码。
   *
   * @return 稳定业务错误码
   */
  public String code() {
    return code;
  }

  /**
   * 返回响应应使用的 HTTP 状态。
   *
   * @return HTTP 状态
   */
  public HttpStatus status() {
    return status;
  }

  /**
   * 创建请求参数错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 400 异常
   */
  public static ApiException bad(String code, String message) {
    return new ApiException(code, message, HttpStatus.BAD_REQUEST);
  }

  /**
   * 创建上游服务错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 502 异常
   */
  public static ApiException upstream(String code, String message) {
    return new ApiException(code, message, HttpStatus.BAD_GATEWAY);
  }

  /**
   * 创建资源状态冲突错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 409 异常
   */
  public static ApiException conflict(String code, String message) {
    return new ApiException(code, message, HttpStatus.CONFLICT);
  }

  /**
   * 创建资源不存在错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 404 异常
   */
  public static ApiException notFound(String code, String message) {
    return new ApiException(code, message, HttpStatus.NOT_FOUND);
  }

  /**
   * 创建生成任务已取消的标准异常。
   *
   * @return 生成取消异常
   */
  public static ApiException cancelled() {
    return conflict("GENERATION_CANCELLED", "生成已停止");
  }
}
