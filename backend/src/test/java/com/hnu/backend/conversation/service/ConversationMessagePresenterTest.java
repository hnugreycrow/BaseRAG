package com.hnu.backend.conversation.service;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.conversation.entity.Message;
import com.hnu.backend.conversation.entity.MessageStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationMessagePresenterTest {
  private final ConversationMessagePresenter presenter = new ConversationMessagePresenter();

  @Test
  void absentLegacySnapshotsRemainReadable() {
    for (String absent : new String[] {null, "", "  ", "null"}) {
      Message message = new Message();
      message.setStatus(MessageStatus.COMPLETED);
      message.setSourcesJson(absent);
      message.setCitationsJson(absent);
      message.setModelInfoJson(absent);
      var response = presenter.assistantResponse(message);
      assertEquals(List.of(), response.sources());
      assertEquals(List.of(), response.citations());
      assertNull(response.modelInfo());
    }
  }

  @Test
  void missingModelConfigIdAndAddedFieldsAreCompatible() {
    Message message = new Message();
    message.setStatus(MessageStatus.COMPLETED);
    message.setCitationsJson("[\"S7\",\"S2\"]");
    message.setModelInfoJson("{\"provider\":\"legacy\",\"model\":\"chat\",\"futureField\":true}");
    var response = presenter.assistantResponse(message);
    assertNull(response.modelInfo().id());
    assertEquals("legacy", response.modelInfo().provider());
    assertEquals("chat", response.modelInfo().model());
    assertEquals(List.of("S7", "S2"), response.citations());
    assertThrows(UnsupportedOperationException.class, () -> response.citations().add("S3"));
  }

  @Test
  void corruptSnapshotsAreNotSilentlyDisplayedAsEmpty() {
    Message message = new Message();
    message.setStatus(MessageStatus.COMPLETED);
    message.setSourcesJson("{}");
    assertThrows(IllegalArgumentException.class, () -> presenter.assistantResponse(message));
    message.setSourcesJson("[]");
    message.setCitationsJson("not json");
    assertThrows(RuntimeException.class, () -> presenter.assistantResponse(message));
  }
}
