package com.hnu.backend.rag.port;

import com.hnu.backend.rag.model.McpToolDefinition;
import com.hnu.backend.rag.model.QueryPlan;
import java.util.List;

/** 将结构化问题计划分类为模型建议路由的中立端口。 */
public interface IntentClassifier {
  ClassificationOutput classify(QueryPlan plan, List<McpToolDefinition> availableTools);

  record ClassificationOutput(String content, String modelId, String provider, String model) {}
}
