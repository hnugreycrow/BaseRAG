package com.hnu.backend.document.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.persistence.UuidTypeHandler;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.entity.DocumentVersionStatus;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.rag.config.RagProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentServicePreviewTest {
  private final UUID owner = UUID.randomUUID();
  private final UUID kbId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();
  private final KnowledgeBaseAccess knowledgeBaseService = mock(KnowledgeBaseAccess.class);
  private final DocumentMapper documentMapper = mock(DocumentMapper.class);
  private final DocumentVersionMapper versionMapper = mock(DocumentVersionMapper.class);
  private final DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
  private final FileStorage storage = mock(FileStorage.class);
  private final DocumentService service =
      new DocumentService(
          knowledgeBaseService,
          documentMapper,
          versionMapper,
          chunkMapper,
          new MarkdownChunker(new RagProperties()),
          mock(EmbeddingClient.class),
          storage,
          mock(TransactionTemplate.class),
          new com.hnu.backend.document.config.DocumentProcessingProperties());

  @BeforeAll
  static void initializeTableMetadata() {
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(configuration, ""), DocumentVersion.class);
  }

  @Test
  void returnsBomFreeMarkdownAndCurrentOriginal() {
    byte[] bytes = "\uFEFF# 标题\n\n正文".getBytes(StandardCharsets.UTF_8);
    DocumentVersion version =
        arrange("guide.md", "MARKDOWN", "text/markdown; charset=utf-8", bytes);

    var preview = service.preview(owner, kbId, documentId);

    assertEquals("guide.md", preview.name());
    assertEquals("MARKDOWN", preview.format());
    assertEquals("# 标题\n\n正文", preview.content());
    assertTrue(preview.blocks().isEmpty());
    assertArrayEquals(bytes, service.originalFile(owner, kbId, documentId).bytes());
    verify(storage, times(2)).get(version.getStorageKey());
  }

  @Test
  void returnsPdfMetadataWithoutParsingContent() {
    byte[] bytes = "%PDF-preview".getBytes(StandardCharsets.US_ASCII);
    arrange("guide.pdf", "PDF", "application/pdf", bytes);

    var preview = service.preview(owner, kbId, documentId);

    assertEquals("PDF", preview.format());
    assertNull(preview.content());
    assertTrue(preview.blocks().isEmpty());
    assertEquals(bytes.length, preview.fileSizeBytes());
    verify(storage, never()).get(any());
  }

  @Test
  void returnsStructuredDocxBlocksInDocumentOrder() throws IOException {
    byte[] bytes;
    try (XWPFDocument docx = new XWPFDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var heading = docx.createParagraph();
      heading.setStyle("Heading1");
      heading.createRun().setText("制度标题");
      docx.createParagraph().createRun().setText("第一段正文");
      docx.write(output);
      bytes = output.toByteArray();
    }
    arrange(
        "guide.docx",
        "DOCX",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        bytes);

    var preview = service.preview(owner, kbId, documentId);

    assertEquals("DOCX", preview.format());
    assertNull(preview.content());
    assertEquals(2, preview.blocks().size());
    assertEquals("HEADING", preview.blocks().get(0).kind());
    assertEquals(1, preview.blocks().get(0).level());
    assertEquals("制度标题", preview.blocks().get(0).content());
    assertEquals("TEXT", preview.blocks().get(1).kind());
    assertEquals("PARAGRAPH", preview.blocks().get(1).sourceUnit());
  }

  @Test
  void rejectsDocumentFromAnotherKnowledgeBaseBeforeReadingStorage() {
    Document document = new Document();
    document.setId(documentId);
    document.setKnowledgeBaseId(UUID.randomUUID());
    when(documentMapper.selectById(documentId)).thenReturn(document);

    ApiException error =
        assertThrows(ApiException.class, () -> service.preview(owner, kbId, documentId));

    assertEquals("DOCUMENT_NOT_FOUND", error.code());
    verify(storage, never()).get(any());
  }

  private DocumentVersion arrange(String name, String format, String mediaType, byte[] bytes) {
    Document document = new Document();
    document.setId(documentId);
    document.setKnowledgeBaseId(kbId);
    document.setName(name);
    when(documentMapper.selectById(documentId)).thenReturn(document);

    DocumentVersion version = new DocumentVersion();
    version.setId(UUID.randomUUID());
    version.setDocumentId(documentId);
    version.setKnowledgeBaseId(kbId);
    version.setStorageKey("documents/" + documentId);
    version.setFormat(format);
    version.setMediaType(mediaType);
    version.setFileSizeBytes((long) bytes.length);
    version.setStatus(DocumentVersionStatus.UPLOADED);
    when(versionMapper.selectOne(any())).thenReturn(version);
    when(storage.get(version.getStorageKey())).thenReturn(bytes);
    return version;
  }
}
