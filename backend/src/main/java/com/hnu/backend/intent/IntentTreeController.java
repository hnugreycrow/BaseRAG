package com.hnu.backend.intent;

import cn.dev33.satoken.annotation.SaCheckRole;
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

  public IntentTreeController(IntentTreeService intentTreeService, McpToolRegistry tools) {
    this.intentTreeService = intentTreeService;
    this.tools = tools;
  }

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

  public record ToolOption(String name, String description) {}

  @PostMapping
  public IntentNode create(@RequestBody IntentNodeRequest request) {
    return intentTreeService.create(request);
  }

  @PutMapping("/{id}")
  public IntentNode update(@PathVariable UUID id, @RequestBody IntentNodeRequest request) {
    return intentTreeService.update(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    intentTreeService.delete(id);
  }
}
