package com.hnu.backend.intent;

import com.hnu.backend.auth.service.CurrentUserService;
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
public class IntentTreeController {
  private final IntentTreeService tree;
  private final CurrentUserService users;
  private final McpToolRegistry tools;

  public IntentTreeController(
      IntentTreeService tree, CurrentUserService users, McpToolRegistry tools) {
    this.tree = tree;
    this.users = users;
    this.tools = tools;
  }

  @GetMapping
  public List<IntentNode> list() {
    users.requireAdmin();
    return tree.list();
  }

  /** 返回当前可供管理员绑定的只读工具。 */
  @GetMapping("/tools")
  public List<ToolOption> tools() {
    users.requireAdmin();
    return tools.availableReadOnlyTools().stream()
        .map(tool -> new ToolOption(tool.name(), tool.description()))
        .toList();
  }

  public record ToolOption(String name, String description) {}

  @PostMapping
  public IntentNode create(@RequestBody IntentNodeRequest request) {
    users.requireAdmin();
    return tree.create(request);
  }

  @PutMapping("/{id}")
  public IntentNode update(@PathVariable UUID id, @RequestBody IntentNodeRequest request) {
    users.requireAdmin();
    return tree.update(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    users.requireAdmin();
    tree.delete(id);
  }
}
