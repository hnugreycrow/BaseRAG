package com.hnu.backend.rag.routing;

import com.hnu.backend.model.client.ChatClient;
import com.hnu.backend.rag.mcp.McpToolDefinition;
import com.hnu.backend.rag.planning.QueryPlan;
import com.hnu.backend.rag.prompt.IntentRoutingPrompts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 使用现有 Chat 模型生成结构化子问题意图建议。 */
@Component
public class ChatIntentClassifier implements IntentClassifier {
  private final ChatClient chat;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 创建基于通用 Chat 模型的结构化意图分类器。
   *
   * @param chat 模型客户端
   */
  public ChatIntentClassifier(ChatClient chat) {
    this.chat = chat;
  }

  /**
   * 把问题规划和经过安全裁剪的只读工具目录提交给分类模型。
   *
   * @param plan 已校验的问题规划
   * @param availableTools 当前服务端允许模型选择的只读工具
   * @return 未解析的模型路由建议及模型元数据
   */
  @Override
  public ClassificationOutput classify(QueryPlan plan, List<McpToolDefinition> availableTools) {
    List<Map<String, Object>> tools =
        availableTools.stream()
            .map(
                definition -> {
                  Map<String, Object> value = new LinkedHashMap<>();
                  value.put("name", definition.name());
                  value.put("description", definition.description());
                  value.put("inputSchema", definition.inputSchema());
                  return value;
                })
            .toList();
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("standaloneQuestion", plan.standaloneQuestion());
    input.put("subQuestions", plan.subQuestions());
    input.put("availableTools", tools);

    ChatClient.Generation generation =
        chat.generate(IntentRoutingPrompts.system(), json.writeValueAsString(input));
    return new ClassificationOutput(
        generation.content(), generation.id(), generation.provider(), generation.model());
  }
}
