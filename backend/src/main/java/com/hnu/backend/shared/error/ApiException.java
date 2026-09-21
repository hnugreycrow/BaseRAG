package com.hnu.backend.shared.error;

/** 携带稳定业务错误码和 HTTP 状态的 API 异常。 */
public class ApiException extends RuntimeException {
  private final ErrorCode errorCode;

  /**
   * 创建 API 异常。
   *
   * @param code 供客户端识别的稳定错误码
   * @param message 面向用户的错误信息
   * @param status 对应的 HTTP 状态
   */
  public ApiException(ErrorCode errorCode) {
    this(errorCode, errorCode.defaultMessage(), null);
  }

  public ApiException(ErrorCode errorCode, String message) {
    this(errorCode, message, null);
  }

  public ApiException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  /**
   * 返回业务错误码。
   *
   * @return 稳定业务错误码
   */
  public String code() {
    return errorCode.code();
  }

  /**
   * 返回响应应使用的 HTTP 状态。
   *
   * @return HTTP 状态
   */
  public org.springframework.http.HttpStatus status() {
    return errorCode.status();
  }

  /** 返回类型安全的错误码。 */
  public ErrorCode errorCode() {
    return errorCode;
  }

  /**
   * 创建请求参数错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 400 异常
   */
  public static ApiException bad(ErrorCode code, String message) {
    return new ApiException(code, message);
  }

  /**
   * 创建上游服务错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 502 异常
   */
  public static ApiException upstream(ErrorCode code, String message) {
    return new ApiException(code, message);
  }

  /** 创建保留内部根因的上游服务错误。 */
  public static ApiException upstream(ErrorCode code, String message, Throwable cause) {
    return new ApiException(code, message, cause);
  }

  /**
   * 创建资源状态冲突错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 409 异常
   */
  public static ApiException conflict(ErrorCode code, String message) {
    return new ApiException(code, message);
  }

  /**
   * 创建资源不存在错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return HTTP 404 异常
   */
  public static ApiException notFound(ErrorCode code, String message) {
    return new ApiException(code, message);
  }

  /**
   * 创建生成任务已取消的标准异常。
   *
   * @return 生成取消异常
   */
  public static ApiException cancelled() {
    return new ApiException(ErrorCode.GENERATION_CANCELLED);
  }
}
