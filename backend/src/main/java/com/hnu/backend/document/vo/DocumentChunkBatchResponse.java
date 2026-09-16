package com.hnu.backend.document.vo;

import java.util.List;
import java.util.UUID;

/** 批量提交中接受和跳过的文档标识。 */
public record DocumentChunkBatchResponse(
    List<UUID> acceptedDocumentIds, List<UUID> skippedDocumentIds) {}
