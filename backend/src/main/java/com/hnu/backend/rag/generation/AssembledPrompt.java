package com.hnu.backend.rag.generation;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.List;
import java.util.Objects;

/**
 * 可以直接交给模型客户端的完整提示词快照。
 *
 * @param systemPrompt 只包含服务端可信规则的系统消息
 * @param userPrompt 只包含服务端序列化数据区的用户消息
 * @param sources 实际进入用户消息并允许产生 S 引用的知识来源
 * @param toolReferenceIds 实际进入用户消息的稳定工具编号
 * @param shouldGenerate 是否需要调用回答模型；纯知识路由且没有证据时为 false
 */
public record AssembledPrompt(
    String systemPrompt,
    String userPrompt,
    List<SourceResponse> sources,
    List<String> toolReferenceIds,
    boolean shouldGenerate) {
  /** 保证提示词文本和集合字段不可为空，并冻结集合快照。 */
  public AssembledPrompt {
    systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
    userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
    sources = List.copyOf(sources);
    toolReferenceIds = List.copyOf(toolReferenceIds);
  }
}
