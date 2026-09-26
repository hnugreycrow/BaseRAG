package com.hnu.backend.knowledgebase.api;

import java.util.UUID;

/** 向跨模块删除编排公开知识库记录删除能力。 */
public interface KnowledgeBaseRemoval {
  /**
   * 删除已经完成关联资源清理的知识库记录。
   *
   * <p>调用方必须先校验管理权限，并在同一个事务中删除关联文档。
   *
   * @param id 非空且已授权的知识库标识
   * @throws com.hnu.backend.shared.error.ApiException 记录已不存在时抛出，触发整体回滚
   */
  void deleteRecord(UUID id);
}
