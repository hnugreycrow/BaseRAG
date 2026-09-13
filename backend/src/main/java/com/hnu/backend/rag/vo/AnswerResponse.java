package com.hnu.backend.rag.vo;

import java.util.List;

public record AnswerResponse(
    String answer,
    List<SourceResponse> sources,
    List<String> citations,
    ModelInfoResponse modelInfo) {}
