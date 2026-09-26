package com.hnu.backend.document.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentChunk;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.entity.DocumentVersionStatus;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.DocumentParserRegistry;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.rag.configuration.RagProperties;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.persistence.UuidTypeHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentIndexServiceTest {
  private final UUID owner = UUID.randomUUID();
  private final UUID kb = UUID.randomUUID();
  private final Document document = new Document();
  private final DocumentVersion version = new DocumentVersion();
  private final KnowledgeBaseAccess knowledgeBase = mock(KnowledgeBaseAccess.class);
  private final DocumentMapper documents = mock(DocumentMapper.class);
  private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
  private final DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
  private final EmbeddingClient embedding = mock(EmbeddingClient.class);
  private final FileStorage storage = mock(FileStorage.class);
  private final DocumentAccess access = mock(DocumentAccess.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private DocumentIndexService indexer;

  @BeforeEach
  void setUp() {
    Configuration configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
    var assistant = new MapperBuilderAssistant(configuration, "");
    TableInfoHelper.initTableInfo(assistant, DocumentVersion.class);
    TableInfoHelper.initTableInfo(assistant, DocumentChunk.class);
    document.setId(UUID.randomUUID());
    version.setId(UUID.randomUUID());
    version.setDocumentId(document.getId());
    version.setStatus(DocumentVersionStatus.UPLOADED);
    version.setStorageKey("document.md");
    version.setEmbeddingModelId("embedding-id");
    version.setEmbeddingProvider("local");
    version.setEmbeddingModel("embedding");
    version.setEmbeddingDimensions(2);
    when(access.requireDocument(owner, kb, document.getId())).thenReturn(document);
    when(access.latestVersion(document.getId())).thenReturn(version);
    when(versions.update(any())).thenReturn(1);
    when(storage.get("document.md")).thenReturn("# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8));
    when(embedding.embed(eq("embedding-id"), eq("local"), eq("embedding"), eq(2), anyList()))
        .thenReturn(List.of(new float[] {1, 0}));
    // 此处只验证处理流程；模拟事务回调不用于证明数据库回滚行为。
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
    MarkdownChunker chunker = new MarkdownChunker(new RagProperties());
    indexer =
        new DocumentIndexService(
            knowledgeBase,
            documents,
            versions,
            chunks,
            chunker,
            new DocumentParserRegistry(chunker),
            embedding,
            storage,
            tx,
            access);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void synchronousAndClaimedTasksUseTheSameIndexPipeline(boolean claimed) {
    if (claimed) {
      version.setStatus(DocumentVersionStatus.PROCESSING);
    }
    var result = indexer.process(owner, kb, document.getId(), claimed);
    assertEquals("READY", result.status());
    assertEquals(1, result.chunkCount());
    assertEquals(version.getId(), document.getActiveVersionId());
    verify(versions, times(claimed ? 0 : 1)).update(any());
    var order = inOrder(embedding, knowledgeBase, chunks, versions, documents);
    order
        .verify(embedding)
        .embed(eq("embedding-id"), eq("local"), eq("embedding"), eq(2), anyList());
    order.verify(knowledgeBase).lockAndBind(owner, kb, "embedding-id", "local", "embedding", 2);
    order.verify(chunks).delete(any());
    order.verify(chunks).insertVector(any());
    order.verify(versions).updateById(version);
    order.verify(documents).updateById(document);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void embeddingFailurePreservesExistingIndexAndRecordsStatus(boolean rebuilding) {
    if (rebuilding) {
      document.setActiveVersionId(version.getId());
      version.setStatus(DocumentVersionStatus.READY);
    }
    when(embedding.embed(anyString(), anyString(), anyString(), anyInt(), anyList()))
        .thenThrow(new IllegalStateException("model unavailable"));
    assertEquals(
        "IMPORT_FAILED",
        assertThrows(ApiException.class, () -> indexer.process(owner, kb, document.getId(), false))
            .code());
    assertEquals(
        rebuilding ? DocumentVersionStatus.READY : DocumentVersionStatus.FAILED,
        version.getStatus());
    assertEquals("IMPORT_FAILED", version.getErrorCode());
    assertEquals(rebuilding ? version.getId() : null, document.getActiveVersionId());
    verifyNoInteractions(chunks, documents, knowledgeBase);
    verify(versions).updateById(version);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsConflictingVersionBeforeReadingFile(boolean claimed) {
    version.setStatus(claimed ? DocumentVersionStatus.READY : DocumentVersionStatus.PROCESSING);
    assertEquals(
        "DOCUMENT_PROCESSING",
        assertThrows(
                ApiException.class, () -> indexer.process(owner, kb, document.getId(), claimed))
            .code());
    verifyNoInteractions(storage, embedding, chunks, documents, tx);
  }
}
