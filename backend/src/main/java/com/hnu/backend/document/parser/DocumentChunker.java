package com.hnu.backend.document.parser;

import java.util.List;

/** 将有序结构块转换为有序索引片段，不执行存储或模型调用。 */
public interface DocumentChunker {
  /** 返回按原文顺序排列的分块；空列表表示没有可用正文。 */
  List<DocumentChunkResult> pack(List<StructuredBlock> blocks);

  /** 返回持久化的算法版本；同一版本必须保持分块语义兼容。 */
  String version();
}
