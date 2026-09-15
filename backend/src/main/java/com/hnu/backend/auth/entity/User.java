package com.hnu.backend.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

/** 用户账号实体；密码字段只保存 BCrypt 哈希。 */
@Data
@TableName("users")
public class User {
  /** 用户标识。 */
  @TableId(type = IdType.INPUT)
  private UUID id;

  /** 已规范化为小写的唯一登录名。 */
  private String username;

  /** 用于界面展示的名称。 */
  private String displayName;

  /** BCrypt 密码哈希。 */
  private String passwordHash;

  /** 账号角色。 */
  private UserRole role;

  /** 账号是否允许登录。 */
  private boolean enabled;

  /** 最近一次成功登录时间。 */
  private OffsetDateTime lastLoginAt;

  /** 创建时间。 */
  private OffsetDateTime createdAt;

  /** 最近更新时间。 */
  private OffsetDateTime updatedAt;
}
