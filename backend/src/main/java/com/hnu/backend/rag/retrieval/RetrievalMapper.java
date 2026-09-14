package com.hnu.backend.rag.retrieval;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 基于 pgvector 执行知识库向量检索的数据访问接口。 */
@Mapper
public interface RetrievalMapper {
  /**
   * 查询所有具有有效文档的向量模型绑定。
   *
   * @return 去重后的模型绑定列表
   */
  List<EmbeddingBinding> activeModelBindings();

  /**
   * 查询指定知识库集合中具有有效文档的向量模型绑定。
   *
   * @param knowledgeBaseIds 知识库标识集合
   * @return 去重后的模型绑定列表
   */
  List<EmbeddingBinding> activeModelBindingsIn(
      @Param("knowledgeBaseIds") List<UUID> knowledgeBaseIds);

  /**
   * 在所有兼容指定模型规格的知识库中检索候选分块。
   *
   * @param vector 查询向量的 pgvector 字面量
   * @param model 向量模型名称
   * @param dimensions 向量维度
   * @param limit 最大返回数量
   * @return 按相似度排序的候选分块
   */
  List<SearchHit> searchAll(
      @Param("vector") String vector,
      @Param("model") String model,
      @Param("dimensions") int dimensions,
      @Param("limit") int limit);

  /**
   * 在指定知识库集合中检索兼容模型规格的候选分块。
   *
   * @param knowledgeBaseIds 知识库标识集合
   * @param vector 查询向量的 pgvector 字面量
   * @param model 向量模型名称
   * @param dimensions 向量维度
   * @param limit 最大返回数量
   * @return 按相似度排序的候选分块
   */
  List<SearchHit> searchIn(
      @Param("knowledgeBaseIds") List<UUID> knowledgeBaseIds,
      @Param("vector") String vector,
      @Param("model") String model,
      @Param("dimensions") int dimensions,
      @Param("limit") int limit);

  /**
   * 在单个知识库中检索候选分块。
   *
   * @param knowledgeBaseId 知识库标识
   * @param vector 查询向量的 pgvector 字面量
   * @param model 向量模型名称
   * @param dimensions 向量维度
   * @param limit 最大返回数量
   * @return 按相似度排序的候选分块
   */
  List<SearchHit> search(
      @Param("knowledgeBaseId") UUID knowledgeBaseId,
      @Param("vector") String vector,
      @Param("model") String model,
      @Param("dimensions") int dimensions,
      @Param("limit") int limit);
}
