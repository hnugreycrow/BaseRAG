package com.hnu.backend.document.parser;

import java.util.List;

/** 将一种原文件格式转换成带真实来源位置的结构块，不负责分块策略。 */
public interface DocumentParser {
  /** 返回当前解析器处理的文件格式。 */
  DocumentFormat format();

  /**
   * 解析已通过上传大小限制的原文件字节。
   *
   * @param bytes 原文件内容
   * @return 按原文顺序排列的结构块
   */
  List<StructuredBlock> parse(byte[] bytes);
}
