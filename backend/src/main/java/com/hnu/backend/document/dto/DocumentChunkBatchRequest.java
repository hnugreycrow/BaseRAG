package com.hnu.backend.document.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 当前知识库待分块文档的批量提交请求。 */
public record DocumentChunkBatchRequest(@NotEmpty @Size(max = 50) List<UUID> documentIds) {}
