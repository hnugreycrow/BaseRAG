package com.hnu.backend.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
  KnowledgeBase lock(@Param("id") UUID id);

  List<KnowledgeBase> selectWithDocumentCount(
      @Param("query") String query, @Param("limit") int limit, @Param("offset") long offset);

  long countWithDocumentCount(@Param("query") String query);
}
