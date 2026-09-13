package com.hnu.backend.document.vo;

import java.util.UUID;

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
