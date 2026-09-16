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
 * @param lineStart 兼容旧版 Markdown 的起始行号，其他格式为空
 * @param lineEnd 兼容旧版 Markdown 的结束行号，其他格式为空
 * @param characterCount 正文字符数
 * @param content 完整分块正文
 * @param sourceUnit 来源位置单位：行、页或段落
 * @param sourceStart 来源起始位置
 * @param sourceEnd 来源结束位置
 */
public record DocumentChunkDetailResponse(
    UUID id,
    UUID documentId,
    UUID versionId,
    int chunkIndex,
    String heading,
    Integer lineStart,
    Integer lineEnd,
    int characterCount,
    String content,
    String sourceUnit,
    int sourceStart,
    int sourceEnd) {}
