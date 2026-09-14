package com.hnu.backend.knowledgebase.vo;

/**
 * 可供知识库选择的向量模型响应。
 *
 * @param id 模型配置标识
 * @param provider 模型供应商
 * @param model 模型名称
 * @param dimensions 向量维度
 * @param defaultModel 是否为系统默认模型
 */
public record EmbeddingModelResponse(
    String id, String provider, String model, int dimensions, boolean defaultModel) {}
