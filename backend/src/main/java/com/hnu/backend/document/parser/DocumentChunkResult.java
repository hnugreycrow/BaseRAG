package com.hnu.backend.document.parser;

/**
 * 与打包算法无关的文档分块结果。
 *
 * @param content 展示正文
 * @param embeddingText 向量化文本
 * @param heading 标题路径
 * @param source 原文件来源范围
 */
public record DocumentChunkResult(
    String content, String embeddingText, String heading, StructuredBlock.SourceSpan source) {}
