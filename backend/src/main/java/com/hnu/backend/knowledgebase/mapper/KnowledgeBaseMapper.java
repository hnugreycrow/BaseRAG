package com.hnu.backend.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 知识库的数据访问接口。 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
  /**
   * 按标识查询并锁定知识库行。
   *
   * @param id 知识库标识
   * @return 已锁定的知识库；不存在时返回 {@code null}
   */
  KnowledgeBase lock(@Param("id") UUID id);

  /**
   * 分页查询知识库，并附带各知识库的文档数量。
   *
   * @param query 可选的名称搜索词
   * @param limit 最大返回数量
   * @param offset 分页偏移量
   * @return 知识库列表
   */
  List<KnowledgeBase> selectWithDocumentCount(
      @Param("query") String query, @Param("limit") int limit, @Param("offset") long offset);

  /**
   * 统计匹配名称条件的知识库数量。
   *
   * @param query 可选的名称搜索词
   * @return 匹配数量
   */
  long countWithDocumentCount(@Param("query") String query);
}
