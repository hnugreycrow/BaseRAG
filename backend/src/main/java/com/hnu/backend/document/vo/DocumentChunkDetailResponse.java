package com.hnu.backend.document.vo;

import java.util.UUID;

/**
 * 文档分块详情响应。
 *
 * @param id 分块标识
 * @param documentId 文档标识
 * @param versionId 文档版本标识
 * @param chunkIndex 分块在文档中的顺序
 * @param heading 分块所属标题
 * @param lineStart 原文起始行号
 * @param lineEnd 原文结束行号
 * @param characterCount 正文字符数
 * @param content 完整分块正文
 */
public record DocumentChunkDetailResponse(
    UUID id,
    UUID documentId,
    UUID versionId,
    int chunkIndex,
    String heading,
    int lineStart,
    int lineEnd,
    int characterCount,
    String content) {}
