package com.hnu.backend.document.service;

import com.hnu.backend.conversation.entity.MessageStatus;
import com.hnu.backend.conversation.service.ConversationService;
import com.hnu.backend.conversation.vo.ConversationResponses;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.rag.vo.SourceResponse;
import com.hnu.backend.shared.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 仅允许用户读取本人已完成回答中实际引用的文档原文件。 */
@Service
public class CitedDocumentService {
  private final ConversationService conversationService;
  private final KnowledgeBaseService knowledgeBaseService;
  private final DocumentService documentService;

  /** 创建引用原文件授权服务。 */
  public CitedDocumentService(
      ConversationService conversationService,
      KnowledgeBaseService knowledgeBaseService,
      DocumentService documentService) {
    this.conversationService = conversationService;
    this.knowledgeBaseService = knowledgeBaseService;
    this.documentService = documentService;
  }

  /**
   * 在会话、消息和引用三级校验通过后读取不可变的文档版本。
   *
   * @param userId 当前登录用户
   * @param conversationId 本人的会话
   * @param messageId 助手回答标识
   * @param citationId 回答实际使用的引用编号
   * @return 引用对应版本的原文件
   */
  public DocumentService.OriginalFile originalFile(
      UUID userId, UUID conversationId, UUID messageId, String citationId) {
    ConversationResponses.Detail conversation = conversationService.get(userId, conversationId);
    ConversationResponses.AssistantMessage message =
        conversation.turns().stream()
            .flatMap(turn -> turn.assistantVersions().stream())
            .filter(candidate -> candidate.id().equals(messageId))
            .findFirst()
            .orElseThrow(CitedDocumentService::notFound);
    if (!MessageStatus.COMPLETED.name().equals(message.status())
        || !message.citations().contains(citationId)) {
      throw notFound();
    }
    SourceResponse source =
        message.sources().stream()
            .filter(candidate -> candidate.citationId().equals(citationId))
            .findFirst()
            .orElseThrow(CitedDocumentService::notFound);
    KnowledgeBase knowledgeBase = knowledgeBaseService.requireAdminOwned(source.knowledgeBaseId());
    return documentService.originalFile(
        knowledgeBase.getOwnerId(),
        source.knowledgeBaseId(),
        source.documentId(),
        source.versionId());
  }

  /** 对未授权引用使用统一的不存在响应，避免泄露文档标识。 */
  private static ApiException notFound() {
    return ApiException.notFound("CITED_SOURCE_NOT_FOUND", "引用来源不存在");
  }
}
