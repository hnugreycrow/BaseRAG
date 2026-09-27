package com.hnu.backend.intent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.hnu.backend.intent.dto.IntentNodeRequest;
import com.hnu.backend.intent.model.IntentNode;
import com.hnu.backend.intent.service.IntentTreeService;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 仅管理员可访问的全局意图树管理接口。 */
@RestController
@Profile("local")
@RequestMapping("/api/admin/intent-nodes")
@SaCheckRole("ADMIN")
public class IntentTreeController {
  private final IntentTreeService intentTreeService;
  private final McpToolRegistry tools;

  /**
   * 创建管理员意图树接口。
   *
   * @param intentTreeService 负责节点校验与持久化的服务
   * @param tools 提供当前可绑定的只读工具
   */
  public IntentTreeController(IntentTreeService intentTreeService, McpToolRegistry tools) {
    this.intentTreeService = intentTreeService;
    this.tools = tools;
  }

  /** 返回按排序值、名称和 ID 稳定排列的平面节点；层级由 {@code parentId} 表示。 */
  @GetMapping
  public List<IntentNode> list() {
    return intentTreeService.list();
  }

  /** 返回当前可供管理员绑定的只读工具。 */
  @GetMapping("/tools")
  public List<ToolOption> tools() {
    return tools.availableReadOnlyTools().stream()
        .map(tool -> new ToolOption(tool.name(), tool.description()))
        .toList();
  }

  /**
   * 管理员可绑定的只读工具摘要。
   *
   * @param name 服务端登记的工具名称
   * @param description 服务端登记的工具用途
   */
  public record ToolOption(String name, String description) {}

  /**
   * 创建意图节点；绑定范围、树深及启用叶子数量由服务层校验。
   *
   * @param request 待创建节点的管理员输入
   * @return 保存后的节点
   */
  @PostMapping
  public IntentNode create(@RequestBody IntentNodeRequest request) {
    return intentTreeService.create(request);
  }

  /**
   * 更新指定意图节点，并重新校验整棵树的约束。
   *
   * @param id 待更新节点标识
   * @param request 节点新内容
   * @return 保存后的节点
   */
  @PutMapping("/{id}")
  public IntentNode update(@PathVariable UUID id, @RequestBody IntentNodeRequest request) {
    return intentTreeService.update(id, request);
  }

  /**
   * 删除没有子节点的意图节点。
   *
   * @param id 待删除节点标识
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    intentTreeService.delete(id);
  }
}
