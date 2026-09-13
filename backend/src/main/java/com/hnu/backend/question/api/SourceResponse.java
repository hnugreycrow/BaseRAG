package com.hnu.backend.question.api;

import java.util.UUID;

public record SourceResponse(
    String citationId,
    UUID knowledgeBaseId,
    String knowledgeBaseName,
    UUID chunkId,
    UUID documentId,
    UUID versionId,
    String documentName,
    String heading,
    int lineStart,
    int lineEnd,
    double similarity,
    String content) {}
