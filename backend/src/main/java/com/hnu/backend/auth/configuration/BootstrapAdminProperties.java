package com.hnu.backend.auth.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 首次管理员的环境变量映射；数据库初始化完成后不再读取其值。 */
@Component
@ConfigurationProperties(prefix = "baserag.bootstrap-admin")
public class BootstrapAdminProperties {
  private String username = "";
  private String displayName = "";
  private String password = "";

  /**
   * @return 首次管理员用户名
   */
  public String getUsername() {
    return username;
  }

  /**
   * @param username 首次管理员用户名
   */
  public void setUsername(String username) {
    this.username = username;
  }

  /**
   * @return 首次管理员显示名
   */
  public String getDisplayName() {
    return displayName;
  }

  /**
   * @param displayName 首次管理员显示名
   */
  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  /**
   * @return 首次管理员明文密码
   */
  public String getPassword() {
    return password;
  }

  /**
   * @param password 首次管理员明文密码
   */
  public void setPassword(String password) {
    this.password = password;
  }
}
