package com.hnu.backend.common.exception;

/** 携带稳定业务错误码和 HTTP 状态的 API 异常。 */
public class ApiException extends RuntimeException {
  private final ErrorCode errorCode;

  /**
   * 使用错误码的默认消息创建异常。
   *
   * @param errorCode 决定响应状态和稳定业务码的错误类型
   */
  public ApiException(ErrorCode errorCode) {
    this(errorCode, errorCode.defaultMessage(), null);
  }

  /**
   * 使用调用方提供的消息创建异常。
   *
   * @param errorCode 决定响应状态和稳定业务码的错误类型
   * @param message 返回给调用方的错误消息；不得包含敏感数据
   */
  public ApiException(ErrorCode errorCode, String message) {
    this(errorCode, message, null);
  }

  /**
   * 保留内部根因，供服务端诊断；根因消息不会直接进入 API 响应。
   *
   * @param errorCode 决定响应状态和稳定业务码的错误类型
   * @param message 返回给调用方的错误消息；不得包含敏感数据
   * @param cause 内部根因，可为 {@code null}
   */
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
   * @return 使用错误码所配置 HTTP 状态的异常
   */
  public static ApiException bad(ErrorCode code, String message) {
    return new ApiException(code, message);
  }

  /**
   * 创建上游服务错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return 使用错误码所配置 HTTP 状态的异常
   */
  public static ApiException upstream(ErrorCode code, String message) {
    return new ApiException(code, message);
  }

  /**
   * 创建保留内部根因的上游服务错误；响应状态仍由错误码决定。
   *
   * @param code 业务错误码
   * @param message 可返回的错误消息
   * @param cause 仅供内部诊断的根因
   * @return 保留根因的异常
   */
  public static ApiException upstream(ErrorCode code, String message, Throwable cause) {
    return new ApiException(code, message, cause);
  }

  /**
   * 创建资源状态冲突错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return 使用错误码所配置 HTTP 状态的异常
   */
  public static ApiException conflict(ErrorCode code, String message) {
    return new ApiException(code, message);
  }

  /**
   * 创建资源不存在错误。
   *
   * @param code 业务错误码
   * @param message 错误信息
   * @return 使用错误码所配置 HTTP 状态的异常
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
