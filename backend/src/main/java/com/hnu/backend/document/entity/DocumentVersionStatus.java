package com.hnu.backend.document.entity;

/** 文档版本的导入处理状态。 */
public enum DocumentVersionStatus {
  /** 文件已上传，等待处理。 */
  UPLOADED,
  /** 正在解析和向量化。 */
  PROCESSING,
  /** 已完成处理，可供检索。 */
  READY,
  /** 处理失败。 */
  FAILED
}
