package com.hnu.backend.knowledgebase.api;

import com.hnu.backend.knowledgebase.application.KnowledgeBaseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
  private final KnowledgeBaseService knowledgeBases;

  public KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
    this.knowledgeBases = knowledgeBases;
  }

  @GetMapping
  public List<KnowledgeBaseResponse> list() {
    return knowledgeBases.list();
  }

  @GetMapping("/embedding-models")
  public List<EmbeddingModelResponse> embeddingModels() {
    return knowledgeBases.embeddingModels();
  }

  @GetMapping("/{id}")
  public KnowledgeBaseResponse get(@PathVariable UUID id) {
    return knowledgeBases.get(id);
  }

  @PostMapping
  public KnowledgeBaseResponse create(@Valid @RequestBody KnowledgeBaseRequest request) {
    return knowledgeBases.create(request.name(), request.embeddingModelId());
  }

  @PatchMapping("/{id}")
  public KnowledgeBaseResponse rename(
      @PathVariable UUID id, @Valid @RequestBody KnowledgeBaseRequest request) {
    return knowledgeBases.rename(id, request.name());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    knowledgeBases.delete(id);
  }
}
