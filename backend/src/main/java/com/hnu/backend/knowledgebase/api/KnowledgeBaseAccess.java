package com.hnu.backend.knowledgebase.api;

import java.util.UUID;

/** 向文档模块公开知识库访问校验和模型绑定能力，不暴露持久化实体。 */
public interface KnowledgeBaseAccess {
  /**
   * 校验管理员创建的公共知识库并取得创建者。
   *
   * @param id 非空知识库标识
   * @return 创建者标识，用于对象存储路径与后台任务
   * @throws com.hnu.backend.common.exception.ApiException 知识库不存在或不可见时抛出
   */
  UUID requireManagedOwner(UUID id);

  /**
   * 校验知识库归属，不返回内部实体。
   *
   * @param ownerId 非空创建者标识
   * @param id 非空知识库标识
   * @throws com.hnu.backend.common.exception.ApiException 知识库不存在或不属于创建者时抛出
   */
  void requireOwned(UUID ownerId, UUID id);

  /**
   * 校验现有向量绑定，旧数据未绑定时在事务内补绑默认模型。
   *
   * @param ownerId 非空创建者标识
   * @param id 非空知识库标识
   * @return 不含凭据的不可变模型绑定
   * @throws com.hnu.backend.common.exception.ApiException 知识库不可见或模型配置不兼容时抛出
   */
  EmbeddingBinding ensureEmbedding(UUID ownerId, UUID id);

  /**
   * 在调用方的写事务内锁定知识库并校验或补绑模型。
   *
   * @param ownerId 非空创建者标识
   * @param id 非空知识库标识
   * @param modelId 模型配置标识
   * @param provider 供应商标识
   * @param model 模型名称
   * @param dimensions 向量维度，必须与配置一致
   * @throws com.hnu.backend.common.exception.ApiException 知识库不可见或模型配置不兼容时抛出
   */
  void lockAndBind(
      UUID ownerId, UUID id, String modelId, String provider, String model, int dimensions);

  /**
   * 可供文档版本保存的模型身份快照。
   *
   * @param modelId 模型配置标识
   * @param provider 供应商标识
   * @param model 模型名称
   * @param dimensions 向量维度
   */
  record EmbeddingBinding(String modelId, String provider, String model, int dimensions) {}
}
