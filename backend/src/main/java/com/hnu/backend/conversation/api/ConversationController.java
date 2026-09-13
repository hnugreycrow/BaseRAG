package com.hnu.backend.conversation.api;

import com.hnu.backend.conversation.application.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile("local")
@RequestMapping("/api/conversations")
public class ConversationController {
  private final ConversationService service;

  public ConversationController(ConversationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ConversationResponses.Summary create(@Valid @RequestBody TitleRequest request) {
    return service.create(request.title());
  }

  @GetMapping
  public List<ConversationResponses.Summary> list(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return service.list(q, limit);
  }

  @GetMapping("/{id}")
  public ConversationResponses.Detail get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PatchMapping("/{id}")
  public ConversationResponses.Summary rename(
      @PathVariable UUID id, @Valid @RequestBody TitleRequest request) {
    return service.rename(id, request.title());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }

  @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter ask(
      @PathVariable UUID id, @Valid @RequestBody MessageRequest request, HttpServletRequest http) {
    return service.ask(id, request.clientMessageId(), request.content(), requestId(http));
  }

  @PostMapping(
      value = "/{id}/messages/{assistantMessageId}/retry",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter retry(
      @PathVariable UUID id,
      @PathVariable UUID assistantMessageId,
      @Valid @RequestBody ActionRequest request,
      HttpServletRequest http) {
    return service.retry(id, assistantMessageId, request.clientRequestId(), requestId(http));
  }

  @PostMapping(
      value = "/{id}/messages/{assistantMessageId}/regenerate",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter regenerate(
      @PathVariable UUID id,
      @PathVariable UUID assistantMessageId,
      @Valid @RequestBody ActionRequest request,
      HttpServletRequest http) {
    return service.regenerate(id, assistantMessageId, request.clientRequestId(), requestId(http));
  }

  @PostMapping("/{id}/generations/{generationId}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable UUID id, @PathVariable UUID generationId) {
    service.cancel(id, generationId);
  }

  private String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute("requestId"));
  }

  public record TitleRequest(@NotBlank @Size(max = 200) String title) {}

  public record MessageRequest(
      @NotNull UUID clientMessageId, @NotBlank @Size(max = 2000) String content) {}

  public record ActionRequest(@NotNull UUID clientRequestId) {}
}
