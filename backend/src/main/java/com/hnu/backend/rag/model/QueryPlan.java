package com.hnu.backend.rag.model;

import java.util.List;
import java.util.Objects;

public record QueryPlan(String standaloneQuestion, List<SubQuestion> subQuestions) {
  public QueryPlan {
    standaloneQuestion = Objects.requireNonNull(standaloneQuestion, "standaloneQuestion");
    subQuestions = List.copyOf(subQuestions);
  }

  public static QueryPlan fallback(String originalQuestion) {
    String question = Objects.requireNonNull(originalQuestion, "originalQuestion").strip();
    return new QueryPlan(question, List.of(new SubQuestion("Q1", question)));
  }
}
