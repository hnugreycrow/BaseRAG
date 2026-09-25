package com.hnu.backend.document.parser;

import com.hnu.backend.configuration.RagProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 将 Markdown 标题、正文和结构单元解析为原子块，再按软标题与长度预算合并。
 *
 * <p>切分结果保留标题路径和原文行号，便于回答引用回溯到原文。
 */
@Component
public class MarkdownChunker {
  public record Piece(
      String content, String embeddingText, String heading, int lineStart, int lineEnd) {}

  private enum Kind {
    HEADING,
    TEXT,
    CODE,
    TABLE,
    LIST
  }

  private record Block(
      int start,
      int end,
      String heading,
      int section,
      Kind kind,
      String display,
      String search,
      boolean piece) {
    private Block(int start, int end, String heading, int section, Kind kind) {
      this(start, end, heading, section, kind, null, null, false);
    }

    private Block(
        int start, int end, String heading, int section, Kind kind, String display, String search) {
      this(start, end, heading, section, kind, display, search, false);
    }
  }

  private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
  private static final Pattern LIST_MARKER = Pattern.compile("^ {0,3}(?:[-*+]|\\d+[.)])\\s+.*$");
  private static final Pattern TABLE_DELIMITER =
      Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?(?:\\s*\\|\\s*:?-{3,}:?)*\\s*\\|?\\s*$");
  private static final String SENTENCE_END = "。！？；.!?;";
  private final int targetSize;
  private final int minSize;
  private final int maxSize;
  private final int overlap;
  private final StructuredChunkPacker packer;

  public MarkdownChunker(RagProperties config) {
    targetSize = config.getChunkSize();
    minSize = Math.min(config.getChunkMinSize(), targetSize);
    maxSize = Math.max(config.getChunkMaxSize(), targetSize);
    overlap = config.getChunkOverlap();
    packer = new StructuredChunkPacker(config);
  }

  /**
   * 将 Markdown 文本切分为适合向量化的语义片段。
   *
   * @param markdown Markdown 原文
   * @return 按原文顺序排列的非空片段
   */
  public List<Piece> split(String markdown) {
    List<Piece> result = new ArrayList<>();
    for (StructuredChunkPacker.Chunk chunk : packer.pack(parse(markdown))) {
      result.add(
          new Piece(
              chunk.content(),
              chunk.embeddingText(),
              chunk.heading(),
              chunk.source().start(),
              chunk.source().end()));
    }
    return result;
  }

  /** 返回结构块允许的最大字符数，供其他格式解析器限制单块大小。 */
  public int maxSize() {
    return maxSize;
  }

  /**
   * 使用统一策略打包各格式的结构块。
   *
   * @param blocks 按原文顺序排列的结构块
   * @return 带来源位置的分块
   */
  public List<StructuredChunkPacker.Chunk> pack(List<StructuredBlock> blocks) {
    return packer.pack(blocks);
  }

