package com.hnu.backend.document.parser;

import com.hnu.backend.configuration.RagProperties;
import com.hnu.backend.document.parser.StructuredBlock.Kind;
import com.hnu.backend.document.parser.StructuredBlock.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将结构块按软标题和长度预算打包，并生成展示正文、向量文本及真实来源范围。 */
public class StructuredChunkPacker {
  public record Chunk(String content, String embeddingText, String heading, SourceSpan source) {}

  private static final String SEPARATOR = "\n\n";
  private final int targetSize;
  private final int minSize;
  private final int maxSize;

  public StructuredChunkPacker(RagProperties config) {
    targetSize = config.getChunkSize();
    minSize = Math.min(config.getChunkMinSize(), targetSize);
    maxSize = Math.max(config.getChunkMaxSize(), targetSize);
  }

  public List<Chunk> pack(List<StructuredBlock> blocks) {
    List<List<StructuredBlock>> packed = new ArrayList<>();
    List<StructuredBlock> buffer = new ArrayList<>();
    for (List<StructuredBlock> unit : units(prepareListContext(blocks))) {
      if (contentLength(unit) > maxSize) {
        throw new IllegalArgumentException(
            "Parsed block exceeds the configured maximum chunk size");
      }
      if (unit.size() == 1 && unit.getFirst().piece()) {
        // 原子块切出的片段不互相回并；长文本片段可能带有原文重叠。
        if (!buffer.isEmpty()
            && contentLength(buffer) < minSize
            && canJoin(buffer, unit, maxSize)) {
          buffer.addAll(unit);
        } else {
          flush(buffer, packed);
          buffer.addAll(unit);
        }
        flush(buffer, packed);
        continue;
      }
      if (!buffer.isEmpty()) {
        // 小于最小长度时允许继续填充到硬上限，否则以目标长度为优先预算。
        int limit = contentLength(buffer) < minSize ? maxSize : targetSize;
        if (!canJoin(buffer, unit, limit)) {
          flush(buffer, packed);
        }
      }
      buffer.addAll(unit);
    }
    flush(buffer, packed);

    List<Chunk> result = new ArrayList<>();
    for (List<StructuredBlock> group : packed) {
      if (group.stream().allMatch(block -> block.kind() == Kind.HEADING)) {
        continue;
      }
      result.add(assemble(group));
    }
    return result;
  }

  private List<List<StructuredBlock>> units(List<StructuredBlock> blocks) {
    List<List<StructuredBlock>> units = new ArrayList<>();
    List<StructuredBlock> section = new ArrayList<>();
    for (StructuredBlock block : blocks) {
      if (block.kind() == Kind.HEADING && !section.isEmpty()) {
        addSection(section, units);
        section = new ArrayList<>();
      }
      section.add(block);
    }
    addSection(section, units);
    return units;
  }

  private void addSection(List<StructuredBlock> section, List<List<StructuredBlock>> units) {
    if (section.isEmpty()) {
      return;
    }
    // 未切开的完整小节先作为候选单元；标题只帮助确定单元，不阻止跨节打包。
    if (contentLength(section) <= maxSize && section.stream().noneMatch(StructuredBlock::piece)) {
      units.add(List.copyOf(section));
    } else {
      for (StructuredBlock block : section) {
        units.add(List.of(block));
      }
    }
  }

  private List<StructuredBlock> prepareListContext(List<StructuredBlock> blocks) {
    // 短引导语只补入列表的向量文本；续块的展示正文和来源范围仍指向实际原文。
    List<StructuredBlock> result = new ArrayList<>(blocks.size());
    String introduction = null;
    SourceSpan introductionSource = null;
    int introductionSection = -1;
    boolean listStarted = false;
    for (StructuredBlock block : blocks) {
      if (block.kind() == Kind.TEXT) {
        introduction = block.content().length() < minSize ? block.content().strip() : null;
        introductionSource = block.source();
        introductionSection = block.section();
        listStarted = false;
      } else if (block.kind() != Kind.LIST) {
        introduction = null;
        listStarted = false;
      }
      StructuredBlock prepared = block;
      if (block.kind() == Kind.LIST
          && introduction != null
          && introductionSection == block.section()
          && ((block.piece() && listStarted) || isNearby(introductionSource, block.source()))) {
        String indexText = block.indexText().isBlank() ? block.content() : block.indexText();
        if (!indexText.startsWith(introduction + "\n")) {
          indexText = introduction + "\n" + indexText;
        }
        prepared =
            new StructuredBlock(
                block.kind(),
                block.section(),
                block.heading(),
                block.outlinePath(),
                block.content(),
                indexText,
                block.source(),
                block.piece());
        listStarted = true;
      }
      result.add(prepared);
    }
    return result;
  }

