package com.hnu.backend.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;

/** 在 Sa-Token Token Session 中签发和验证 CSRF nonce。 */
@Service
public class CsrfTokenService {
  public static final String HEADER = "X-CSRF-Token";
  private static final String SESSION_KEY = "baserag.csrf";
  private final SecureRandom random = new SecureRandom();

  /**
   * 生成新的 nonce 并替换当前 Token Session 中的旧值。
   *
   * @return Base64 URL 编码的 256 位 nonce
   * @throws org.springframework.data.redis.RedisSystemException Token Session 无法写入 Redis 时抛出
   */
  public String issue() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    StpUtil.getTokenSession().set(SESSION_KEY, token);
    return token;
  }

  /**
   * 使用恒定时间比较校验请求 Header。
   *
   * @param submitted 浏览器提交的 nonce
   * @throws ApiException nonce 缺失、过期或不匹配时抛出 403
   */
  public void requireValid(String submitted) {
    Object stored = StpUtil.getTokenSession().get(SESSION_KEY);
    boolean valid =
        stored instanceof String expected
            && submitted != null
            && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                submitted.getBytes(StandardCharsets.UTF_8));
    if (!valid) {
      throw new ApiException(ErrorCode.CSRF_INVALID, "CSRF Token 无效，请刷新页面后重试");
    }
  }
}
