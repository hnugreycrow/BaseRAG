package com.hnu.backend.rag.vo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 惰性读取历史分块级 JSON 和新版文档级来源快照，不改写历史消息。 */
public final class SourceSnapshotDecoder {
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 解码消息中的来源数组；旧来源保留各自的编号与顺序，不合并为新文档来源。
   *
   * @param encoded 数据库中的 JSON 数组
   * @return 可由当前前端统一展示的文档来源视图
   */
  public List<SourceResponse> decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return List.of();
    }
    JsonNode array = json.readTree(encoded);
    List<SourceResponse> sources = new ArrayList<>();
    for (JsonNode item : array) {
      JsonNode version = item.path("schemaVersion");
      if (version.asInt() == 2) {
        sources.add(json.readValue(item.toString(), SourceResponse.class));
      } else if (version.isMissingNode()) {
        int start = item.path("lineStart").asInt();
        int end = item.path("lineEnd").asInt();
        String label = start == end ? "第 " + start + " 行" : "第 " + start + "–" + end + " 行";
        SourceResponse.Location location =
            new SourceResponse.Location(
                uuid(item, "chunkId"),
                item.path("heading").isNull() ? null : item.path("heading").asString(),
                new SourceResponse.Range("LINE", start, end, label));
        sources.add(
            new SourceResponse(
                1,
                item.path("citationId").asString(),
                uuid(item, "knowledgeBaseId"),
                item.path("knowledgeBaseName").asString(),
                uuid(item, "documentId"),
                uuid(item, "versionId"),
                item.path("documentName").asString(),
                "MARKDOWN",
                item.path("content").asString(),
                location,
                List.of(location)));
      } else {
        throw new IllegalArgumentException("Unsupported source snapshot version");
      }
    }
    return List.copyOf(sources);
  }

  /** 将旧快照的 UUID 字符串还原为响应中的 UUID。 */
  private UUID uuid(JsonNode node, String field) {
    return UUID.fromString(node.path(field).asString());
  }
}
