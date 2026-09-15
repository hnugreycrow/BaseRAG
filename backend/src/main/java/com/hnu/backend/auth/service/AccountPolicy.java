package com.hnu.backend.auth.service;

import com.hnu.backend.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 统一执行账号字段规范化与密码边界校验。 */
@Component
public class AccountPolicy {
  private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,64}");

  /**
   * 规范化并校验登录名。
   *
   * @param raw 原始用户名
   * @return 去除首尾空白并转为小写的用户名
   * @throws ApiException 用户名不符合 ASCII 字符集、长度或保留名约束时抛出
   */
  public String username(String raw) {
    String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(value).matches() || "__legacy_owner__".equals(value)) {
      throw ApiException.bad("INVALID_USERNAME", "用户名应为 3 到 64 位 ASCII 字母、数字或 . _ -");
    }
    return value;
  }

  /**
   * 校验并返回显示名。
   *
   * @param raw 原始显示名
   * @return 去除首尾空白后的显示名
   * @throws ApiException 显示名为空、超长或包含控制字符时抛出
   */
  public String displayName(String raw) {
    String value = raw == null ? "" : raw.strip();
    if (value.isEmpty()
        || value.length() > 100
        || value.chars().anyMatch(Character::isISOControl)) {
      throw ApiException.bad("INVALID_DISPLAY_NAME", "显示名应为 1 到 100 个有效字符");
    }
    return value;
  }

  /**
   * 校验 BCrypt 可安全处理的密码长度。
   *
   * @param raw 明文密码
   * @return 通过校验的原始密码
   * @throws ApiException 密码不足 12 字符、包含空字符或超过 BCrypt 72 字节上限时抛出
   */
  public String password(String raw) {
    int characters = raw == null ? 0 : raw.codePointCount(0, raw.length());
    int bytes = raw == null ? 0 : raw.getBytes(StandardCharsets.UTF_8).length;
    if (characters < 12 || bytes > 72 || raw.chars().anyMatch(value -> value == 0)) {
      throw ApiException.bad("INVALID_PASSWORD", "密码至少 12 个字符且 UTF-8 编码不能超过 72 字节");
    }
    return raw;
  }
}
