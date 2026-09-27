package com.hnu.backend.document.parser;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 上传时验证文件签名与可解析性，不执行分块或索引。 */
public final class DocumentUploadValidator {
  /** PDF 文件的固定开头，用于拒绝伪造扩展名。 */
  private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  /** DOCX 使用 ZIP 容器，此处先核对本地文件头。 */
  private static final byte[] ZIP_HEADER = {'P', 'K', 3, 4};

  /** 旧版 DOC 的 OLE 文件头，当前阶段明确拒绝。 */
  private static final byte[] OLE_HEADER = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0};

  private final DocumentParserRegistry parsers;

  /** 创建上传验证器。 */
  public DocumentUploadValidator(DocumentParserRegistry parsers) {
    this.parsers = parsers;
  }

  /**
   * 核对扩展名、文件头和内部内容，拒绝不匹配或无法解析的文件。
   *
   * @param name 原文件名
   * @param bytes 文件内容
   * @return 通过校验的文档格式
   */
  public DocumentFormat verify(String name, byte[] bytes) {
    DocumentFormat format = DocumentFormat.fromName(name);
    boolean pdf = startsWith(bytes, PDF_HEADER);
    boolean zip = startsWith(bytes, ZIP_HEADER);
    boolean ole = startsWith(bytes, OLE_HEADER);
    if (ole
        || (format == DocumentFormat.PDF && !pdf)
        || (format == DocumentFormat.DOCX && !zip)
        || (format == DocumentFormat.MARKDOWN && (pdf || zip))) {
      throw ApiException.bad(ErrorCode.INVALID_FILE_FORMAT, "文件内容与扩展名不符");
    }
    parsers.parser(format).parse(bytes);
    return format;
  }

  /** 判断文件内容是否具有指定的二进制文件头。 */
  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length
        && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
  }
}
