package com.hnu.backend.document.parser;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 选择指定格式的解析器，不承担上传校验或分块。 */
public final class DocumentParserRegistry {
  private final Map<DocumentFormat, DocumentParser> parsers;

  /** 注册解析器；重复格式视为装配错误。 */
  public DocumentParserRegistry(List<DocumentParser> parsers) {
    this.parsers =
        Map.copyOf(
            parsers.stream()
                .collect(Collectors.toMap(DocumentParser::format, Function.identity())));
  }

  /** 返回已注册的格式解析器。 */
  public DocumentParser parser(DocumentFormat format) {
    return parsers.get(format);
  }
}
