package com.hnu.backend.application;

import com.hnu.backend.document.api.DocumentCleanup;
import com.hnu.backend.intent.event.IntentTreeChangedEvent;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseRemoval;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 编排知识库与文档的原子删除，以及提交后的缓存失效和文件清理。 */
@Service
public class KnowledgeBaseDeletionService {
  private final KnowledgeBaseAccess access;
  private final KnowledgeBaseRemoval knowledgeBases;
  private final DocumentCleanup documents;
  private final TransactionTemplate transactions;
  private final ApplicationEventPublisher events;

  /**
   * 创建跨模块删除编排服务。
   *
   * @param access 知识库访问校验接口
   * @param knowledgeBases 知识库记录删除接口
   * @param documents 文档清理接口
   * @param transactions 数据库事务模板
   * @param events 意图树缓存失效事件发布器
   */
  public KnowledgeBaseDeletionService(
      KnowledgeBaseAccess access,
      KnowledgeBaseRemoval knowledgeBases,
      DocumentCleanup documents,
      TransactionTemplate transactions,
      ApplicationEventPublisher events) {
    this.access = access;
    this.knowledgeBases = knowledgeBases;
    this.documents = documents;
    this.transactions = transactions;
    this.events = events;
  }

  /**
   * 删除公共知识库及文档，在实际提交后清理文件。
   *
   * <p>加入外层事务时也等待外层提交；任何数据库删除失败都会回滚，不删除原文件。
   *
   * @param id 非空知识库标识
   * @throws com.hnu.backend.common.exception.ApiException 知识库不可见或文档仍在处理中时抛出
   */
  public void delete(UUID id) {
    access.requireManagedOwner(id);
    transactions.executeWithoutResult(
        status -> {
          List<String> keys = List.copyOf(documents.storageKeys(id));
          documents.deleteRecords(id);
          knowledgeBases.deleteRecord(id);
          // 事件在事务内发布，让现有 AFTER_COMMIT 监听器在实际提交后失效缓存。
          events.publishEvent(new IntentTreeChangedEvent());
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  documents.removeStoredFiles(keys);
                }
              });
        });
  }
}
