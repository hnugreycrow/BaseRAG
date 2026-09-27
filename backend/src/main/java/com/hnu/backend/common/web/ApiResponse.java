package com.hnu.backend.common.web;

/**
 * 普通 JSON 接口的统一响应协议；HTTP 状态码仍用于表达请求是否成功。
 *
 * @param code 稳定业务码
 * @param message 可展示的简短消息
 * @param data 业务数据，失败时可为空
 * @param requestId 用于定位服务端请求日志的标识
 */
public record ApiResponse<T>(String code, String message, T data, String requestId) {
  /** 成功响应使用的稳定业务码。 */
  public static final String SUCCESS_CODE = "SUCCESS";

  /** 成功响应的默认消息。 */
  public static final String SUCCESS_MESSAGE = "请求成功";

  /**
   * 创建成功响应。
   *
   * @param data 返回的业务数据
   * @param requestId 当前请求标识
   * @return 带统一成功码的响应
   */
  public static <T> ApiResponse<T> success(T data, String requestId) {
    return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, requestId);
  }

  /**
   * 创建失败响应；调用方仍须设置对应的 HTTP 状态。
   *
   * @param code 稳定业务错误码
   * @param message 可展示的错误消息
   * @param data 附加数据，可为空
   * @param requestId 当前请求标识
   * @return 保留调用方错误信息的响应
   */
  public static <T> ApiResponse<T> failure(String code, String message, T data, String requestId) {
    return new ApiResponse<>(code, message, data, requestId);
  }
}
