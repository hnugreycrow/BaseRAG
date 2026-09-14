package com.hnu.backend.rag.vo;

import java.util.List;

/**
 * RAG 问答的完整响应。
 *
 * @param answer 生成的回答正文
 * @param sources 回答可引用的来源详情
 * @param citations 回答实际使用的引用标识
 * @param modelInfo 实际使用的模型信息
 */
public record AnswerResponse(
    String answer,
    List<SourceResponse> sources,
    List<String> citations,
    ModelInfoResponse modelInfo) {}
