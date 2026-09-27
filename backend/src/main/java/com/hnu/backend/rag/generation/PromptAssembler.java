package com.hnu.backend.rag.generation;

import com.hnu.backend.rag.memory.RagMemory;
import com.hnu.backend.rag.pipeline.ExecutionResult;
import com.hnu.backend.rag.pipeline.QueryPlan;
import com.hnu.backend.rag.pipeline.RoutingPlan;
import com.hnu.backend.rag.retrieval.EvidenceCandidate;
import java.util.List;

/** 将已选证据、记忆和工具观察组装为不可变提示词快照。 */
public interface PromptAssembler extends CitationRepairPrompt {
  /**
   * 组装完整流水线的知识、工具和闲聊混合输入。
   *
   * @param memory 本轮加载的会话记忆
   * @param originalQuestion 用户未经改写的原始问题
   * @param plan 经过校验的问题规划
   * @param routing 经过服务端安全归一化的路由计划
   * @param execution 子问题执行结果和本轮冻结预算
   * @param selectedCandidates 重排阶段最终选中的知识证据
   * @return 可直接传给回答模型的提示词快照
   */
  AssembledPrompt assemblePipeline(
      RagMemory memory,
      String originalQuestion,
      QueryPlan plan,
      RoutingPlan routing,
      ExecutionResult execution,
      List<EvidenceCandidate> selectedCandidates);

  /**
   * 组装全部子问题都被路由为系统闲聊时的模型输入。
   *
   * @param memory 本轮加载的会话记忆
   * @param originalQuestion 用户未经改写的原始问题
   * @param plan 经过校验的问题规划
   * @param routing 全部为系统闲聊的路由计划
   * @return 不包含虚假证据或工具结果的提示词快照
   */
  AssembledPrompt assembleSystemChat(
      RagMemory memory, String originalQuestion, QueryPlan plan, RoutingPlan routing);

  /**
   * 为旧单轮 RAG 兼容入口组装与新流水线相同结构的模型输入。
   *
   * @param originalQuestion 用户原始问题
   * @param context 旧检索链路已经构造的知识来源
   * @return 与新流水线数据区结构一致的提示词快照
   */
  AssembledPrompt assembleLegacy(String originalQuestion, ContextBuilder.Context context);

  /**
   * 在不引入上一次错误回答的前提下切换为引用修复系统提示词。
   *
   * @param original 首次生成使用的完整提示词快照
   * @return 用户数据和来源完全不变的引用修复快照
   */
  AssembledPrompt forCitationRepair(AssembledPrompt original);
}
