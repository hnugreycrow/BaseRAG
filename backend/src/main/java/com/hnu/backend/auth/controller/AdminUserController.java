package com.hnu.backend.auth.controller;

import com.hnu.backend.auth.dto.CreateUserRequest;
import com.hnu.backend.auth.dto.ResetPasswordRequest;
import com.hnu.backend.auth.dto.UserStatusRequest;
import com.hnu.backend.auth.entity.User;
import com.hnu.backend.auth.service.AdminUserService;
import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.auth.vo.UserResponse;
import com.hnu.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 提供仅管理员可用的账号管理接口。 */
@RestController
@Profile("local")
@Validated
@RequestMapping("/api/admin/users")
public class AdminUserController {
  private final CurrentUserService currentUserService;
  private final AdminUserService adminUserService;

  /**
   * 创建账号管理控制器。
   *
   * @param currentUserService 当前用户解析服务
   * @param adminUserService 管理员账号服务
   */
  public AdminUserController(
      CurrentUserService currentUserService, AdminUserService adminUserService) {
    this.currentUserService = currentUserService;
    this.adminUserService = adminUserService;
  }

  /**
   * 分页查询账号。
   *
   * @param page 页码
   * @param pageSize 每页数量
   * @param query 可选搜索词
   * @return 用户分页
   */
  @GetMapping
  public PageResponse<UserResponse> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 100) String query) {
    currentUserService.requireAdmin();
    return adminUserService.list(page, pageSize, query);
  }

  /**
   * 创建账号。知识库由用户在账号创建后按需建立。
   *
   * @param request 新账号字段
   * @return 新用户
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
    currentUserService.requireAdmin();
    return adminUserService.create(
        request.username(), request.displayName(), request.password(), request.role());
  }

  /**
   * 更新账号启用状态。
   *
   * @param id 目标用户
   * @param request 目标状态
   * @return 更新后的用户
   */
  @PatchMapping("/{id}/status")
  public UserResponse status(@PathVariable UUID id, @Valid @RequestBody UserStatusRequest request) {
    User actor = currentUserService.requireAdmin();
    return adminUserService.setEnabled(actor.getId(), id, request.enabled());
  }

  /**
   * 重置目标用户密码并注销其全部会话。
   *
   * @param id 目标用户
   * @param request 新密码
   */
  @PutMapping("/{id}/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(
      @PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
    currentUserService.requireAdmin();
    adminUserService.resetPassword(id, request.password());
  }
}