  /**
   * 解析 Markdown 结构并保留标题路径、字符偏移和原文行号。
   *
   * @param markdown Markdown 原文
   * @return 按原文顺序排列的结构块
   */
  public List<StructuredBlock> parse(String markdown) {
    String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = text.split("\n", -1);
    int[] offsets = new int[lines.length];
    for (int i = 1; i < lines.length; i++) offsets[i] = offsets[i - 1] + lines[i - 1].length() + 1;
    List<Block> blocks = new ArrayList<>();
    // 每次遇到标题都记录真实的层级路径；打包器据此计算跨小节块的公共标题。
    List<List<String>> sectionPaths = new ArrayList<>();
    sectionPaths.add(List.of());
    String[] headings = new String[6];
    String path = "";
    int section = 0;
    int start = -1;
    char fence = 0;
    int fenceLength = 0;
    for (int i = 0; i < lines.length; i++) {
      var fenceMatch = FENCE.matcher(lines[i]);
      if (fence != 0) {
        String trimmed = lines[i].strip();
        if (trimmed.length() >= fenceLength
            && trimmed.chars().allMatch(c -> c == trimmed.charAt(0))
            && trimmed.charAt(0) == fence) {
          fence = 0;
          blocks.add(new Block(start, offsets[i] + lines[i].length(), path, section, Kind.CODE));
          start = -1;
        }
        continue;
      }
      var heading = HEADING.matcher(lines[i]);
      if (heading.matches()) {
        if (start >= 0) blocks.add(new Block(start, offsets[i] - 1, path, section, Kind.TEXT));
        section++;
        int level = heading.group(1).length() - 1;
        headings[level] = heading.group(2);
        Arrays.fill(headings, level + 1, 6, null);
        List<String> levels = Arrays.stream(headings).filter(Objects::nonNull).toList();
        path = String.join(" / ", levels);
        sectionPaths.add(levels);
        blocks.add(
            new Block(offsets[i], offsets[i] + lines[i].length(), path, section, Kind.HEADING));
        start = -1;
      } else if (fenceMatch.matches()) {
        if (start >= 0) blocks.add(new Block(start, offsets[i] - 1, path, section, Kind.TEXT));
        start = offsets[i];
        fence = fenceMatch.group(1).charAt(0);
        fenceLength = fenceMatch.group(1).length();
      } else if (lines[i].isBlank()) {
        if (start >= 0) blocks.add(new Block(start, offsets[i] - 1, path, section, Kind.TEXT));
        start = -1;
      } else if (start < 0) start = offsets[i];
    }
    if (start >= 0)
      blocks.add(
          new Block(start, text.length(), path, section, fence == 0 ? Kind.TEXT : Kind.CODE));

    blocks = structure(blocks, lines, offsets);

    // 先保护结构单元，再对过长的普通段落使用句界回退及重叠。
    List<Block> atomic = new ArrayList<>();
    for (Block block : blocks) {
      atomic.addAll(splitAtomic(block, text, offsets));
    }

    // 转交通用打包器前保留原文偏移与行号；展示围栏、表头等补充文本不改变来源定位。
    List<StructuredBlock> structured = new ArrayList<>();
    for (Block block : atomic) {
      String content =
          block.display == null ? text.substring(block.start, block.end) : block.display;
      String search = block.search == null ? content : block.search;
      structured.add(
          new StructuredBlock(
              StructuredBlock.Kind.valueOf(block.kind.name()),
              block.section,
              block.heading,
              sectionPaths.get(block.section),
              content,
              search,
              new StructuredBlock.SourceSpan(
                  StructuredBlock.SourceSpan.Unit.LINE,
                  lineAt(offsets, block.start),
                  lineAt(offsets, block.end - 1),
                  block.start,
                  block.end),
              block.piece));
    }

    return structured;
  }

  private static List<Block> structure(List<Block> blocks, String[] lines, int[] offsets) {
    List<Block> result = new ArrayList<>();
    for (Block block : blocks) {
      if (block.kind == Kind.CODE || block.kind == Kind.HEADING) {
        result.add(block);
        continue;
      }
      int first = lineAt(offsets, block.start) - 1;
      int last = lineAt(offsets, block.end - 1) - 1;
      int cursor = first;
      while (cursor <= last) {
        int begin = cursor;
        Kind kind;
        if (isTableStart(lines, cursor, last)) {
          kind = Kind.TABLE;
          cursor += 2;
          while (cursor <= last && lines[cursor].contains("|")) cursor++;
        } else if (LIST_MARKER.matcher(lines[cursor]).matches()) {
          kind = Kind.LIST;
          cursor++;
          while (cursor <= last
              && (LIST_MARKER.matcher(lines[cursor]).matches()
                  || lines[cursor].startsWith("  ")
                  || lines[cursor].startsWith("\t"))) cursor++;
        } else {
          kind = Kind.TEXT;
          cursor++;
          while (cursor <= last
              && !isTableStart(lines, cursor, last)
              && !LIST_MARKER.matcher(lines[cursor]).matches()) cursor++;
        }
        int start = offsets[begin];
        int end = offsets[cursor - 1] + lines[cursor - 1].length();
        result.add(new Block(start, end, block.heading, block.section, kind));
      }
    }
    return result;
  }

  private static boolean isTableStart(String[] lines, int line, int last) {
    return line < last
        && lines[line].contains("|")
        && TABLE_DELIMITER.matcher(lines[line + 1]).matches();
  }

  private List<Block> splitAtomic(Block block, String text, int[] offsets) {
    List<Block> pieces =
        switch (block.kind) {
          case HEADING -> List.of(block);
          case TEXT -> splitText(block, text);
          case CODE -> splitCode(block, text);
          case TABLE -> splitTable(block, text, offsets);
          case LIST -> splitList(block, text, offsets);
        };
    if (pieces.size() <= 1) return pieces;
    // 同一原子块被切成多个片段后显式标记，防止打包阶段消除结构边界或文本重叠。
    return pieces.stream()
        .map(
            piece ->
                new Block(
                    piece.start,
                    piece.end,
                    piece.heading,
                    piece.section,
                    piece.kind,
                    piece.display,
                    piece.search,
                    true))
        .toList();
  }

