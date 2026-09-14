package com.hnu.backend.rag.retrieval;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RetrievalMapper {
  List<EmbeddingBinding> activeModelBindings();

  List<EmbeddingBinding> activeModelBindingsIn(
      @Param("knowledgeBaseIds") List<UUID> knowledgeBaseIds);

  List<SearchHit> searchAll(
      @Param("vector") String vector,
      @Param("model") String model,
      @Param("dimensions") int dimensions,
      @Param("limit") int limit);

  List<SearchHit> searchIn(
      @Param("knowledgeBaseIds") List<UUID> knowledgeBaseIds,
      @Param("vector") String vector,
      @Param("model") String model,
      @Param("dimensions") int dimensions,
      @Param("limit") int limit);

  List<SearchHit> search(
      @Param("knowledgeBaseId") UUID knowledgeBaseId,
      @Param("vector") String vector,
      @Param("model") String model,
      @Param("dimensions") int dimensions,
      @Param("limit") int limit);
}
