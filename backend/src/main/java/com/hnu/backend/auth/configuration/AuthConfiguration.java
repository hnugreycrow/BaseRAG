package com.hnu.backend.auth.configuration;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.hnu.backend.auth.service.CsrfTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册密码编码器、认证拦截器和写请求 CSRF 防护。 */
@Configuration
public class AuthConfiguration implements WebMvcConfigurer {
  private final CsrfTokenService csrfTokens;

  /**
   * 创建认证配置。
   *
   * @param csrfTokens CSRF nonce 服务
   */
  public AuthConfiguration(CsrfTokenService csrfTokens) {
    this.csrfTokens = csrfTokens;
  }

  /**
   * 创建成本因子为 12 的 BCrypt 编码器。
   *
   * @return 密码编码器
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /**
   * 为 API 注册先认证、后 CSRF 的拦截顺序。
   *
   * @param registry Spring MVC 拦截器注册器
   */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login")
        .order(Ordered.HIGHEST_PRECEDENCE);
    registry
        .addInterceptor(new CsrfInterceptor(csrfTokens))
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login")
        .order(Ordered.HIGHEST_PRECEDENCE + 1);
  }
}
