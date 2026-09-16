package com.hnu.backend.document.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.document.entity.Document;
import com.hnu.backend.document.entity.DocumentChunk;
import com.hnu.backend.document.entity.DocumentVersion;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.document.parser.MarkdownChunker;
import com.hnu.backend.document.storage.FileStorage;
import com.hnu.backend.knowledgebase.entity.KnowledgeBase;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.model.client.EmbeddingClient;
import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.persistence.UuidTypeHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class DocumentServiceParsingTest {
  private final UUID owner = UUID.randomUUID();
  private final UUID kbId = UUID.randomUUID();
  private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
  private final DocumentMapper documentMapper = mock(DocumentMapper.class);
  private final DocumentVersionMapper documentVersionMapper = mock(DocumentVersionMapper.class);
  private final DocumentChunkMapper documentChunkMapper = mock(DocumentChunkMapper.class);
  private final EmbeddingClient embedding = mock(EmbeddingClient.class);
  private final FileStorage storage = mock(FileStorage.class);
  private final TransactionTemplate tx = mock(TransactionTemplate.class);

  @Test
  void pdfUploadThenManualChunkStoresPageLocation() throws IOException {
    byte[] bytes;
    try (PDDocument pdf = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      pdf.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.showText("Policy document");
        content.endText();
      }
      pdf.save(output);
      bytes = output.toByteArray();
    }
    checkUploadAndChunk("policy.pdf", bytes, "application/pdf", "PDF", "PAGE");
  }

  @Test
  void docxUploadThenManualChunkStoresParagraphLocation() throws IOException {
    byte[] bytes;
    try (XWPFDocument docx = new XWPFDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      docx.createParagraph().createRun().setText("中文制度正文");
      docx.write(output);
      bytes = output.toByteArray();
    }
    checkUploadAndChunk(
        "policy.docx",
        bytes,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "DOCX",
        "PARAGRAPH");
  }

  @Test
  void invalidPdfIsRejectedBeforeObjectStorage() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setEmbeddingModelId("model-id");
    kb.setEmbeddingProvider("local");
    kb.setEmbeddingModel("embedding");
    kb.setEmbeddingDimensions(2);
    when(knowledgeBaseService.ensureModel(owner, kbId)).thenReturn(kb);
    var service =
        new DocumentService(
            knowledgeBaseService,
            documentMapper,
            documentVersionMapper,
            documentChunkMapper,
            new MarkdownChunker(new RagProperties()),
            embedding,
            storage,
            tx);
    var invalid =
        new MockMultipartFile(
            "file", "fake.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "INVALID_FILE_FORMAT",
        assertThrows(ApiException.class, () -> service.upload(owner, kbId, invalid)).code());
    verifyNoInteractions(storage, documentMapper, documentVersionMapper);
  }

  private void checkUploadAndChunk(
      String filename, byte[] bytes, String mediaType, String format, String unit) {
    var configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
    var assistant = new MapperBuilderAssistant(configuration, "");
    TableInfoHelper.initTableInfo(assistant, DocumentVersion.class);
    TableInfoHelper.initTableInfo(assistant, DocumentChunk.class);
    KnowledgeBase kb = new KnowledgeBase();
    kb.setEmbeddingModelId("model-id");
    kb.setEmbeddingProvider("local");
    kb.setEmbeddingModel("embedding");
    kb.setEmbeddingDimensions(2);
    when(knowledgeBaseService.ensureModel(owner, kbId)).thenReturn(kb);
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> callback = invocation.getArgument(0);
              callback.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(tx)
        .executeWithoutResult(any());
    when(documentVersionMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);
    when(embedding.embed(eq("model-id"), eq("local"), eq("embedding"), eq(2), anyList()))
        .thenAnswer(
            invocation -> {
              List<?> texts = invocation.getArgument(4);
              return texts.stream().map(text -> new float[] {1f, 0f}).toList();
            });
    var service =
        new DocumentService(
            knowledgeBaseService,
            documentMapper,
            documentVersionMapper,
            documentChunkMapper,
            new MarkdownChunker(new RagProperties()),
            embedding,
            storage,
            tx);

    var uploaded =
        service.upload(
            owner,
            kbId,
            new MockMultipartFile("file", filename, "application/octet-stream", bytes));
    assertEquals("UPLOADED", uploaded.status());
    var documentCapture = ArgumentCaptor.forClass(Document.class);
    var versionCapture = ArgumentCaptor.forClass(DocumentVersion.class);
    verify(documentMapper).insert(documentCapture.capture());
    verify(documentVersionMapper).insert(versionCapture.capture());
    Document document = documentCapture.getValue();
    DocumentVersion version = versionCapture.getValue();
    assertEquals(format, version.getFormat());
    assertEquals(mediaType, version.getMediaType());
    assertEquals(bytes.length, version.getFileSizeBytes());
    assertTrue(version.getStorageKey().endsWith(format.equals("PDF") ? ".pdf" : ".docx"));
    verify(storage).put(version.getStorageKey(), bytes, mediaType);

    when(documentMapper.selectById(document.getId())).thenReturn(document);
    when(documentVersionMapper.selectOne(any())).thenReturn(version);
    when(storage.get(version.getStorageKey())).thenReturn(bytes);
    var ready = service.createChunks(owner, kbId, document.getId());
    assertEquals("READY", ready.status());
    assertEquals(version.getId(), document.getActiveVersionId());
    var chunkCapture = ArgumentCaptor.forClass(DocumentChunk.class);
    verify(documentChunkMapper, atLeastOnce()).insertVector(chunkCapture.capture());
    assertTrue(
        chunkCapture.getAllValues().stream()
            .allMatch(
                chunk ->
                    unit.equals(chunk.getSourceUnit())
                        && chunk.getSourceStart() != null
                        && chunk.getSourceStart() > 0
                        && chunk.getLineStart() == null));
  }
}
