package com.hnu.backend.knowledgebase.controller;

import com.hnu.backend.knowledgebase.dto.KnowledgeBaseRequest;
import com.hnu.backend.knowledgebase.service.KnowledgeBaseService;
import com.hnu.backend.knowledgebase.vo.EmbeddingModelResponse;
import com.hnu.backend.knowledgebase.vo.KnowledgeBaseResponse;
import com.hnu.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 提供知识库及可用向量模型的 REST 接口。 */
@RestController
@Profile("local")
@Validated
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
  private final KnowledgeBaseService knowledgeBases;

  public KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
    this.knowledgeBases = knowledgeBases;
  }

  /** 分页查询知识库及各自的文档数量。 */
  @GetMapping
  public PageResponse<KnowledgeBaseResponse> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 200) String query) {
    return knowledgeBases.list(page, pageSize, query);
  }

  /** 查询配置中可供新知识库绑定的向量模型。 */
  @GetMapping("/embedding-models")
  public List<EmbeddingModelResponse> embeddingModels() {
    return knowledgeBases.embeddingModels();
  }

  /** 获取指定知识库。 */
  @GetMapping("/{id}")
  public KnowledgeBaseResponse get(@PathVariable UUID id) {
    return knowledgeBases.get(id);
  }

  /** 创建知识库并绑定所选向量模型。 */
  @PostMapping
  public KnowledgeBaseResponse create(@Valid @RequestBody KnowledgeBaseRequest request) {
    return knowledgeBases.create(request.name(), request.embeddingModelId());
  }

  /** 修改知识库名称。 */
  @PatchMapping("/{id}")
  public KnowledgeBaseResponse rename(
      @PathVariable UUID id, @Valid @RequestBody KnowledgeBaseRequest request) {
    return knowledgeBases.rename(id, request.name());
  }

  /** 删除知识库及其全部文档资源。 */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    knowledgeBases.delete(id);
  }
}
