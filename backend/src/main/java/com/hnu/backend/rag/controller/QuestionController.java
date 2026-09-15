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
  private final RagService rag;
  private final CurrentUserService currentUsers;

  /**
   * 创建单轮问答控制器。
   *
   * @param rag 单轮 RAG 服务
   * @param currentUsers 当前用户解析服务
   */
  public QuestionController(RagService rag, CurrentUserService currentUsers) {
    this.rag = rag;
    this.currentUsers = currentUsers;
  }

  /**
   * 只检索当前用户的知识库并生成带来源引用的回答。
   *
   * @param request 问题与可选知识库范围
   * @return 回答、引用和来源
   */
  @PostMapping
  public AnswerResponse ask(@Valid @RequestBody QuestionRequest request) {
    return rag.ask(currentUsers.require().getId(), request.question(), request.knowledgeBaseIds());
  }
}
