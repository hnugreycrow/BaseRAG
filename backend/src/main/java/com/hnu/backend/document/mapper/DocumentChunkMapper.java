package com.hnu.backend.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.document.entity.DocumentChunk;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {
  default int deleteByKnowledgeBase(UUID knowledgeBaseId) {
    return delete(
        Wrappers.<DocumentChunk>lambdaQuery()
            .apply(
                "document_id IN (SELECT id FROM documents WHERE knowledge_base_id = {0})",
                knowledgeBaseId));
  }

  default int insertVector(DocumentChunk chunk) {
    return insert(chunk);
  }
}
