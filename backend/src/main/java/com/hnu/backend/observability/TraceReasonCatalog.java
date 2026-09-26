package com.hnu.backend.observability;

import com.hnu.backend.common.exception.ErrorCode;
import java.util.Arrays;

/** 追踪原因的唯一展示目录；稳定代码不随文案调整而改变，通用错误复用错误码目录。 */
public enum TraceReasonCatalog {
  /** 使用主模型。 */
  PRIMARY("使用主模型"),
  /** 切换至备用模型。 */
  PROVIDER_FALLBACK("切换至备用模型"),
  /** 重新生成以修复引用。 */
  CITATION_REPAIR("重新生成以修复引用"),
  /** 尚未达到摘要生成条件。 */
  SUMMARY_NOT_DUE("尚未达到摘要生成条件"),
  /** 路由输出不符合结构要求，回退到公共知识库检索。 */
  INTENT_TREE_INVALID_OUTPUT("路由输出不符合结构要求，回退到公共知识库检索"),
  /** 意图识别置信度不足，回退到公共知识库检索。 */
  INTENT_TREE_LOW_CONFIDENCE("意图识别置信度不足，回退到公共知识库检索"),
  /** 未配置意图树，使用公共知识库检索。 */
  INTENT_TREE_EMPTY("未配置意图树，使用公共知识库检索"),
  /** 路由超时，回退到公共知识库检索。 */
  INTENT_TREE_TIMEOUT("路由超时，回退到公共知识库检索"),
  /** 路由分类失败，回退到公共知识库检索。 */
  INTENT_TREE_CLASSIFICATION_FAILED("路由分类失败，回退到公共知识库检索"),
  /** 部分子问题路由降级。 */
  INTENT_TREE_PARTIAL_FALLBACK("部分子问题路由降级"),
  /** 系统闲聊无需检索证据。 */
  SYSTEM_CHAT("系统闲聊无需检索证据"),
  /** 子问题路由为系统闲聊。 */
  SYSTEM_CHAT_ROUTED("子问题路由为系统闲聊"),
  /** 未启用重排。 */
  RERANK_DISABLED("未启用重排"),
  /** 无可重排的候选。 */
  NO_RERANK_INPUT("无可重排的候选"),
  /** 证据不足，返回固定回答。 */
  NO_EVIDENCE("证据不足，返回固定回答"),
  /** 未启用向量检索。 */
  VECTOR_DISABLED("未启用向量检索"),
  /** 知识库范围为空。 */
  EMPTY_KNOWLEDGE_SCOPE("知识库范围为空"),
  /** 没有可用的向量模型绑定。 */
  NO_EMBEDDING_BINDINGS("没有可用的向量模型绑定"),
  /** 子问题超过执行预算。 */
  SUBQUESTION_TIMEOUT("子问题超过执行预算"),
  /** 回答包含无效引用。 */
  INVALID_CITATIONS("回答包含无效引用"),
  /** 意图树有效叶节点数量不符合要求，回退到公共知识库检索。 */
  INTENT_TREE_NO_VALID_LEAVES("意图树有效叶节点数量不符合要求，回退到公共知识库检索"),
  /** 未启用工具调用，回退到公共知识库检索。 */
  INTENT_TREE_MCP_DISABLED("未启用工具调用，回退到公共知识库检索"),
  /** 工具不在允许范围内，回退到公共知识库检索。 */
  INTENT_TREE_TOOL_NOT_ALLOWED("工具不在允许范围内，回退到公共知识库检索"),
  /** 工具不是只读操作，回退到公共知识库检索。 */
  INTENT_TREE_TOOL_NOT_READ_ONLY("工具不是只读操作，回退到公共知识库检索"),
  /** 工具参数不符合要求，回退到公共知识库检索。 */
  INTENT_TREE_INVALID_TOOL_ARGUMENTS("工具参数不符合要求，回退到公共知识库检索"),
  /** 摘要输出不符合要求。 */
  SUMMARY_INVALID_OUTPUT("摘要输出不符合要求"),
  /** 摘要生成失败。 */
  SUMMARY_FAILED("摘要生成失败"),
  /** 会话记忆加载失败。 */
  MEMORY_LOAD_FAILED("会话记忆加载失败"),
  /** 模型调用失败。 */
  MODEL_ERROR("模型调用失败"),
  /** 模型请求超时。 */
  MODEL_TIMEOUT("模型请求超时"),
  /** 模型输出为空。 */
  EMPTY_OUTPUT("模型输出为空"),
  /** 模型输出不是有效 JSON。 */
  INVALID_JSON("模型输出不是有效 JSON"),
  /** 模型输出结构不符合要求。 */
  INVALID_SCHEMA("模型输出结构不符合要求"),
  /** 改写后的问题无效。 */
  INVALID_QUESTION("改写后的问题无效"),
  /** 未生成子问题。 */
  EMPTY_SUBQUESTIONS("未生成子问题"),
  /** 子问题数量超过限制。 */
  TOO_MANY_SUBQUESTIONS("子问题数量超过限制"),
  /** 子问题编号无效。 */
  INVALID_SUBQUESTION_ID("子问题编号无效"),
  /** 生成了重复子问题。 */
  DUPLICATE_SUBQUESTION("生成了重复子问题");

  private final String label;

  TraceReasonCatalog(String label) {
    this.label = label;
  }

  /** 返回持久化使用的稳定原因码。 */
  public String code() {
    return name();
  }

  /**
   * 判断代码是否由追踪目录或通用错误目录收录。
   *
   * @param code 原因码，可以为空
   * @return 已知代码返回 true
   */
  public static boolean contains(String code) {
    return Arrays.stream(values()).anyMatch(value -> value.code().equals(code))
        || ErrorCode.fromCode(code).isPresent();
  }

  /**
   * 解析安全说明，仅在明确降级时补充当前阶段的回退行为。
   *
   * @param stage 所属阶段，可以为空
   * @param status 阶段状态，可以为空；不依据代码推断状态
   * @param code 原始代码；空值不生成说明，未知历史码返回通用占位
   * @return 不含异常正文的说明，缺少代码时为空
   */
  public static String label(RagStageName stage, RagStageStatus status, String code) {
    if (code == null || code.isBlank()) {
      return null;
    }
    String description =
        Arrays.stream(values())
            .filter(value -> value.code().equals(code))
            .map(value -> value.label)
            .findFirst()
            .orElseGet(() -> ErrorCode.messageFor(code));
    if (description == null) {
      return "暂无说明";
    }
    if (status == RagStageStatus.DEGRADED && stage != null) {
      return switch (stage) {
        case QUERY_PLANNING -> description + "，使用原始问题继续";
        case MEMORY_SUMMARY -> description + "，保留现有会话记忆继续";
        case RERANK -> description + "，使用回退排序继续";
        default -> description;
      };
    }
    return description;
  }
}
