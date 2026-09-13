package com.hnu.backend.document.api;

import java.util.UUID;

public record DocumentImportResponse(UUID documentId, String status, int chunkCount) {}
