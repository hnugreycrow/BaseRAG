package com.hnu.backend.common.web;

/** 普通 JSON 接口的统一响应协议。HTTP 状态码仍用于表达请求是否成功。 */
public record ApiResponse<T>(String code, String message, T data, String requestId) {
  public static final String SUCCESS_CODE = "SUCCESS";
  public static final String SUCCESS_MESSAGE = "请求成功";

  public static <T> ApiResponse<T> success(T data, String requestId) {
    return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, requestId);
  }

  public static <T> ApiResponse<T> failure(String code, String message, T data, String requestId) {
    return new ApiResponse<>(code, message, data, requestId);
  }
}