  private boolean canJoin(List<StructuredBlock> previous, List<StructuredBlock> next, int limit) {
    SourceSpan left = previous.getLast().source();
    SourceSpan right = next.getFirst().source();
    // 跨标题可以合并，但来源单位必须一致，且不能把重叠或已切开的两个片段重新拼回。
    return left.unit() == right.unit()
        && right.startOffset() >= left.endOffset()
        && !(hasPiece(previous) && hasPiece(next))
        && contentLength(previous) + SEPARATOR.length() + contentLength(next) <= limit;
  }

  private static boolean hasPiece(List<StructuredBlock> blocks) {
    return blocks.stream().anyMatch(StructuredBlock::piece);
  }

  private void flush(List<StructuredBlock> buffer, List<List<StructuredBlock>> result) {
    if (buffer.isEmpty()) {
      return;
    }
    // 末尾短块优先回并到前一块，仍受配置的硬上限和片段规则约束。
    if (!result.isEmpty()
        && contentLength(buffer) < minSize
        && canJoin(result.getLast(), buffer, maxSize)) {
      List<StructuredBlock> joined = new ArrayList<>(result.getLast());
      joined.addAll(buffer);
      result.set(result.size() - 1, joined);
    } else {
      result.add(new ArrayList<>(buffer));
    }
    buffer.clear();
  }

  private static Chunk assemble(List<StructuredBlock> blocks) {
    // 展示正文保留原有标题标记；向量文本可补上下文，但不得扩大来源范围。
    StringBuilder content = new StringBuilder();
    StringBuilder body = new StringBuilder();
    for (StructuredBlock block : blocks) {
      append(content, block.content());
      String part =
          block.kind() == Kind.HEADING && !block.outlinePath().isEmpty()
              ? block.outlinePath().getLast()
              : block.indexText().isBlank() ? block.content() : block.indexText();
      if (block.kind() == Kind.LIST && !body.isEmpty() && part.contains("\n")) {
        String caption = part.substring(0, part.indexOf('\n'));
        if (body.toString().stripTrailing().endsWith(caption)) {
          part = part.substring(part.indexOf('\n') + 1);
        }
      }
      append(body, part);
    }
    List<String> outline = commonOutline(blocks);
    SourceSpan first = blocks.getFirst().source();
    SourceSpan last = blocks.getLast().source();
    // 合并块只标记首尾原子块实际覆盖的原文位置，不把补入的表头等上下文当作来源。
    return new Chunk(
        content.toString(),
        withHeading(body.toString(), outline),
        String.join(" / ", outline),
        new SourceSpan(
            first.unit(), first.start(), last.end(), first.startOffset(), last.endOffset()));
  }

  private static List<String> commonOutline(List<StructuredBlock> blocks) {
    // 跨小节后的标题字段只保留所有结构块共同的分层标题前缀。
    List<String> first = blocks.getFirst().outlinePath();
    int count = first.size();
    for (StructuredBlock block : blocks) {
      List<String> path = block.outlinePath();
      count = Math.min(count, path.size());
      for (int i = 0; i < count; i++) {
        if (!Objects.equals(first.get(i), path.get(i))) {
          count = i;
          break;
        }
      }
    }
    return first.subList(0, count);
  }

  private static String withHeading(String body, List<String> outline) {
    List<String> missing = new ArrayList<>();
    for (String level : outline) {
      if (!body.contains(level)) {
        missing.add(level);
      }
    }
    return missing.isEmpty() ? body : String.join(" / ", missing) + "\n" + body;
  }

  private static void append(StringBuilder target, String value) {
    if (value.isBlank()) {
      return;
    }
    if (!target.isEmpty()) {
      target.append(SEPARATOR);
    }
    target.append(value);
  }

  private static int contentLength(List<StructuredBlock> blocks) {
    int length = 0;
    for (StructuredBlock block : blocks) {
      if (length != 0) {
        length += SEPARATOR.length();
      }
      length += block.content().length();
    }
    return length;
  }

  private static boolean isNearby(SourceSpan previous, SourceSpan next) {
    return previous.unit() == next.unit() && next.start() - previous.end() <= 2;
  }
}
