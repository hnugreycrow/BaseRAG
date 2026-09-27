package com.hnu.backend.model.client;

import java.util.*;

/** 文档和检索共用的可替换向量模型契约。 */
public interface EmbeddingEncoder {
  /**
   * 返回默认向量模型名称。
   *
   * @return 模型名称
   */
  String model();

  /**
   * 返回默认向量维度。
   *
   * @return 向量维度
   */
  int dimensions();

  /** 校验默认向量模型是否已完整配置。 */
  void requireConfigured();

  /**
   * 校验指定的知识库向量模型是否仍可使用。
   *
   * @param modelId 模型配置标识
   * @param provider 向量供应商
   * @param model 模型名称
   * @param dimensions 向量维度
   */
  void requireConfigured(String modelId, String provider, String model, int dimensions);

  /**
   * 使用默认模型批量生成向量。
   *
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  List<float[]> embed(List<String> texts);

  /**
   * 使用指定模型批量生成向量。
   *
   * @param modelId 模型配置标识
   * @param provider 向量供应商
   * @param model 模型名称
   * @param dimensions 向量维度
   * @param texts 待编码文本
   * @return 与输入顺序一致的向量列表
   */
  List<float[]> embed(
      String modelId, String provider, String model, int dimensions, List<String> texts);
}
