package com.hnu.backend.document.api;

import java.util.UUID;

public record DocumentChunkResponse(
    UUID id,
    int chunkIndex,
    String heading,
    int lineStart,
    int lineEnd,
    int characterCount,
    String preview) {}
