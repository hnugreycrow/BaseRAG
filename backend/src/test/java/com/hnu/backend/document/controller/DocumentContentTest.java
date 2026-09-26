package com.hnu.backend.document.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hnu.backend.common.web.ApiResponseAdvice;
import com.hnu.backend.document.service.DocumentService;
import com.hnu.backend.knowledgebase.api.KnowledgeBaseAccess;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentContentTest {
  private final DocumentService documentService = mock(DocumentService.class);
  private final KnowledgeBaseAccess knowledgeBaseService = mock(KnowledgeBaseAccess.class);
  private final DocumentController controller =
      new DocumentController(documentService, knowledgeBaseService);
  private final UUID owner = UUID.randomUUID();
  private final UUID kb = UUID.randomUUID();
  private final UUID document = UUID.randomUUID();
  private final UUID version = UUID.randomUUID();

  @Test
  void mvcReturnsRawPdfRangeWithoutJsonEnvelope() throws Exception {
    when(knowledgeBaseService.requireManagedOwner(kb)).thenReturn(owner);
    when(documentService.originalFile(owner, kb, document, version))
        .thenReturn(
            new DocumentService.OriginalFile(
                "policy.pdf", "application/pdf", "0123456789".getBytes(StandardCharsets.US_ASCII)));
    var mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseAdvice())
            .build();
    mvc.perform(
            get(
                    "/api/knowledge-bases/{kb}/documents/{document}/versions/{version}/content",
                    kb,
                    document,
                    version)
                .header(HttpHeaders.RANGE, "bytes=3-5"))
        .andExpect(status().isPartialContent())
        .andExpect(
            result ->
                assertArrayEquals(
                    "345".getBytes(StandardCharsets.US_ASCII),
                    result.getResponse().getContentAsByteArray()));
  }

  @Test
  void servesPdfAndSingleByteRanges() {
    when(knowledgeBaseService.requireManagedOwner(kb)).thenReturn(owner);
    when(documentService.originalFile(owner, kb, document, version))
        .thenReturn(
            new DocumentService.OriginalFile(
                "手册.pdf", "application/pdf", "0123456789".getBytes(StandardCharsets.US_ASCII)));

    var full = controller.content(kb, document, version, null);
    assertEquals(HttpStatus.OK, full.getStatusCode());
    byte[] fullBody = full.getBody();
    assertNotNull(fullBody);
    assertEquals(10, fullBody.length);
    String disposition = full.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    assertNotNull(disposition);
    assertTrue(disposition.startsWith("inline;"));
    assertEquals("bytes", full.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));

    var partial = controller.content(kb, document, version, "bytes=3-5");
    assertEquals(HttpStatus.PARTIAL_CONTENT, partial.getStatusCode());
    byte[] partialBody = partial.getBody();
    assertNotNull(partialBody);
    assertEquals("345", new String(partialBody, StandardCharsets.US_ASCII));
    assertEquals("bytes 3-5/10", partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));

    var suffix = controller.content(kb, document, version, "bytes=-2");
    byte[] suffixBody = suffix.getBody();
    assertNotNull(suffixBody);
    assertEquals("89", new String(suffixBody, StandardCharsets.US_ASCII));
    var invalid = controller.content(kb, document, version, "bytes=20-");
    assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, invalid.getStatusCode());
  }

  @Test
  void currentContentResolvesTheDocumentWithoutAClientVersionId() {
    when(knowledgeBaseService.requireManagedOwner(kb)).thenReturn(owner);
    when(documentService.originalFile(owner, kb, document))
        .thenReturn(
            new DocumentService.OriginalFile(
                "手册.pdf", "application/pdf", "0123456789".getBytes(StandardCharsets.US_ASCII)));

    var response = controller.currentContent(kb, document, "bytes=0-3");

    assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
    byte[] body = response.getBody();
    assertNotNull(body);
    assertEquals("0123", new String(body, StandardCharsets.US_ASCII));
    verify(documentService).originalFile(owner, kb, document);
  }
}
