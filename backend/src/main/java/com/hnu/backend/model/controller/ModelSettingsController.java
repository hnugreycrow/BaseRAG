package com.hnu.backend.model.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hnu.backend.model.service.ModelSettingsService;
import com.hnu.backend.model.vo.ModelSettingsResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供仅管理员可访问的系统模型配置。 */
@RestController
@Profile("local")
@RequestMapping("/api/admin/settings/models")
@SaCheckRole("ADMIN")
public class ModelSettingsController {
  private final ModelSettingsService service;

  /**
   * 创建只读配置控制器。
   *
   * @param service 模型配置查询服务
   */
  public ModelSettingsController(ModelSettingsService service) {
    this.service = service;
  }

  /**
   * 查询当前进程实际生效的模型配置。
   *
   * @return 不包含密钥及连接地址的配置摘要
   */
  @GetMapping
  public ModelSettingsResponse get() {
    return service.get();
  }
}
