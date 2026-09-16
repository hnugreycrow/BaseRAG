package com.hnu.backend.auth.entity;

/** BaseRAG 首期账号角色。 */
public enum UserRole {
  /** 可以使用私有会话与公共知识库问答。 */
  USER,
  /** 在普通用户能力之上可以管理账号、公共知识库和系统配置。 */
  ADMIN
}
