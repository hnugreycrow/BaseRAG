package com.hnu.backend.conversation.controller;

import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.conversation.dto.ActionRequest;
import com.hnu.backend.conversation.dto.CreateConversationRequest;
import com.hnu.backend.conversation.dto.MessageRequest;
import com.hnu.backend.conversation.dto.ThinkingRequest;
import com.hnu.backend.conversation.dto.TitleRequest;
import com.hnu.backend.conversation.service.ConversationService;
import com.hnu.backend.conversation.vo.ConversationResponses;
import com.hnu.backend.shared.web.RequestIdFilter;
import com.hnu.backend.shared.web.RequestTiming;
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
  private final ConversationService conversationService;
  private final CurrentUserService currentUserService;

  /**
   * 创建会话控制器。
   *
   * @param conversationService 会话服务
   * @param currentUserService 当前用户解析服务
   */
  public ConversationController(
      ConversationService conversationService, CurrentUserService currentUserService) {
    this.conversationService = conversationService;
    this.currentUserService = currentUserService;
  }

  /** 创建会话。 */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ConversationResponses.Summary create(
      @Valid @RequestBody CreateConversationRequest request) {
    return conversationService.create(
        currentUserService.require().getId(),
        request.title(),
        Boolean.TRUE.equals(request.thinkingEnabled()));
  }

  /** 按标题关键字查询会话；返回数量最多为服务层允许的上限。 */
  @GetMapping
  public List<ConversationResponses.Summary> list(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return conversationService.list(currentUserService.require().getId(), q, limit);
  }

  /** 获取会话详情及各轮回答版本。 */
  @GetMapping("/{id}")
  public ConversationResponses.Detail get(@PathVariable UUID id) {
    return conversationService.get(currentUserService.require().getId(), id);
  }

  /** 修改会话标题。 */
  @PatchMapping("/{id}")
  public ConversationResponses.Summary rename(
      @PathVariable UUID id, @Valid @RequestBody TitleRequest request) {
    return conversationService.rename(currentUserService.require().getId(), id, request.title());
  }

  /** 更新本会话后续回答使用的深度思考选择。 */
  @PatchMapping("/{id}/thinking")
  public ConversationResponses.Summary setThinking(
      @PathVariable UUID id, @Valid @RequestBody ThinkingRequest request) {
    return conversationService.setThinkingEnabled(
        currentUserService.require().getId(), id, request.thinkingEnabled());
  }

  /** 删除没有正在生成回答的会话。 */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    conversationService.delete(currentUserService.require().getId(), id);
  }

  /** 提交用户问题，并通过 SSE 持续返回生成事件。 */
  @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter ask(
      @PathVariable UUID id, @Valid @RequestBody MessageRequest request, HttpServletRequest http) {
    return conversationService.ask(
        currentUserService.require().getId(),
        id,
        request.clientMessageId(),
        request.content(),
        requestTiming(http));
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
    return conversationService.retry(
        currentUserService.require().getId(),
        id,
        assistantMessageId,
        request.clientRequestId(),
        requestTiming(http));
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
    return conversationService.regenerate(
        currentUserService.require().getId(),
        id,
        assistantMessageId,
        request.clientRequestId(),
        requestTiming(http));
  }

  /** 取消指定的生成任务；任务已结束时保持幂等。 */
  @PostMapping("/{id}/generations/{generationId}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(@PathVariable UUID id, @PathVariable UUID generationId) {
    conversationService.cancel(currentUserService.require().getId(), id, generationId);
  }

  /**
   * 读取请求过滤器写入的请求标识与时间起点。
   *
   * @param request HTTP 请求
   * @return 单次请求的追踪标识、墙钟和单调时钟起点
   */
  private RequestTiming requestTiming(HttpServletRequest request) {
    return new RequestTiming(
        String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)),
        (java.time.OffsetDateTime)
            request.getAttribute(RequestIdFilter.REQUEST_STARTED_AT_ATTRIBUTE),
        (long) request.getAttribute(RequestIdFilter.REQUEST_STARTED_NANOS_ATTRIBUTE));
  }
}
