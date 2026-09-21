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
 * @param errorMessage 由错误码目录派生的安全错误说明
 * @param chunkCount 当前有效版本的分块数量
 * @param createdAt 创建时间
 * @param format 原文件格式
 * @param mediaType 原文件 MIME 类型
 * @param fileSizeBytes 原文件大小，单位为字节
 * @param previewAvailable 原文件是否支持在线预览
 */
public record DocumentResponse(
    UUID id,
    String name,
    String status,
    String errorCode,
    String errorMessage,
    long chunkCount,
    OffsetDateTime createdAt,
    String format,
    String mediaType,
    long fileSizeBytes,
    boolean previewAvailable) {}
