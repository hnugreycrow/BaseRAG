package com.hnu.backend.auth.controller;

import com.hnu.backend.auth.dto.ChangePasswordRequest;
import com.hnu.backend.auth.dto.LoginRequest;
import com.hnu.backend.auth.service.AuthService;
import com.hnu.backend.auth.vo.AuthSessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 提供 Cookie 会话认证和本人密码管理接口。 */
@RestController
@Profile("local")
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;

  /**
   * 创建认证控制器。
   *
   * @param authService 认证服务
   */
  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  /**
   * 使用用户名和密码登录。
   *
   * @param request 登录凭据
   * @param http Servlet 请求，用于取得直接客户端地址
   * @return 用户与 CSRF nonce
   */
  @PostMapping("/login")
  public AuthSessionResponse login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    return authService.login(request.username(), request.password(), http.getRemoteAddr());
  }

  /**
   * 恢复当前 Cookie 登录态。
   *
   * @return 用户与刷新后的 CSRF nonce
   */
  @GetMapping("/session")
  public AuthSessionResponse session() {
    return authService.restore();
  }

  /** 注销当前 Token 并清除 Cookie。 */
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout() {
    authService.logout();
  }

  /**
   * 验证旧密码并修改当前用户密码。
   *
   * @param request 新旧密码
   * @return 新登录态与 CSRF nonce
   */
  @PutMapping("/password")
  public AuthSessionResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
    return authService.changePassword(request.oldPassword(), request.newPassword());
  }
}
