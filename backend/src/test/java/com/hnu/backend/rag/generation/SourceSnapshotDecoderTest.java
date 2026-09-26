package com.hnu.backend.rag.generation;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.rag.vo.SourceResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SourceSnapshotDecoderTest {
  private final JsonMapper json = com.hnu.backend.common.json.JsonCodecs.snapshots();
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

  @Test
  void acceptsAdditionalFieldsWithoutChangingCurrentSourceIdentity() {
    UUID id = UUID.randomUUID();
    var location =
        new SourceResponse.Location(id, null, new SourceResponse.Range("LINE", 1, 2, "第 1–2 行"));
    var source =
        new SourceResponse(
            2, "S1", id, "知识库", id, id, "手册.md", "MARKDOWN", "原文", location, List.of(location));
    var node = json.valueToTree(source);
    var object = (tools.jackson.databind.node.ObjectNode) node;
    object.put("futureField", "ignored");
    ((tools.jackson.databind.node.ObjectNode) object.path("primaryLocation"))
        .put("futureLocationField", true);
    assertEquals(List.of(source), decoder.decode("[" + object + "]"));
  }

  @Test
  void missingOptionalLegacyHeadingIsNullAndOrderIsPreserved() {
    String id = "00000000-0000-4000-8000-000000000001";
    String source =
        """
        {"citationId":"S7","knowledgeBaseId":"%s","knowledgeBaseName":"制度",
         "documentId":"%s","versionId":"%s","chunkId":"%s","documentName":"旧.md",
         "lineStart":1,"lineEnd":2,"content":"原文","futureField":true}
        """
            .formatted(id, id, id, id);
    var decoded = decoder.decode("[" + source + "," + source.replace("S7", "S2") + "]");
    assertNull(decoded.getFirst().primaryLocation().heading());
    assertEquals(List.of("S7", "S2"), decoded.stream().map(SourceResponse::citationId).toList());
    assertThrows(UnsupportedOperationException.class, () -> decoded.clear());
  }

  @Test
  void rejectsUnknownVersionsAndMalformedShapes() {
    for (String invalid : List.of("{}", "[null]", "[42]", "[{\"schemaVersion\":99}]")) {
      assertThrows(IllegalArgumentException.class, () -> decoder.decode(invalid));
    }
    assertThrows(RuntimeException.class, () -> decoder.decode("["));
    // 位置是 v2 的必需业务字段，不为缺失位置伪造默认引用。
    assertThrows(RuntimeException.class, () -> decoder.decode("[{\"schemaVersion\":2}]"));
  }
}
