package com.hnu.backend.application.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hnu.backend.document.api.DocumentCleanup;
import com.hnu.backend.intent.event.IntentTreeChangedEvent;
import com.hnu.backend.intent.service.IntentTreeService;
import com.hnu.backend.intent.snapshot.IntentTreeSnapshotProvider;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseRemoval;
import com.hnu.backend.rag.mcp.McpToolRegistry;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeBaseDeletionServiceTest {
  private final UUID id = UUID.randomUUID();
  private final KnowledgeBaseAccess access = mock(KnowledgeBaseAccess.class);
  private final KnowledgeBaseRemoval knowledgeBases = mock(KnowledgeBaseRemoval.class);
  private final DocumentCleanup documents = mock(DocumentCleanup.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final Connection connection = mock(Connection.class);
  private TransactionTemplate transactions;
  private KnowledgeBaseDeletionService service;

  @BeforeEach
  void setUp() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    service =
        new KnowledgeBaseDeletionService(access, knowledgeBases, documents, transactions, events);
    when(documents.storageKeys(id)).thenReturn(List.of("first", "second"));
  }

  @Test
  void deletesRecordsInOneTransactionAndFilesOnlyAfterCommit() throws SQLException {
    doAnswer(
            ignored -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(knowledgeBases)
        .deleteRecord(id);
    doAnswer(
            ignored -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return null;
            })
        .when(events)
        .publishEvent(any(IntentTreeChangedEvent.class));

    service.delete(id);

    var order = inOrder(access, documents, knowledgeBases, events, connection);
    order.verify(access).requireManagedOwner(id);
    order.verify(documents).storageKeys(id);
    order.verify(documents).deleteRecords(id);
    order.verify(knowledgeBases).deleteRecord(id);
    order.verify(events).publishEvent(any(IntentTreeChangedEvent.class));
    order.verify(connection).commit();
    order.verify(documents).removeStoredFiles(List.of("first", "second"));
    verify(connection, never()).rollback();
  }

  @Test
  void invisibleKnowledgeBaseDoesNotTouchDocumentsOrStartTransaction() throws SQLException {
    when(access.requireManagedOwner(id))
        .thenThrow(new ApiException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    assertThrows(ApiException.class, () -> service.delete(id));
    verifyNoInteractions(documents, knowledgeBases, events);
    verify(connection, never()).setAutoCommit(false);
  }

  @Test
  void processingDocumentRollsBackWithoutDeletingLibraryOrFiles() throws SQLException {
    doThrow(ApiException.conflict(ErrorCode.DOCUMENT_PROCESSING, "文档仍在处理中"))
        .when(documents)
        .deleteRecords(id);
    assertEquals(
        "DOCUMENT_PROCESSING", assertThrows(ApiException.class, () -> service.delete(id)).code());
    verify(connection).rollback();
    verify(connection, never()).commit();
    verifyNoInteractions(knowledgeBases, events);
    verify(documents, never()).removeStoredFiles(anyList());
  }

  @Test
  void databaseFailureAfterDocumentDeletionRollsBackAndKeepsFiles() throws SQLException {
    doThrow(new IllegalStateException("database failed")).when(knowledgeBases).deleteRecord(id);
    assertThrows(IllegalStateException.class, () -> service.delete(id));
    verify(documents).deleteRecords(id);
    verify(connection).rollback();
    verify(connection, never()).commit();
    verifyNoInteractions(events);
    verify(documents, never()).removeStoredFiles(anyList());
  }

  @Test
  void joinedTransactionDefersFileCleanupUntilOuterCommit() throws SQLException {
    transactions.executeWithoutResult(
        status -> {
          service.delete(id);
          verify(documents, never()).removeStoredFiles(anyList());
        });
    var order = inOrder(connection, documents);
    order.verify(connection).commit();
    order.verify(documents).removeStoredFiles(List.of("first", "second"));
  }

  @Test
  void realIntentCacheListenerInvalidatesOnlyAfterActualCommit() {
    IntentTreeService tree = mock(IntentTreeService.class);
    McpToolRegistry tools = mock(McpToolRegistry.class);
    when(tree.list()).thenReturn(List.of());
    when(tools.availableReadOnlyTools()).thenReturn(List.of());
    IntentTreeSnapshotProvider snapshots = new IntentTreeSnapshotProvider(tree, tools);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(TransactionalEventListenerFactory.class);
      context.registerBean(IntentTreeSnapshotProvider.class, () -> snapshots);
      context.refresh();
      var original = snapshots.snapshot();
      var deletion =
          new KnowledgeBaseDeletionService(
              access, knowledgeBases, documents, transactions, context);

      transactions.executeWithoutResult(
          status -> {
            deletion.delete(id);
            assertSame(original, snapshots.snapshot());
            status.setRollbackOnly();
          });
      assertSame(original, snapshots.snapshot());
      transactions.executeWithoutResult(
          status -> {
            deletion.delete(id);
            assertSame(original, snapshots.snapshot());
          });
      assertNotSame(original, snapshots.snapshot());
      verify(tree, times(2)).list();
    }
  }

  @Test
  void outerRollbackDiscardsRegisteredFileCleanup() throws SQLException {
    transactions.executeWithoutResult(
        status -> {
          service.delete(id);
          status.setRollbackOnly();
        });
    verify(connection).rollback();
    verify(connection, never()).commit();
    verify(documents, never()).removeStoredFiles(anyList());
  }
}
