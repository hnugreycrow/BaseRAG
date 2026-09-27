package com.hnu.backend.document.parser;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.common.exception.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** 逐页提取文本，保留 PDF 页码作为来源位置。 */
public final class PdfParser implements DocumentParser {
  private final int maxSize;

  /** 创建指定最大结构块字符数的解析器。 */
  public PdfParser(int maxSize) {
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
      if (document.isEncrypted()) {
        throw ApiException.bad(ErrorCode.ENCRYPTED_PDF, "不支持加密 PDF");
      }
      PDFTextStripper stripper = new PDFTextStripper();
      // 跨页字符偏移只用于结构块排序；引用位置仍以页码为准。
      int offset = 0;
      for (int page = 1; page <= document.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(document).strip();
        if (!text.isBlank()) {
          StructuredBlocks.append(
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
      if (e instanceof ApiException api) {
        throw api;
      }
      throw ApiException.bad(ErrorCode.INVALID_PDF, "PDF 文件无法解析");
    }
    if (blocks.isEmpty()) {
      throw ApiException.bad(ErrorCode.PDF_NO_TEXT, "PDF 没有可提取文本");
    }
    return blocks;
  }
}
