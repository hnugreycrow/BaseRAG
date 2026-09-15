package com.hnu.backend.rag.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.rag.prompt.AssembledPrompt;
import com.hnu.backend.rag.prompt.PromptAssemblyStage;
import com.hnu.backend.rag.vo.SourceResponse;
import com.hnu.backend.shared.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnswerStageTest {
  private final AnswerGenerator generator = mock(AnswerGenerator.class);
  private final AnswerStage.Observer observer = mock(AnswerStage.Observer.class);
  private final TestControl control = new TestControl();
  private final PromptAssemblyStage prompts =
      new PromptAssemblyStage(new ContextBuilder(new RagProperties()));
  private final AnswerStage stage = new AnswerStage(generator, prompts);
  private SourceResponse source;

  @BeforeEach
  void setUp() {
    source =
        new ContextBuilder(new RagProperties())
            .build(List.of(ContextAndCitationsTest.hit("依据")))
            .sources()
            .getFirst();
  }

  @Test
  void returnsValidatedKnowledgeAndToolReferences() {
    AssembledPrompt prompt = prompt(List.of(source), List.of("T1"), true);
    AnswerGenerator.Generation generation = generation("答案 [S1]，根据工具 T1。");
    when(generator.generate(
            anyString(),
            anyString(),
            eq(AnswerGenerator.AttemptReason.PRIMARY),
            eq(observer),
            eq(control)))
        .thenReturn(generation);

    AnswerResult result = stage.execute(prompt, observer, control);

    assertEquals(generation.content(), result.content());
    assertEquals(List.of("S1"), result.citations());
    assertEquals(List.of("T1"), result.toolReferences());
    assertEquals(prompt.sources(), result.sources());
    assertSame(generation, result.generation());
  }

  @Test
  void skipsModelWhenPromptDoesNotRequireGeneration() {
    AssembledPrompt prompt = prompt(List.of(), List.of(), false);

    AnswerResult result = stage.execute(prompt, observer, control);

    assertTrue(result.content().contains("资料不足"));
    assertTrue(result.sources().isEmpty());
    assertNull(result.generation());
    verifyNoInteractions(generator);
    verify(observer).generationSkipped("NO_EVIDENCE");
  }

  @Test
  void repairsInvalidReferencesOnceWithTheSameUserInput() {
    AssembledPrompt prompt = prompt(List.of(source), List.of("T1"), true);
    when(generator.generate(anyString(), anyString(), any(), eq(observer), eq(control)))
        .thenReturn(generation("错误 [S99] T99"), generation("答案 [S1]，工具 T1。"));

    AnswerResult result = stage.execute(prompt, observer, control);

    assertEquals("答案 [S1]，工具 T1。", result.content());
    verify(observer).invalidReferences("INVALID_CITATIONS", true);
    verify(observer, never()).invalidReferences("INVALID_CITATIONS", false);
    ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<AnswerGenerator.AttemptReason> reason =
        ArgumentCaptor.forClass(AnswerGenerator.AttemptReason.class);
    verify(generator, times(2))
        .generate(system.capture(), user.capture(), reason.capture(), eq(observer), eq(control));
    assertEquals(List.of(prompt.userPrompt(), prompt.userPrompt()), user.getAllValues());
    assertEquals(
        List.of(
            AnswerGenerator.AttemptReason.PRIMARY, AnswerGenerator.AttemptReason.CITATION_REPAIR),
        reason.getAllValues());
    assertTrue(system.getAllValues().get(1).contains("引用修复"));
    assertTrue(system.getAllValues().stream().noneMatch(value -> value.contains("错误 [S99]")));
  }

  @Test
  void citationRepairKeepsTheRequestedThinkingMode() {
    AssembledPrompt prompt = prompt(List.of(source), List.of(), true);
    when(generator.generate(any(AnswerGenerator.Request.class), any(), eq(observer), eq(control)))
        .thenReturn(generation("错误 [S99]"), generation("答案 [S1]"));

    AnswerResult result = stage.execute(prompt, observer, control, true);

    assertEquals("答案 [S1]", result.content());
    ArgumentCaptor<AnswerGenerator.Request> requests =
        ArgumentCaptor.forClass(AnswerGenerator.Request.class);
    verify(generator, times(2)).generate(requests.capture(), any(), eq(observer), eq(control));
    assertTrue(requests.getAllValues().stream().allMatch(AnswerGenerator.Request::thinkingEnabled));
  }

  @Test
  void rejectsASecondInvalidAnswerAndDoesNotRetryAgain() {
    AssembledPrompt prompt = prompt(List.of(source), List.of(), true);
    when(generator.generate(anyString(), anyString(), any(), eq(observer), eq(control)))
        .thenReturn(generation("[S99]"));

    ApiException error =
        assertThrows(ApiException.class, () -> stage.execute(prompt, observer, control));

    assertEquals("INVALID_CITATIONS", error.code());
    verify(generator, times(2))
        .generate(anyString(), anyString(), any(), eq(observer), eq(control));
    verify(observer).invalidReferences("INVALID_CITATIONS", true);
    verify(observer).invalidReferences("INVALID_CITATIONS", false);
  }

  @Test
  void cancellationBeforeGenerationPreventsModelCall() {
    control.close();

    ApiException error =
        assertThrows(
            ApiException.class,
            () -> stage.execute(prompt(List.of(source), List.of(), true), observer, control));

    assertEquals("GENERATION_CANCELLED", error.code());
    verifyNoInteractions(generator, observer);
  }

  @Test
  void cancellationBeforeRepairPreventsSecondModelCall() {
    AssembledPrompt prompt = prompt(List.of(source), List.of(), true);
    when(generator.generate(anyString(), anyString(), any(), eq(observer), eq(control)))
        .thenReturn(generation("[S99]"));
    doAnswer(
            ignored -> {
              control.close();
              return null;
            })
        .when(observer)
        .invalidReferences("INVALID_CITATIONS", true);

    ApiException error =
        assertThrows(ApiException.class, () -> stage.execute(prompt, observer, control));

    assertEquals("GENERATION_CANCELLED", error.code());
    verify(generator)
        .generate(
            anyString(),
            anyString(),
            eq(AnswerGenerator.AttemptReason.PRIMARY),
            eq(observer),
            eq(control));
    verify(generator, never())
        .generate(
            anyString(),
            anyString(),
            eq(AnswerGenerator.AttemptReason.CITATION_REPAIR),
            eq(observer),
            eq(control));
  }

  private AssembledPrompt prompt(
      List<SourceResponse> sources, List<String> toolReferences, boolean shouldGenerate) {
    return new AssembledPrompt("system", "user", sources, toolReferences, shouldGenerate);
  }

  private AnswerGenerator.Generation generation(String content) {
    return new AnswerGenerator.Generation(content, "chat", "test", "model");
  }

  private static final class TestControl implements AnswerGenerator.Control {
    private boolean cancelled;

    @Override
    public boolean cancelled() {
      return cancelled;
    }

    @Override
    public void close() {
      cancelled = true;
    }
  }
}
