package com.hnu.backend.document.vo;

import java.util.List;
import java.util.UUID;

/**
 * 按提交顺序记录批量上传中每个文件的结果。
 *
 * @param results 与请求文件顺序对应的逐项结果
 */
public record DocumentBatchUploadResponse(List<Item> results) {
  /**
   * 批量上传中单个文件的结果，失败项不影响其余文件的独立结果。
   *
   * @param index 文件在本次请求中的零基序号
   * @param fileName 原文件名
   * @param status 单项处理状态
   * @param documentId 成功创建的文档标识；失败时为空
   * @param errorCode 失败时的稳定错误码；成功时为空
   * @param message 失败时的可展示提示；成功时为空
   */
  public record Item(
      int index,
      String fileName,
      String status,
      UUID documentId,
      String errorCode,
      String message) {}
}
