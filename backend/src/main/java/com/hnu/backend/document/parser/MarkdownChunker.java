package com.hnu.backend.document.parser;

import com.hnu.backend.configuration.RagProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MarkdownChunker {
  public record Piece(String content, String heading, int lineStart, int lineEnd) {}

  private record Block(int start, int end, String heading) {}

  private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
  private static final String SENTENCE_END = "。！？；.!?;";
  private final int targetSize;
  private final int minSize;
  private final int maxSize;
  private final int overlap;

  public MarkdownChunker(RagProperties config) {
    targetSize = config.getChunkSize();
    minSize = Math.min(config.getChunkMinSize(), targetSize);
    maxSize = Math.max(config.getChunkMaxSize(), targetSize);
    overlap = config.getChunkOverlap();
  }

  public List<Piece> split(String markdown) {
    String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = text.split("\n", -1);
    int[] offsets = new int[lines.length];
    for (int i = 1; i < lines.length; i++) offsets[i] = offsets[i - 1] + lines[i - 1].length() + 1;
    List<Block> blocks = new ArrayList<>();
    String[] headings = new String[6];
    String path = "";
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
          blocks.add(new Block(start, offsets[i] + lines[i].length(), path));
          start = -1;
        }
        continue;
      }
      var heading = HEADING.matcher(lines[i]);
      if (heading.matches()) {
        if (start >= 0) blocks.add(new Block(start, offsets[i] - 1, path));
        int level = heading.group(1).length() - 1;
        headings[level] = heading.group(2);
        Arrays.fill(headings, level + 1, 6, null);
        path = String.join(" / ", Arrays.stream(headings).filter(Objects::nonNull).toList());
        start = offsets[i];
      } else if (fenceMatch.matches()) {
        if (start >= 0) blocks.add(new Block(start, offsets[i] - 1, path));
        start = offsets[i];
        fence = fenceMatch.group(1).charAt(0);
        fenceLength = fenceMatch.group(1).length();
      } else if (lines[i].isBlank()) {
        if (start >= 0) blocks.add(new Block(start, offsets[i] - 1, path));
        start = -1;
      } else if (start < 0) start = offsets[i];
    }
    if (start >= 0) blocks.add(new Block(start, text.length(), path));

    List<Block> atomic = new ArrayList<>();
    for (Block block : blocks) {
      if (block.end - block.start <= targetSize) {
        atomic.add(block);
        continue;
      }
      for (int cursor = block.start; cursor < block.end; ) {
        int proposed = Math.min(cursor + targetSize, block.end);
        int end =
            proposed == block.end
                ? proposed
                : naturalBoundary(text, cursor, proposed, Math.min(cursor + minSize, proposed));
        if (end < block.end && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        atomic.add(new Block(cursor, end, block.heading));
        if (end == block.end) break;
        int next = Math.max(cursor + 1, end - overlap);
        if (next < text.length() && Character.isLowSurrogate(text.charAt(next))) next++;
        cursor = next;
      }
    }

    List<Block> packed = new ArrayList<>();
    for (Block block : atomic) {
      if (!packed.isEmpty()) {
        Block last = packed.getLast();
        int combinedSize = block.end - last.start;
        boolean adjacent = block.start >= last.end;
        boolean smallSide = last.end - last.start < minSize || block.end - block.start < minSize;
        if (adjacent && (combinedSize <= targetSize || (smallSide && combinedSize <= maxSize))) {
          packed.set(
              packed.size() - 1,
              new Block(last.start, block.end, preferredHeading(last.heading, block.heading)));
          continue;
        }
      }
      packed.add(block);
    }

    List<Piece> result = new ArrayList<>();
    for (Block block : packed) {
      String content = text.substring(block.start, block.end);
      if (!content.isBlank())
        result.add(
            new Piece(
                content,
                block.heading,
                lineAt(offsets, block.start),
                lineAt(offsets, block.end - 1)));
    }
    return result;
  }

  private static int naturalBoundary(String text, int start, int proposed, int minimum) {
    for (int i = proposed; i > minimum; i--) {
      char previous = text.charAt(i - 1);
      if (previous == '\n' || SENTENCE_END.indexOf(previous) >= 0) return i;
    }
    for (int i = proposed; i > minimum; i--) {
      if (Character.isWhitespace(text.charAt(i - 1))) return i;
    }
    return proposed;
  }

  private static String preferredHeading(String first, String second) {
    if (first.isBlank()) return second;
    if (second.isBlank() || second.startsWith(first + " / ")) return first;
    return first;
  }

  private static int lineAt(int[] offsets, int position) {
    int found = Arrays.binarySearch(offsets, position);
    return found >= 0 ? found + 1 : -found - 1;
  }
}
