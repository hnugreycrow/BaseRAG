package com.hnu.backend.question.api;

import com.hnu.backend.question.application.QuestionService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/questions")
public class QuestionController {
  private final QuestionService questions;

  public QuestionController(QuestionService questions) {
    this.questions = questions;
  }

  @PostMapping
  public AnswerResponse ask(@Valid @RequestBody QuestionRequest request) {
    return questions.ask(request.question());
  }
}
