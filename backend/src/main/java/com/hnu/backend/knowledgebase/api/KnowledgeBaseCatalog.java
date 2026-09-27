package com.hnu.backend.knowledgebase.api;

import java.util.List;
import java.util.UUID;

/** 向检索模块公开管理员创建的公共知识库标识。 */
public interface KnowledgeBaseCatalog {
  /** 返回当前公共知识库标识，保持目录查询顺序；不返回持久化实体。 */
  List<UUID> availableIds();

  /** 判断标识是否对应当前可见的公共知识库，不返回内部实体。 */
  boolean contains(UUID id);
}
