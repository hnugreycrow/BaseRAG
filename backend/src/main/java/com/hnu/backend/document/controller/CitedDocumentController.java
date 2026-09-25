package com.hnu.backend.document.controller;

import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.document.service.CitedDocumentService;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 通过本人回答的实际引用提供只读原文件预览。 */
@RestController
@Profile("local")
@RequestMapping("/api/conversations/{conversationId}/messages/{messageId}/sources")
public class CitedDocumentController {
  private final CurrentUserService currentUserService;
  private final CitedDocumentService citedDocumentService;

  /** 创建引用原文件接口。 */
  public CitedDocumentController(
      CurrentUserService currentUserService, CitedDocumentService citedDocumentService) {
    this.currentUserService = currentUserService;
    this.citedDocumentService = citedDocumentService;
  }

  /** 校验引用并返回原文件；PDF 支持浏览器的单段 Range 请求。 */
  @GetMapping("/{citationId}/content")
  public ResponseEntity<@NonNull byte[]> content(
      @PathVariable UUID conversationId,
      @PathVariable UUID messageId,
      @PathVariable String citationId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) @Nullable String range) {
    var file =
        citedDocumentService.originalFile(
            currentUserService.require().getId(), conversationId, messageId, citationId);
    return DocumentController.fileResponse(file, range);
  }
}
