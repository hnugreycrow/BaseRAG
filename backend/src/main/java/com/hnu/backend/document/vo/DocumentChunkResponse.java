package com.hnu.backend.document.vo;

import java.util.UUID;

/**
 * 文档分块列表项。
 *
 * @param id 分块标识
 * @param chunkIndex 分块在文档中的顺序
 * @param heading 分块所属标题
 * @param lineStart 兼容旧版 Markdown 的起始行号，其他格式为空
 * @param lineEnd 兼容旧版 Markdown 的结束行号，其他格式为空
 * @param characterCount 正文字符数
 * @param preview 正文预览
 * @param sourceUnit 来源位置单位：行、页或段落
 * @param sourceStart 来源起始位置
 * @param sourceEnd 来源结束位置
 */
public record DocumentChunkResponse(
    UUID id,
    int chunkIndex,
    String heading,
    Integer lineStart,
    Integer lineEnd,
    int characterCount,
    String preview,
    String sourceUnit,
    int sourceStart,
    int sourceEnd) {}
