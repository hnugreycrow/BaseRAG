package com.hnu.backend.rag.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hnu.backend.rag.service.RetrievalSettingsService;
import com.hnu.backend.rag.vo.RetrievalSettingsResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供管理员专用的检索与回答配置查询接口。 */
@RestController
@Profile("local")
@RequestMapping("/api/admin/settings/retrieval")
@SaCheckRole("ADMIN")
public class RetrievalSettingsController {
  private final RetrievalSettingsService service;

  /**
   * 创建只读配置控制器。
   *
   * @param service 检索配置查询服务
   */
  public RetrievalSettingsController(RetrievalSettingsService service) {
    this.service = service;
  }

  /**
   * 查询当前进程的检索与回答配置。
   *
   * @return 不包含敏感连接信息的生效配置
   */
  @GetMapping
  public RetrievalSettingsResponse get() {
    return service.get();
  }
}
