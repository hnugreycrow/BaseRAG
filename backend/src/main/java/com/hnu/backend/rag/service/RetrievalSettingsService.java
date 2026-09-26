package com.hnu.backend.rag.service;

import com.hnu.backend.conversation.configuration.ConversationProperties;
import com.hnu.backend.rag.configuration.RagProperties;
import com.hnu.backend.rag.execution.RagBudgetSnapshot;
import com.hnu.backend.rag.vo.RetrievalSettingsResponse;
import org.springframework.stereotype.Service;

/** 将实际执行预算和会话默认值转换为只读配置摘要。 */
@Service
public class RetrievalSettingsService {
  private final RagProperties rag;
  private final ConversationProperties conversation;

  /**
   * 创建检索配置查询服务。
   *
   * @param rag 当前 RAG 配置
   * @param conversation 当前会话配置
   */
  public RetrievalSettingsService(RagProperties rag, ConversationProperties conversation) {
    this.rag = rag;
    this.conversation = conversation;
  }

  /**
   * 读取当前生效参数，不执行检索或修改已有回答。
   *
   * @return 仅包含页面所需字段的配置快照
   */
  public RetrievalSettingsResponse get() {
    var budget = RagBudgetSnapshot.from(rag);
    var pipeline = rag.getPipeline();
    return new RetrievalSettingsResponse(
        budget.recallBudget(),
        budget.vectorEnabled(),
        budget.channelTimeoutMs(),
        rag.getSearch().getFusion().getStrategy(),
        budget.rrfK(),
        budget.vectorWeight(),
        budget.deduplicationOverlapThreshold(),
        budget.rerankEnabled(),
        budget.rerankInputLimit(),
        budget.selectedEvidenceLimit(),
        budget.maxSubQuestions(),
        pipeline.getPlanning().getRecentTurns(),
        pipeline.getRouting().getConfidenceThreshold(),
        pipeline.getRouting().getTimeoutMs(),
        rag.getMaxQuestionChars(),
        conversation.getRecentTurns(),
        conversation.getSummaryBatchTurns(),
        conversation.getSummaryMaxChars());
  }
}
