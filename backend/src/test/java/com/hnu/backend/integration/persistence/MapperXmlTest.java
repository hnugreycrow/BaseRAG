package com.hnu.backend.integration.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hnu.backend.document.infrastructure.persistence.DocumentChunkMapper;
import com.hnu.backend.knowledgebase.infrastructure.persistence.KnowledgeBaseMapper;
import com.hnu.backend.question.infrastructure.persistence.RetrievalMapper;
import com.hnu.backend.shared.persistence.UuidTypeHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MapperXmlTest {
  @Test
  void loadsCustomMapperStatementsFromXml() throws IOException {
    Configuration configuration = new Configuration();
    configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);
    Map<String, ExpectedMapper> expectedMappers =
        Map.of(
            "mapper/knowledgebase/KnowledgeBaseMapper.xml",
            new ExpectedMapper(
                KnowledgeBaseMapper.class, new String[] {"lock", "selectWithDocumentCount"}),
            "mapper/document/DocumentChunkMapper.xml",
            new ExpectedMapper(
                DocumentChunkMapper.class, new String[] {"deleteByKnowledgeBase", "insertVector"}),
            "mapper/question/RetrievalMapper.xml",
            new ExpectedMapper(
                RetrievalMapper.class,
                new String[] {"activeModelBindings", "searchAll", "search"}));

    for (var entry : expectedMappers.entrySet()) {
      try (InputStream input = resource(entry.getKey())) {
        new XMLMapperBuilder(input, configuration, entry.getKey(), configuration.getSqlFragments())
            .parse();
      }
      for (String statement : entry.getValue().statements()) {
        assertTrue(
            configuration.hasStatement(entry.getValue().type().getName() + "." + statement, false));
      }
    }
  }

  private InputStream resource(String name) throws IOException {
    InputStream input = getClass().getClassLoader().getResourceAsStream(name);
    if (input == null) throw new IOException("Missing mapper resource: " + name);
    return input;
  }

  private record ExpectedMapper(Class<?> type, String[] statements) {}
}
