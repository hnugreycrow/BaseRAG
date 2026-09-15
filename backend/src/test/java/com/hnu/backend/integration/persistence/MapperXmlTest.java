package com.hnu.backend.integration.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hnu.backend.conversation.mapper.ConversationMapper;
import com.hnu.backend.conversation.mapper.GenerationAttemptMapper;
import com.hnu.backend.conversation.mapper.MessageMapper;
import com.hnu.backend.document.mapper.DocumentChunkMapper;
import com.hnu.backend.document.mapper.DocumentMapper;
import com.hnu.backend.document.mapper.DocumentVersionMapper;
import com.hnu.backend.knowledgebase.mapper.KnowledgeBaseMapper;
import com.hnu.backend.observability.mapper.RagRunMapper;
import com.hnu.backend.observability.mapper.RagStageRunMapper;
import com.hnu.backend.rag.retrieval.RetrievalMapper;
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
                KnowledgeBaseMapper.class,
                new String[] {"lock", "selectWithDocumentCount", "countWithDocumentCount"}),
            "mapper/rag/retrieval/RetrievalMapper.xml",
            new ExpectedMapper(
                RetrievalMapper.class,
                new String[] {
                  "activeModelBindings", "activeModelBindingsIn", "searchAll", "searchIn", "search"
                }),
            "mapper/observability/RagRunMapper.xml",
            new ExpectedMapper(
                RagRunMapper.class, new String[] {"list", "count", "findView", "summary"}),
            "mapper/observability/RagStageRunMapper.xml",
            new ExpectedMapper(RagStageRunMapper.class, new String[] {"insertBatch"}));

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

  @Test
  void entityMappersUseMybatisPlusDataInterface() {
    for (Class<?> mapper :
        new Class<?>[] {
          ConversationMapper.class,
          MessageMapper.class,
          GenerationAttemptMapper.class,
          RagRunMapper.class,
          RagStageRunMapper.class,
          KnowledgeBaseMapper.class,
          DocumentMapper.class,
          DocumentVersionMapper.class,
          DocumentChunkMapper.class
        }) {
      assertTrue(BaseMapper.class.isAssignableFrom(mapper));
    }
  }

  private InputStream resource(String name) throws IOException {
    InputStream input = getClass().getClassLoader().getResourceAsStream(name);
    if (input == null) throw new IOException("Missing mapper resource: " + name);
    return input;
  }

  private record ExpectedMapper(Class<?> type, String[] statements) {}
}
