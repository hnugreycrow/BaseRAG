package com.hnu.backend.document.parser;

import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.util.Locale;

/** 受支持的原文件格式及其存储和解析元数据。 */
public enum DocumentFormat {
  /** 严格按 UTF-8 解码的 Markdown 文本。 */
  MARKDOWN("text/markdown; charset=utf-8", ".md", "markdown-v1"),
  /** 逐页提取文字的文本型 PDF。 */
  PDF("application/pdf", ".pdf", "pdfbox-v1"),
  /** 使用 OOXML Word 主文档结构解析的 DOCX。 */
  DOCX(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      ".docx",
      "poi-docx-v1");

  /** 写入对象存储及内容响应的 MIME 类型。 */
  private final String mediaType;

  /** 对象键使用的规范扩展名。 */
  private final String extension;

  /** 记录在文档版本中的解析器标识。 */
  private final String parserVersion;

  /**
   * 定义一种受支持文件的存储和解析元数据。
   *
   * @param mediaType 原文件 MIME 类型
   * @param extension 对象键扩展名
   * @param parserVersion 解析器版本标识
   */
  DocumentFormat(String mediaType, String extension, String parserVersion) {
    this.mediaType = mediaType;
    this.extension = extension;
    this.parserVersion = parserVersion;
  }

  /** 返回服务端确定的 MIME 类型。 */
  public String mediaType() {
    return mediaType;
  }

  /** 返回对象键使用的规范扩展名。 */
  public String extension() {
    return extension;
  }

  /** 返回当前格式的解析器版本标识。 */
  public String parserVersion() {
    return parserVersion;
  }

  /**
   * 根据文件名确定允许的格式；上传流程随后还会校验签名和实际内容。
   *
   * @param name 已去除目录部分的文件名
   * @return 文件名对应的受支持格式
   */
  public static DocumentFormat fromName(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
      return MARKDOWN;
    }
    if (lower.endsWith(".pdf")) {
      return PDF;
    }
    if (lower.endsWith(".docx")) {
      return DOCX;
    }
    throw ApiException.bad(ErrorCode.INVALID_FILE, "仅支持 Markdown、PDF 和 DOCX 文件");
  }
}
