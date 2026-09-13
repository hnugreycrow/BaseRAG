package com.hnu.backend.document.vo;

import java.util.UUID;

public record DocumentImportResponse(UUID documentId, String status, int chunkCount) {}
