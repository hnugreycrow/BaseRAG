package com.hnu.backend.rag.vo;

import java.util.List;
import java.util.UUID;

/**
 * 一次回答中按文档版本聚合的来源快照。
 *
 * @param schemaVersion 来源结构版本；历史分块来源读取后为 1
 * @param citationId 本轮回答使用的稳定 S 编号
 * @param knowledgeBaseId 来源知识库
 * @param knowledgeBaseName 知识库显示名
 * @param documentId 来源文档
 * @param versionId 来源文档版本
 * @param documentName 文档显示名
 * @param format 当前来源格式
 * @param content 入选分块按原文顺序拼接后的正文
 * @param primaryLocation 最高排名分块的原文位置
 * @param locations 全部入选分块的原文位置
 */
public record SourceResponse(
    int schemaVersion,
    String citationId,
    UUID knowledgeBaseId,
    String knowledgeBaseName,
    UUID documentId,
    UUID versionId,
    String documentName,
    String format,
    String content,
    Location primaryLocation,
    List<Location> locations) {
  /** 冻结位置列表，保证模型证据与持久化快照使用同一映射。 */
  public SourceResponse {
    locations = List.copyOf(locations);
  }

  /**
   * 来源分块与原文位置的关联。
   *
   * @param chunkId 持久化分块标识
   * @param heading 分块所属标题
   * @param range 原文通用位置
   */
  public record Location(UUID chunkId, String heading, Range range) {}

  /**
   * 各格式来源中的通用位置范围。
   *
   * @param unit 位置单位：LINE、PAGE 或 PARAGRAPH
   * @param start 起始位置
   * @param end 结束位置
   * @param label 面向用户的显示文字
   */
  public record Range(String unit, int start, int end, String label) {}
}
