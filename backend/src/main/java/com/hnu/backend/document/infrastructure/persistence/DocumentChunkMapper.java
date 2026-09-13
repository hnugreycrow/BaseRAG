package com.hnu.backend.document.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.document.domain.DocumentChunk;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {
  int deleteByKnowledgeBase(@Param("knowledgeBaseId") UUID knowledgeBaseId);

  int insertVector(DocumentChunk chunk);
}
