package com.hnu.backend.rag.retrieval;

/**
 * 知识库数据所绑定的向量模型规格。
 *
 * @param model 模型名称
 * @param dimensions 向量维度
 */
public record EmbeddingBinding(String modelId, String provider, String model, int dimensions) {}
