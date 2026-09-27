package com.hnu.backend.document.config;

import com.hnu.backend.document.parser.*;
import com.hnu.backend.rag.config.RagProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将旧配置键装配成文档能力，算法不依赖 RAG 配置对象。 */
@Configuration
public class DocumentParserConfiguration {
  /** 保留现有配置键和默认预算。 */
  @Bean
  public DocumentParsingOptions documentParsingOptions(RagProperties config) {
    return new DocumentParsingOptions(
        config.getChunkSize(),
        config.getChunkMinSize(),
        config.getChunkMaxSize(),
        config.getChunkOverlap());
  }

  /** 装配默认分块实现。 */
  @Bean
  public DocumentChunker documentChunker(DocumentParsingOptions options) {
    return new StructuredChunkPacker(options);
  }

  /** 注册默认三种文档格式。 */
  @Bean
  public DocumentParserRegistry documentParserRegistry(DocumentParsingOptions options) {
    int maxSize = Math.max(options.chunkSize(), options.chunkMaxSize());
    return new DocumentParserRegistry(
        List.of(
            new MarkdownParser(new MarkdownStructureParser(options)),
            new PdfParser(maxSize),
            new DocxParser(maxSize)));
  }

  /** 装配上传验证器。 */
  @Bean
  public DocumentUploadValidator documentUploadValidator(DocumentParserRegistry parsers) {
    return new DocumentUploadValidator(parsers);
  }
}
