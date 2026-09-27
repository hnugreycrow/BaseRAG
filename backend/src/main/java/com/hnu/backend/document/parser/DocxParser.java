package com.hnu.backend.document.parser;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** 按正文元素顺序提取标题、段落、列表和表格。 */
public final class DocxParser implements DocumentParser {
  private final int maxSize;

  /** 创建指定最大结构块字符数的解析器。 */
  public DocxParser(int maxSize) {
    this.maxSize = maxSize;
  }

  /** 返回本解析器处理的格式。 */
  @Override
  public DocumentFormat format() {
    return DocumentFormat.DOCX;
  }

  /** 解析已读取的文件字节，并生成带来源位置的结构块。 */
  @Override
  public List<StructuredBlock> parse(byte[] bytes) {
    verifyZip(bytes);
    List<StructuredBlock> blocks = new ArrayList<>();
    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
      // 标题路径用于检索上下文；来源序号按正文元素递增，包含空段落。
      List<String> headings = new ArrayList<>();
      int ordinal = 0;
      int section = 0;
      int offset = 0;
      for (IBodyElement element : document.getBodyElements()) {
        ordinal++;
        String text;
        StructuredBlock.Kind kind;
        if (element instanceof XWPFParagraph paragraph) {
          text = paragraph.getText().strip();
          int level = headingLevel(paragraph);
          if (level > 0 && !text.isBlank()) {
            while (headings.size() >= level) {
              headings.removeLast();
            }
            headings.add(text);
            section++;
            kind = StructuredBlock.Kind.HEADING;
          } else {
            kind =
                paragraph.getNumID() == null
                    ? StructuredBlock.Kind.TEXT
                    : StructuredBlock.Kind.LIST;
          }
        } else if (element instanceof XWPFTable table) {
          text =
              table.getRows().stream()
                  .map(
                      row ->
                          row.getTableCells().stream()
                              .map(cell -> cell.getText().strip())
                              .reduce((a, b) -> a + " | " + b)
                              .orElse(""))
                  .reduce((a, b) -> a + "\n" + b)
                  .orElse("")
                  .strip();
          kind = StructuredBlock.Kind.TABLE;
        } else {
          continue;
        }
        if (!text.isBlank()) {
          String heading = String.join(" / ", headings);
          StructuredBlocks.append(
              blocks,
              kind,
              section,
              heading,
              List.copyOf(headings),
              text,
              StructuredBlock.SourceSpan.Unit.PARAGRAPH,
              ordinal,
              offset,
              maxSize);
        }
        offset += text.length() + 1;
      }
    } catch (IOException | RuntimeException e) {
      if (e instanceof ApiException api) {
        throw api;
      }
      throw ApiException.bad(ErrorCode.INVALID_DOCX, "DOCX 文件无法解析");
    }
    if (blocks.stream().noneMatch(b -> b.kind() != StructuredBlock.Kind.HEADING)) {
      throw ApiException.bad(ErrorCode.EMPTY_DOCUMENT, "文档没有可用文本");
    }
    return blocks;
  }

  /** 从 Word 内置标题样式提取 1 至 6 级标题层级。 */
  private static int headingLevel(XWPFParagraph paragraph) {
    String style = paragraph.getStyle();
    if (style == null) {
      return 0;
    }
    String normalized = style.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
    if (!normalized.startsWith("heading") || normalized.length() != 8) {
      return 0;
    }
    char level = normalized.charAt(7);
    return level >= '1' && level <= '6' ? level - '0' : 0;
  }

  /** 校验 DOCX 必需成员及解压大小、文件数，避免异常膨胀的压缩包。 */
  private static void verifyZip(byte[] bytes) {
    boolean contentTypes = false;
    boolean main = false;
    // 同时限制 ZIP 条目数和累计解压字节数。
    int count = 0;
    long total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        if (++count > 2000) {
          throw ApiException.bad(ErrorCode.DOCX_ZIP_LIMIT, "DOCX 包含过多文件");
        }
        String name = entry.getName();
        if ("[Content_Types].xml".equals(name)) {
          contentTypes = true;
        }
        if ("word/document.xml".equals(name)) {
          main = true;
        }
        int read;
        while ((read = zip.read(buffer)) != -1) {
          total += read;
          if (total > 100L * 1024 * 1024 || total > (long) bytes.length * 100) {
            throw ApiException.bad(ErrorCode.DOCX_ZIP_LIMIT, "DOCX 解压内容超出安全限制");
          }
        }
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw ApiException.bad(ErrorCode.INVALID_DOCX, "DOCX 压缩包损坏");
    }
    if (!contentTypes || !main) {
      throw ApiException.bad(ErrorCode.INVALID_DOCX, "缺少 Word 主文档结构");
    }
  }
}
