package com.hnu.backend.document.parser;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 将严格校验后的 UTF-8 Markdown 转为带行号的结构块。
 *
 * @param chunker 结构提取器，不负责通用打包
 */
public record MarkdownParser(MarkdownStructureParser chunker) implements DocumentParser {
  /** 返回本解析器处理的格式。 */
  @Override
  public DocumentFormat format() {
    return DocumentFormat.MARKDOWN;
  }

  /** 解析已读取的文件字节，并生成带来源位置的结构块。 */
  @Override
  public List<StructuredBlock> parse(byte[] bytes) {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (text.startsWith("\uFEFF")) {
        text = text.substring(1);
      }
      if (text.indexOf('\0') >= 0) {
        throw ApiException.bad(ErrorCode.INVALID_FILE, "Markdown 不能包含二进制空字符");
      }
      if (text.isBlank()) {
        throw ApiException.bad(ErrorCode.EMPTY_DOCUMENT, "文档没有可用文本");
      }
      return chunker.parse(text);
    } catch (java.nio.charset.CharacterCodingException e) {
      throw ApiException.bad(ErrorCode.INVALID_UTF8, "请使用 UTF-8 编码的 Markdown 文件");
    }
  }
}
