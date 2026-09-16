package com.hnu.backend.rag.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SourceSnapshotDecoderTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final SourceSnapshotDecoder decoder = new SourceSnapshotDecoder();

  @Test
  void readsNewDocumentSnapshotsWithoutChangingLocations() {
    UUID id = UUID.randomUUID();
    var location =
        new SourceResponse.Location(id, "标题", new SourceResponse.Range("LINE", 2, 4, "第 2–4 行"));
    var source =
        new SourceResponse(
            2, "S1", id, "知识库", id, id, "手册.md", "MARKDOWN", "全文", location, List.of(location));

    assertEquals(List.of(source), decoder.decode(json.writeValueAsString(List.of(source))));
  }

  @Test
  void readsOldChunksIndividuallyAndPreservesCitationIdentifiers() {
    UUID id = UUID.randomUUID();
    String old =
        "[{\"citationId\":\"S7\",\"knowledgeBaseId\":\""
            + id
            + "\",\"knowledgeBaseName\":\"制度\",\"chunkId\":\""
            + id
            + "\",\"documentId\":\""
            + id
            + "\",\"versionId\":\""
            + id
            + "\",\"documentName\":\"手册.md\",\"heading\":\"规则\",\"lineStart\":3,\"lineEnd\":4,\"similarity\":0.8,\"content\":\"历史原文\"}]";

    var source = decoder.decode(old).getFirst();
    assertEquals(1, source.schemaVersion());
    assertEquals("S7", source.citationId());
    assertEquals("历史原文", source.content());
    assertEquals(id, source.primaryLocation().chunkId());
    assertEquals("第 3–4 行", source.locations().getFirst().range().label());
  }
}
