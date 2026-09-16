package com.hnu.backend.document.vo;

import java.util.List;
import java.util.UUID;

/** 按提交顺序记录批量上传中每个文件的结果。 */
public record DocumentBatchUploadResponse(List<Item> results) {
  public record Item(
      int index,
      String fileName,
      String status,
      UUID documentId,
      String errorCode,
      String message) {}
}