  private List<Block> splitText(Block block, String text) {
    return splitText(block, text, overlap);
  }

  private List<Block> splitText(Block block, String text, int overlapChars) {
    if (block.end - block.start <= targetSize) return List.of(block);
    List<Block> result = new ArrayList<>();
    for (int cursor = block.start; cursor < block.end; ) {
      int proposed = Math.min(cursor + targetSize, block.end);
      int end =
          proposed == block.end
              ? proposed
              : naturalBoundary(text, proposed, Math.min(cursor + minSize, proposed));
      if (end < block.end && Character.isHighSurrogate(text.charAt(end - 1))) end--;
      result.add(new Block(cursor, end, block.heading, block.section, Kind.TEXT));
      if (end == block.end) break;
      int next = Math.max(cursor + 1, end - overlapChars);
      if (next < text.length() && Character.isLowSurrogate(text.charAt(next))) next++;
      cursor = next;
    }
    return result;
  }

  private List<Block> splitList(Block block, String text, int[] offsets) {
    if (block.end - block.start <= targetSize) return List.of(block);
    List<Block> result = new ArrayList<>();
    int first = lineAt(offsets, block.start) - 1;
    int last = lineAt(offsets, block.end - 1) - 1;
    List<Integer> itemLines = new ArrayList<>();
    for (int line = first; line <= last; line++) {
      int end = line < last ? offsets[line + 1] - 1 : block.end;
      if (LIST_MARKER.matcher(text.substring(offsets[line], end)).matches()) itemLines.add(line);
    }
    if (itemLines.isEmpty()) return splitText(block, text, 0);
    int groupStart = itemLines.getFirst();
    for (int i = 1; i < itemLines.size(); i++) {
      int next = itemLines.get(i);
      if (offsets[next] - offsets[groupStart] > targetSize) {
        result.add(listPart(block, text, offsets, groupStart, next - 1));
        groupStart = next;
      }
    }
    result.add(listPart(block, text, offsets, groupStart, last));
    List<Block> bounded = new ArrayList<>();
    for (Block part : result) {
      if (part.end - part.start <= maxSize) {
        bounded.add(part);
      } else {
        for (Block slice :
            splitText(
                new Block(part.start, part.end, part.heading, part.section, Kind.TEXT), text, 0)) {
          String content = text.substring(slice.start, slice.end);
          String leadIn = part.search.substring(0, part.search.length() - (part.end - part.start));
          bounded.add(
              new Block(
                  slice.start,
                  slice.end,
                  part.heading,
                  part.section,
                  Kind.LIST,
                  content,
                  leadIn + content));
        }
      }
    }
    return bounded;
  }

  private static Block listPart(Block block, String text, int[] offsets, int first, int last) {
    int start = offsets[first];
    int end =
        Math.min(block.end, last + 1 < offsets.length ? offsets[last + 1] - 1 : text.length());
    String body = text.substring(start, end);
    String leadIn =
        block.search == null
            ? ""
            : block.search.substring(0, block.search.length() - (block.end - block.start));
    return new Block(start, end, block.heading, block.section, Kind.LIST, null, leadIn + body);
  }

