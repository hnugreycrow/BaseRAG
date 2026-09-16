package com.hnu.backend.rag.controller;

import com.hnu.backend.auth.service.CurrentUserService;
import com.hnu.backend.rag.answer.RagService;
import com.hnu.backend.rag.dto.QuestionRequest;
import com.hnu.backend.rag.vo.AnswerResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供无会话状态的单轮知识库问答接口。 */
@RestController
@Profile("local")
@RequestMapping("/api/questions")
public class QuestionController {
  private final RagService ragService;
  private final CurrentUserService currentUserService;

  /**
   * 创建单轮问答控制器。
   *
   * @param ragService 单轮 RAG 服务
   * @param currentUserService 当前用户解析服务
   */
  public QuestionController(RagService ragService, CurrentUserService currentUserService) {
    this.ragService = ragService;
    this.currentUserService = currentUserService;
  }

  /**
   * 检索管理员创建的公共知识库并生成带来源引用的回答。
   *
   * @param request 问题与可选知识库范围
   * @return 回答、引用和来源
   */
  @PostMapping
  public AnswerResponse ask(@Valid @RequestBody QuestionRequest request) {
    return ragService.ask(
        currentUserService.require().getId(), request.question(), request.knowledgeBaseIds());
  }
}
