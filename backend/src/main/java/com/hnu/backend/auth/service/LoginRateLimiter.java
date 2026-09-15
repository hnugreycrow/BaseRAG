package com.hnu.backend.auth.service;

import com.hnu.backend.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 使用 Redis 固定窗口限制同一用户名和客户端地址的连续登录失败。 */
@Service
public class LoginRateLimiter {
  private static final int MAX_FAILURES = 5;
  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final DefaultRedisScript<Long> RECORD_FAILURE =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]);"
              + "if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end;"
              + "return n;",
          Long.class);
  private final StringRedisTemplate redis;

  /**
   * 创建登录限流器。
   *
   * @param redis Redis 字符串客户端
   */
  public LoginRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * 在密码校验前拒绝已经达到失败上限的窗口。
   *
   * @param username 已规范化用户名
   * @param clientAddress Servlet 直接客户端地址
   * @throws ApiException 该组合键已有五次失败时抛出 429
   */
  public void requireAllowed(String username, String clientAddress) {
    String value = redis.opsForValue().get(key(username, clientAddress));
    if (value != null && Long.parseLong(value) >= MAX_FAILURES) {
      throw new ApiException(
          "LOGIN_RATE_LIMITED", "登录失败次数过多，请 15 分钟后重试", HttpStatus.TOO_MANY_REQUESTS);
    }
  }

  /**
   * 原子增加失败次数，并只在窗口首次失败时设置 TTL。
   *
   * @param username 已规范化用户名
   * @param clientAddress Servlet 直接客户端地址
   * @throws org.springframework.data.redis.RedisSystemException Redis 原子计数不可用时抛出并失败关闭
   */
  public void recordFailure(String username, String clientAddress) {
    redis.execute(
        RECORD_FAILURE, List.of(key(username, clientAddress)), String.valueOf(WINDOW.toSeconds()));
  }

  /**
   * 清除成功登录对应的失败窗口。
   *
   * @param username 已规范化用户名
   * @param clientAddress Servlet 直接客户端地址
   * @throws org.springframework.data.redis.RedisSystemException Redis 删除不可用时抛出并失败关闭
   */
  public void clear(String username, String clientAddress) {
    redis.delete(key(username, clientAddress));
  }

  /**
   * 对限流维度哈希，避免 Redis key 暴露用户名或客户端地址。
   *
   * @param username 已规范化用户名
   * @param clientAddress 客户端地址
   * @return namespaced Redis key
   */
  private String key(String username, String clientAddress) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((username + "\n" + clientAddress).getBytes(StandardCharsets.UTF_8));
      return "baserag:auth:login-fail:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
