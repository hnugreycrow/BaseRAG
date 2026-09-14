package com.hnu.backend.document.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 文档摘要响应。
 *
 * @param id 文档标识
 * @param name 文档名称
 * @param status 当前处理状态
 * @param errorCode 处理失败时的错误码
 * @param chunkCount 当前有效版本的分块数量
 * @param createdAt 创建时间
 */
public record DocumentResponse(
    UUID id,
    String name,
    String status,
    String errorCode,
    long chunkCount,
    OffsetDateTime createdAt) {}
