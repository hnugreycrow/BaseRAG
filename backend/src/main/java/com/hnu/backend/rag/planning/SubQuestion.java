package com.hnu.backend.rag.planning;

import java.util.Objects;

public record SubQuestion(String id, String question) {
  public SubQuestion {
    id = Objects.requireNonNull(id, "id");
    question = Objects.requireNonNull(question, "question");
  }
}
