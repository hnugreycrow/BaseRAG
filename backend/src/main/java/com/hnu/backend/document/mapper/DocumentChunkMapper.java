package com.hnu.backend.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.backend.document.entity.DocumentChunk;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

/** 文档分块的数据访问接口。 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {
  /**
   * 删除指定知识库下所有文档的分块。
   *
   * @param knowledgeBaseId 知识库标识
   * @return 删除的分块数量
   */
  default int deleteByKnowledgeBase(UUID knowledgeBaseId) {
    return delete(
        Wrappers.<DocumentChunk>lambdaQuery()
            .apply(
                "document_id IN (SELECT id FROM documents WHERE knowledge_base_id = {0})",
                knowledgeBaseId));
  }

  /**
   * 插入包含 pgvector 文本向量的分块。
   *
   * @param chunk 待插入分块
   * @return 受影响行数
   */
  default int insertVector(DocumentChunk chunk) {
    return insert(chunk);
  }
}
