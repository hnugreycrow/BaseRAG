package com.hnu.backend.conversation.controller;

import com.hnu.backend.conversation.dto.ActionRequest;
import com.hnu.backend.conversation.dto.MessageRequest;
import com.hnu.backend.conversation.dto.TitleRequest;
import com.hnu.backend.conversation.service.ConversationService;
import com.hnu.backend.conversation.vo.ConversationResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 提供会话及回答生成相关的 REST 接口，具体业务规则由 {@link ConversationService} 处理。 */
@RestController
@Profile("local")
@RequestMapping("/api/conversations")
public class ConversationController {
  private final ConversationService service;

  public ConversationController(ConversationService service) {
    this.service = service;
  }

  /** 创建会话。 */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ConversationResponses.Summary create(@Valid @RequestBody TitleRequest request) {
    return service.create(request.title());
  }

  /** 按标题关键字查询会话；返回数量最多为服务层允许的上限。 */
  @GetMapping
  public List<ConversationResponses.Summary> list(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return service.list(q, limit);
  }

  /** 获取会话详情及各轮回答版本。 */
  @GetMapping("/{id}")
  public ConversationResponses.Detail get(@PathVariable UUID id) {
    return service.get(id);
  }

  /** 修改会话标题。 */
  @PatchMapping("/{id}")
  public ConversationResponses.Summary rename(
      @PathVariable UUID id, @Valid @RequestBody TitleRequest request) {
    return service.rename(id, request.title());
  }

  /** 删除没有正在生成回答的会话。 */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }

  /** 提交用户问题，并通过 SSE 持续返回生成事件。 */
  @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter ask(
      @PathVariable UUID id, @Valid @RequestBody MessageRequest request, HttpServletRequest http) {
    return service.ask(id, request.clientMessageId(), request.content(), requestId(http));
  }

  /** 重试失败或已取消的回答。 */
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

  /** 为最后一轮已完成回答创建新的回答版本。 */
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

  /** 取消指定的生成任务；任务已结束时保持幂等。 */
  @PostMapping("/{id}/generations/{generationId}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable UUID id, @PathVariable UUID generationId) {
    service.cancel(id, generationId);
  }

  private String requestId(HttpServletRequest request) {
    return String.valueOf(request.getAttribute("requestId"));
  }
}
