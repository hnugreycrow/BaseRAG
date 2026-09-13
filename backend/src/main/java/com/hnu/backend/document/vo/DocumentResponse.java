package com.hnu.backend.document.vo;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(
    UUID id,
    String name,
    String status,
    String errorCode,
    long chunkCount,
    OffsetDateTime createdAt) {}
