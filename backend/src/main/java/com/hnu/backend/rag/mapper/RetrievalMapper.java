package com.hnu.backend.rag.mapper;

import com.hnu.backend.rag.model.EmbeddingBinding;
import com.hnu.backend.rag.model.SearchHit;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RetrievalMapper {
  List<EmbeddingBinding> activeModelBindings();

  List<SearchHit> searchAll(
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
