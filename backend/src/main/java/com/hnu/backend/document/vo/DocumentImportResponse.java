package com.hnu.backend.document.vo;

import java.util.UUID;

/**
 * 文档导入结果。
 *
 * @param documentId 文档标识
 * @param status 导入后的处理状态
 * @param chunkCount 成功生成的分块数量
 */
public record DocumentImportResponse(UUID documentId, String status, int chunkCount) {}
