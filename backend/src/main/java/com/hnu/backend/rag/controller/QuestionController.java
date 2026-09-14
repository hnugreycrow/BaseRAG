package com.hnu.backend.rag.controller;

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

  public QuestionController(RagService rag) {
    this.rag = rag;
  }

  /** 检索知识库并生成带来源引用的回答。 */
  @PostMapping
  public AnswerResponse ask(@Valid @RequestBody QuestionRequest request) {
    return rag.ask(request.question(), request.knowledgeBaseIds());
  }
}
