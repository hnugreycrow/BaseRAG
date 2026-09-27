package com.hnu.backend.document.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 当前知识库待分块文档的批量提交请求。
 *
 * @param documentIds 待处理文档标识；请求校验要求 1 至 50 个
 */
public record DocumentChunkBatchRequest(@NotEmpty @Size(max = 50) List<UUID> documentIds) {}
