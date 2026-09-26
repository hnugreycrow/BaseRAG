package com.hnu.backend.document.parser;

import static org.junit.jupiter.api.Assertions.*;

import com.hnu.backend.common.exception.ApiException;
import com.hnu.backend.rag.config.RagProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocumentParserRegistryTest {
  private final MarkdownChunker chunker = new MarkdownChunker(new RagProperties());
  private final DocumentParserRegistry registry = new DocumentParserRegistry(chunker);

  @Test
  void extractsPdfTextWithPageLocations() throws IOException {
    byte[] bytes;
    try (PDDocument pdf = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (String text : List.of("First page", "Second page")) {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(50, 700);
          content.showText(text);
          content.endText();
        }
      }
      pdf.save(output);
      bytes = output.toByteArray();
    }
    assertEquals(DocumentFormat.PDF, registry.verify("a.pdf", bytes));
    var blocks = registry.parser(DocumentFormat.PDF).parse(bytes);
    assertEquals(List.of(1, 2), blocks.stream().map(b -> b.source().start()).toList());
    assertTrue(blocks.get(1).content().contains("Second page"));
    assertTrue(
        chunker.pack(blocks).stream()
            .allMatch(c -> c.source().unit() == StructuredBlock.SourceSpan.Unit.PAGE));
  }

  @Test
  void extractsDocxHeadingListTableAndChineseWithParagraphLocations() throws IOException {
    byte[] bytes;
    try (XWPFDocument doc = new XWPFDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var heading = doc.createParagraph();
      heading.setStyle("Heading1");
      heading.createRun().setText("使用说明");
      doc.createParagraph().createRun().setText("中文正文");
      var list = doc.createParagraph();
      list.setNumID(BigInteger.ONE);
      list.createRun().setText("列表内容");
      var table = doc.createTable(1, 2);
      table.getRow(0).getCell(0).setText("字段");
      table.getRow(0).getCell(1).setText("数值");
      doc.write(output);
      bytes = output.toByteArray();
    }
    assertEquals(DocumentFormat.DOCX, registry.verify("a.docx", bytes));
    var blocks = registry.parser(DocumentFormat.DOCX).parse(bytes);
    assertEquals(StructuredBlock.Kind.HEADING, blocks.getFirst().kind());
    assertTrue(blocks.stream().anyMatch(b -> b.content().contains("中文正文")));
    assertTrue(
        blocks.stream()
            .anyMatch(b -> b.kind() == StructuredBlock.Kind.LIST && b.content().contains("列表内容")));
    assertTrue(
        blocks.stream()
            .anyMatch(b -> b.kind() == StructuredBlock.Kind.TABLE && b.content().contains("字段")));
    assertTrue(
        blocks.stream()
            .allMatch(b -> b.source().unit() == StructuredBlock.SourceSpan.Unit.PARAGRAPH));
  }

  @Test
  void rejectsMismatchedCorruptAndTextlessFiles() throws IOException {
    assertCode(
        "INVALID_FILE_FORMAT",
        () -> registry.verify("fake.pdf", "hello".getBytes(StandardCharsets.UTF_8)));
    assertCode(
        "INVALID_PDF",
        () -> registry.verify("broken.pdf", "%PDF-broken".getBytes(StandardCharsets.US_ASCII)));
    assertCode(
        "INVALID_FILE_FORMAT",
        () ->
            registry.verify("old.docx", new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0}));
    assertCode(
        "INVALID_DOCX", () -> registry.verify("broken.docx", new byte[] {'P', 'K', 3, 4, 0, 0}));
    assertCode("INVALID_UTF8", () -> registry.verify("broken.md", new byte[] {(byte) 0xff}));
    try (PDDocument pdf = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      pdf.addPage(new PDPage());
      pdf.save(output);
      assertCode("PDF_NO_TEXT", () -> registry.verify("scan.pdf", output.toByteArray()));
    }
  }

  @Test
  void rejectsEncryptedPdf() throws IOException {
    try (PDDocument pdf = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      pdf.addPage(new PDPage());
      var policy =
          new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
      policy.setEncryptionKeyLength(128);
      pdf.protect(policy);
      pdf.save(output);
      assertCode("ENCRYPTED_PDF", () -> registry.verify("encrypted.pdf", output.toByteArray()));
    }
  }

  @Test
  void rejectsOversizedDocxExpansionBeforePoi() throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("a".repeat(2_000_000).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    assertCode("DOCX_ZIP_LIMIT", () -> registry.verify("bomb.docx", output.toByteArray()));
  }

  private static void assertCode(String code, Runnable action) {
    assertEquals(code, assertThrows(ApiException.class, action::run).code());
  }
}
