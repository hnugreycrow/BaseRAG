package com.hnu.backend.document.parser;

import com.hnu.backend.shared.error.ApiException;
import com.hnu.backend.shared.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** 按文件格式注册解析器，并在上传前核对扩展名、文件签名及可解析内容。 */
public final class DocumentParserRegistry {
  /** PDF 文件的固定开头，用于拒绝伪造扩展名。 */
  private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  /** DOCX 使用 ZIP 容器，此处先核对本地文件头。 */
  private static final byte[] ZIP_HEADER = {'P', 'K', 3, 4};

  /** 旧版 DOC 的 OLE 文件头，当前阶段明确拒绝。 */
  private static final byte[] OLE_HEADER = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0};

  /** 格式与解析器的固定映射，上传校验和后台分块共用。 */
  private final Map<DocumentFormat, DocumentParser> parsers;

  /**
   * 注册三种格式的解析器，并沿用统一的最大结构块长度。
   *
   * @param markdown Markdown 解析及通用打包入口
   */
  public DocumentParserRegistry(MarkdownChunker markdown) {
    int maxSize = markdown.maxSize();
    parsers =
        Map.of(
            DocumentFormat.MARKDOWN, new MarkdownParser(markdown),
            DocumentFormat.PDF, new PdfParser(maxSize),
            DocumentFormat.DOCX, new DocxParser(maxSize));
  }

  /**
   * 获取指定格式的解析器。
   *
   * @param format 已确认的文档格式
   * @return 对应格式的解析器
   */
  public DocumentParser parser(DocumentFormat format) {
    return parsers.get(format);
  }

  /**
   * 核对扩展名、文件头和内部内容，拒绝不匹配或无法解析的文件。
   *
   * @param name 原文件名
   * @param bytes 文件内容
   * @return 通过校验的文档格式
   */
  public DocumentFormat verify(String name, byte[] bytes) {
    DocumentFormat format = DocumentFormat.fromName(name);
    boolean pdf = startsWith(bytes, PDF_HEADER);
    boolean zip = startsWith(bytes, ZIP_HEADER);
    boolean ole = startsWith(bytes, OLE_HEADER);
    if (ole
        || (format == DocumentFormat.PDF && !pdf)
        || (format == DocumentFormat.DOCX && !zip)
        || (format == DocumentFormat.MARKDOWN && (pdf || zip))) {
      throw ApiException.bad(ErrorCode.INVALID_FILE_FORMAT, "文件内容与扩展名不符");
    }
    parser(format).parse(bytes);
    return format;
  }

  /** 判断文件内容是否具有指定的二进制文件头。 */
  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length
        && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
  }

  /** 将严格校验后的 UTF-8 Markdown 转为带行号的结构块。 */
  private record MarkdownParser(MarkdownChunker chunker) implements DocumentParser {
    /** 返回本解析器处理的格式。 */
    @Override
    public DocumentFormat format() {
      return DocumentFormat.MARKDOWN;
    }

    /** 解析已读取的文件字节，并生成带来源位置的结构块。 */
    @Override
    public List<StructuredBlock> parse(byte[] bytes) {
      try {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.indexOf('\0') >= 0)
          throw ApiException.bad(ErrorCode.INVALID_FILE, "Markdown 不能包含二进制空字符");
        if (text.isBlank()) throw ApiException.bad(ErrorCode.EMPTY_DOCUMENT, "文档没有可用文本");
        return chunker.parse(text);
      } catch (java.nio.charset.CharacterCodingException e) {
        throw ApiException.bad(ErrorCode.INVALID_UTF8, "请使用 UTF-8 编码的 Markdown 文件");
      }
    }
  }

  /** 逐页提取文本，保留 PDF 页码作为来源位置。 */
  private static final class PdfParser implements DocumentParser {
    private final int maxSize;

    private PdfParser(int maxSize) {
      this.maxSize = maxSize;
    }

    /** 返回本解析器处理的格式。 */
    @Override
    public DocumentFormat format() {
      return DocumentFormat.PDF;
    }

    /** 解析已读取的文件字节，并生成带来源位置的结构块。 */
    @Override
    public List<StructuredBlock> parse(byte[] bytes) {
      List<StructuredBlock> blocks = new ArrayList<>();
      try (PDDocument document = Loader.loadPDF(bytes)) {
        if (document.isEncrypted()) throw ApiException.bad(ErrorCode.ENCRYPTED_PDF, "不支持加密 PDF");
        PDFTextStripper stripper = new PDFTextStripper();
        // 跨页字符偏移只用于结构块排序；引用位置仍以页码为准。
        int offset = 0;
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
          stripper.setStartPage(page);
          stripper.setEndPage(page);
          String text = stripper.getText(document).strip();
          if (!text.isBlank()) {
            append(
                blocks,
                StructuredBlock.Kind.TEXT,
                page,
                "",
                List.of(),
                text,
                StructuredBlock.SourceSpan.Unit.PAGE,
                page,
                offset,
                maxSize);
          }
          offset += text.length() + 1;
        }
      } catch (InvalidPasswordException e) {
        throw ApiException.bad(ErrorCode.ENCRYPTED_PDF, "不支持加密 PDF");
      } catch (IOException | RuntimeException e) {
        if (e instanceof ApiException api) throw api;
        throw ApiException.bad(ErrorCode.INVALID_PDF, "PDF 文件无法解析");
      }
      if (blocks.isEmpty()) throw ApiException.bad(ErrorCode.PDF_NO_TEXT, "PDF 没有可提取文本");
      return blocks;
    }
  }

  /** 按正文元素顺序提取标题、段落、列表和表格。 */
  private static final class DocxParser implements DocumentParser {
    private final int maxSize;

    private DocxParser(int maxSize) {
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
              while (headings.size() >= level) headings.removeLast();
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
            append(
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
        if (e instanceof ApiException api) throw api;
        throw ApiException.bad(ErrorCode.INVALID_DOCX, "DOCX 文件无法解析");
      }
      if (blocks.stream().noneMatch(b -> b.kind() != StructuredBlock.Kind.HEADING))
        throw ApiException.bad(ErrorCode.EMPTY_DOCUMENT, "文档没有可用文本");
      return blocks;
    }

    /** 从 Word 内置标题样式提取 1 至 6 级标题层级。 */
    private static int headingLevel(XWPFParagraph paragraph) {
      String style = paragraph.getStyle();
      if (style == null) return 0;
      String normalized = style.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
      if (!normalized.startsWith("heading") || normalized.length() != 8) return 0;
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
          if (++count > 2000) throw ApiException.bad(ErrorCode.DOCX_ZIP_LIMIT, "DOCX 包含过多文件");
          String name = entry.getName();
          if ("[Content_Types].xml".equals(name)) contentTypes = true;
          if ("word/document.xml".equals(name)) main = true;
          int read;
          while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > 100L * 1024 * 1024 || total > (long) bytes.length * 100)
              throw ApiException.bad(ErrorCode.DOCX_ZIP_LIMIT, "DOCX 解压内容超出安全限制");
          }
          zip.closeEntry();
        }
      } catch (IOException e) {
        throw ApiException.bad(ErrorCode.INVALID_DOCX, "DOCX 压缩包损坏");
      }
      if (!contentTypes || !main) throw ApiException.bad(ErrorCode.INVALID_DOCX, "缺少 Word 主文档结构");
    }
  }

  /** 将过长的单个文本元素拆成有界结构块，来源位置保持原页或原段落。 */
  private static void append(
      List<StructuredBlock> blocks,
      StructuredBlock.Kind kind,
      int section,
      String heading,
      List<String> path,
      String text,
      StructuredBlock.SourceSpan.Unit unit,
      int position,
      int offset,
      int maxSize) {
    // 防止单个 PDF 页或 DOCX 段落超过后续打包器的最大块长度。
    int budget = Math.max(1, maxSize);
    for (int start = 0; start < text.length(); start += budget) {
      int end = Math.min(text.length(), start + budget);
      String part = text.substring(start, end);
      blocks.add(
          new StructuredBlock(
              kind,
              section,
              heading,
              path,
              part,
              part,
              new StructuredBlock.SourceSpan(
                  unit, position, position, offset + start, offset + end),
              text.length() > budget));
    }
  }
}