  private List<Block> splitCode(Block block, String text) {
    if (block.end - block.start <= maxSize) return List.of(block);
    String raw = text.substring(block.start, block.end);
    int firstNewline = raw.indexOf('\n');
    if (firstNewline < 0)
      return splitText(
          new Block(block.start, block.end, block.heading, block.section, Kind.TEXT), text, 0);
    String opening = raw.substring(0, firstNewline);
    var fenceMatch = FENCE.matcher(opening);
    String marker = fenceMatch.matches() ? fenceMatch.group(1) : "```";
    int lastNewline = raw.lastIndexOf('\n');
    String tail = raw.substring(lastNewline + 1);
    boolean closed = lastNewline > firstNewline && tail.strip().startsWith(marker);
    String closing = closed ? tail : marker;
    int bodyStart = block.start + firstNewline + 1;
    int bodyEnd = closed ? block.start + lastNewline : block.end;
    if (bodyStart >= bodyEnd || opening.length() + closing.length() + 4 >= maxSize)
      return splitText(
          new Block(block.start, block.end, block.heading, block.section, Kind.TEXT), text, 0);
    int budget =
        Math.max(2, Math.min(targetSize, maxSize) - opening.length() - closing.length() - 2);
    List<Block> result = new ArrayList<>();
    for (int cursor = bodyStart; cursor < bodyEnd; ) {
      int proposed = Math.min(cursor + budget, bodyEnd);
      int end = proposed;
      if (end < bodyEnd) {
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline >= cursor + Math.min(16, budget / 2)) end = newline;
      }
      if (end < bodyEnd && Character.isHighSurrogate(text.charAt(end - 1))) end--;
      String body = text.substring(cursor, end);
      // 每个代码续块补可读围栏；偏移仍取该片段实际覆盖的代码行。
      result.add(
          new Block(
              cursor == bodyStart ? block.start : cursor,
              end == bodyEnd ? block.end : end,
              block.heading,
              block.section,
              Kind.CODE,
              opening + "\n" + body + "\n" + closing,
              opening.strip() + "\n" + body));
      cursor = end < bodyEnd && text.charAt(end) == '\n' ? end + 1 : end;
    }
    return result;
  }

  private List<Block> splitTable(Block block, String text, int[] offsets) {
    String raw = text.substring(block.start, block.end);
    String[] rows = raw.split("\n", -1);
    if (rows.length < 3) return List.of(block);
    String header = rows[0];
    String delimiter = rows[1];
    if (header.length() + delimiter.length() + 5 >= maxSize)
      return splitText(
          new Block(block.start, block.end, block.heading, block.section, Kind.TEXT), text, 0);
    String[] columns = cells(header);
    List<Block> result = new ArrayList<>();
    int row = 2;
    while (row < rows.length) {
      int startRow = row;
      int size = header.length() + delimiter.length() + 2;
      while (row < rows.length
          && (row == startRow || size + rows[row].length() + 1 <= targetSize)) {
        size += rows[row].length() + 1;
        row++;
      }
      StringBuilder display = new StringBuilder(header).append('\n').append(delimiter);
      StringBuilder search = new StringBuilder();
      for (int i = startRow; i < row; i++) {
        display.append('\n').append(rows[i]);
        String[] values = cells(rows[i]);
        for (int c = 0; c < Math.min(columns.length, values.length); c++) {
          if (!values[c].isBlank()) {
            if (!search.isEmpty()) search.append("; ");
            search.append(columns[c]).append(": ").append(values[c]);
          }
        }
        search.append('\n');
      }
      int firstLine = lineAt(offsets, block.start) - 1;
      int start = startRow == 2 ? block.start : offsets[firstLine + startRow];
      int end = offsets[firstLine + row - 1] + rows[row - 1].length();
      if (display.length() > maxSize) {
        int sourceStart = offsets[firstLine + startRow];
        int budget = maxSize - header.length() - delimiter.length() - 3;
        String longRow = rows[startRow];
        for (int cursor = 0; cursor < longRow.length(); ) {
          int sliceEnd = Math.min(cursor + budget, longRow.length());
          if (sliceEnd < longRow.length()
              && Character.isHighSurrogate(longRow.charAt(sliceEnd - 1))) sliceEnd--;
          String fragment = longRow.substring(cursor, sliceEnd);
          result.add(
              new Block(
                  sourceStart + cursor,
                  sourceStart + sliceEnd,
                  block.heading,
                  block.section,
                  Kind.TABLE,
                  header + "\n" + delimiter + "\n" + fragment,
                  String.join(" / ", columns) + "\n" + fragment));
          cursor = sliceEnd;
        }
      } else {
        // 表格续块重现表头供阅读，向量文本改写为列名与单元格值。
        result.add(
            new Block(
                start,
                end,
                block.heading,
                block.section,
                Kind.TABLE,
                display.toString(),
                search.toString().strip()));
      }
    }
    return result;
  }

  private static String[] cells(String row) {
    String trimmed = row.strip();
    if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
    if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return Arrays.stream(trimmed.split("(?<!\\\\)\\|", -1))
        .map(String::strip)
        .toArray(String[]::new);
  }

  private static int naturalBoundary(String text, int proposed, int minimum) {
    for (int i = proposed; i > minimum; i--) {
      char previous = text.charAt(i - 1);
      if (previous == '\n' || SENTENCE_END.indexOf(previous) >= 0) return i;
    }
    for (int i = proposed; i > minimum; i--) {
      if (Character.isWhitespace(text.charAt(i - 1))) return i;
    }
    return proposed;
  }

  private static int lineAt(int[] offsets, int position) {
    int found = Arrays.binarySearch(offsets, position);
    return found >= 0 ? found + 1 : -found - 1;
  }
}
