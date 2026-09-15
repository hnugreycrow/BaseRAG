package com.hnu.backend.auth.entity;

/** BaseRAG 首期账号角色。 */
public enum UserRole {
  /** 可以管理自身数据并使用问答功能。 */
  USER,
  /** 在普通用户能力之上可以管理账号。 */
  ADMIN
}
