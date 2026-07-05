package com.example.myllm.support;

import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import com.example.myllm.support.document.DocumentTextRenderer;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.FileContentProbe.BinaryFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

/**
 * 将支持的上传文件解析为保留标题、段落、列表、表格和代码结构的文档块。
 */
public final class FileTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileTextExtractor.class);

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s++(.++)$");
    private static final Pattern MARKDOWN_LIST_ITEM = Pattern.compile("^\\s*+(?:[-*+] |\\d++[.)]\\s++)(.++)$");

    private FileTextExtractor() {
    }

    /**
     * 兼容旧调用方式，将结构化解析结果渲染为纯文本。
     *
     * @param file 上传文件
     * @return 文件的可读文本内容
     */
    public static String extract(MultipartFile file) {
        return renderPlainText(extractDocument(file).blocks());
    }

    /**
     * 按扩展名选择解析逻辑并保留可识别的文档结构。
     *
     * @param file 上传文件
     * @return 结构化解析结果
     * @throws IllegalArgumentException 文件类型不受支持或内容无法解析时抛出
     */
    public static ParsedDocument extractDocument(MultipartFile file) {
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        if (FileContentProbe.isWordLockFile(fileName)) {
            throw new IllegalArgumentException("检测到 Word 临时锁文件（~$ 开头），请关闭 Word 后上传正式文档");
        }

        String extension = FileContentProbe.extensionOf(fileName);
        byte[] bytes = FileContentProbe.readBytes(file);
        BinaryFormat format = FileContentProbe.detect(bytes);
        if (format == BinaryFormat.EMPTY) {
            throw new IllegalArgumentException("文件内容为空，无法解析: " + fileName);
        }

        try {
            List<DocumentBlock> blocks = switch (extension) {
                case "docx" -> parseDocxLike(fileName, bytes, format);
                case "doc" -> parseLegacyWord(bytes, format);
                case "md" -> parseMarkdown(FileContentProbe.decodePlainText(bytes));
                case "csv" -> parseLines(FileContentProbe.decodePlainText(bytes), DocumentBlockType.TABLE_ROW);
                case "txt", "json", "xml", "html", "log" -> parseParagraphs(FileContentProbe.decodePlainText(bytes));
                default -> throw new IllegalArgumentException("暂不支持的文件类型: ." + extension
                        + "，请上传 .txt/.md/.docx 等文本类文件");
            };
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("文件未解析出有效文本内容: " + fileName);
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("extension", extension);
            metadata.put("detectedFormat", format.name());
            return new ParsedDocument(fileName, blocks, Map.copyOf(metadata));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw toParseException(fileName, extension, format, e);
        }
    }

    /**
     * 从文件名提取小写扩展名（不含点）。
     */
    public static String extensionOf(String fileName) {
        return FileContentProbe.extensionOf(fileName);
    }

    private static List<DocumentBlock> parseDocxLike(String fileName, byte[] bytes, BinaryFormat format)
            throws IOException {
        return switch (format) {
            case ZIP -> extractDocx(bytes);
            case OLE -> {
                log.info("文件扩展名为 .docx，但内容为旧版 Word (.doc) 二进制，按 .doc 解析: {}", fileName);
                yield extractLegacyDoc(bytes, Map.of("parserFallback", "docx-extension-ole-doc"));
            }
            case PLAIN_TEXT, RTF -> {
                log.info("文件扩展名为 .docx，但内容为纯文本，按文本段落解析: {}", fileName);
                yield parseParagraphs(FileContentProbe.decodePlainText(bytes));
            }
            default -> throw new IllegalArgumentException(
                    "文件扩展名为 .docx，但内容不是有效的 Office 文档。"
                            + " 请确认未上传损坏文件、旧版 .doc 误命名，或 Word 临时锁文件（~$ 开头）");
        };
    }

    private static List<DocumentBlock> parseLegacyWord(byte[] bytes, BinaryFormat format) throws IOException {
        if (format == BinaryFormat.OLE) {
            return extractLegacyDoc(bytes, Map.of());
        }
        if (format == BinaryFormat.PLAIN_TEXT) {
            return parseParagraphs(FileContentProbe.decodePlainText(bytes));
        }
        throw new IllegalArgumentException("文件扩展名为 .doc，但内容不是旧版 Word 二进制格式");
    }

    private static List<DocumentBlock> extractDocx(byte[] bytes) throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream(bytes);
                XWPFDocument document = new XWPFDocument(inputStream)) {
            return extractDocxBody(document);
        } catch (NotOfficeXmlFileException e) {
            throw new IllegalArgumentException(
                    "文件不是有效的 .docx（Office Open XML）格式，请用 Word/WPS 重新另存为 .docx", e);
        }
    }

    @SuppressWarnings("java:S3776") // Body elements intentionally preserve their source order and type.
    private static List<DocumentBlock> extractDocxBody(XWPFDocument document) {
        List<DocumentBlock> blocks = new ArrayList<>();
        int sourceIndex = 0;
        for (IBodyElement bodyElement : document.getBodyElements()) {
            if (bodyElement instanceof XWPFParagraph paragraph) {
                String text = paragraph.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                Integer headingLevel = headingLevel(paragraph, document);
                DocumentBlockType type = paragraphType(paragraph, headingLevel);
                blocks.add(new DocumentBlock(type, text, headingLevel, sourceIndex++));
            } else if (bodyElement instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    String rowText = row.getTableCells().stream()
                            .map(XWPFTableCell::getText)
                            .map(String::trim)
                            .reduce((left, right) -> left + " | " + right)
                            .orElse("");
                    if (!rowText.isBlank()) {
                        blocks.add(new DocumentBlock(
                                DocumentBlockType.TABLE_ROW, rowText, null, sourceIndex++));
                    }
                }
            }
        }
        return blocks;
    }

    private static DocumentBlockType paragraphType(XWPFParagraph paragraph, Integer headingLevel) {
        if (headingLevel != null) {
            return DocumentBlockType.HEADING;
        }
        return paragraph.getNumID() != null ? DocumentBlockType.LIST_ITEM : DocumentBlockType.PARAGRAPH;
    }

    private static List<DocumentBlock> extractLegacyDoc(byte[] bytes, Map<String, Object> metadata) throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream(bytes);
                HWPFDocument document = new HWPFDocument(inputStream);
                WordExtractor extractor = new WordExtractor(document)) {
            String text = extractor.getText();
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("旧版 Word 文档未解析出有效文本");
            }
            List<DocumentBlock> blocks = parseParagraphs(text);
            if (!metadata.isEmpty()) {
                log.info("旧版 Word 解析完成 parserFallback={}", metadata);
            }
            return blocks;
        }
    }

    @SuppressWarnings({"java:S135", "java:S3776"}) // Markdown parsing is a single-pass state machine.
    public static List<DocumentBlock> parseMarkdown(String content) {
        List<DocumentBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        StringBuilder code = new StringBuilder();
        boolean inCode = false;
        int sourceIndex = 0;
        for (String line : normalizeLines(content)) {
            if (line.stripLeading().startsWith("```")) {
                if (inCode) {
                    blocks.add(new DocumentBlock(DocumentBlockType.CODE, code.toString(), null, sourceIndex++));
                    code.setLength(0);
                    inCode = false;
                } else {
                    sourceIndex = flushParagraph(blocks, paragraph, sourceIndex);
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                if (!code.isEmpty()) {
                    code.append('\n');
                }
                code.append(line);
                continue;
            }

            Matcher heading = MARKDOWN_HEADING.matcher(line);
            Matcher listItem = MARKDOWN_LIST_ITEM.matcher(line);
            if (heading.matches()) {
                sourceIndex = flushParagraph(blocks, paragraph, sourceIndex);
                blocks.add(new DocumentBlock(
                        DocumentBlockType.HEADING,
                        heading.group(2),
                        heading.group(1).length(),
                        sourceIndex++));
            } else if (listItem.matches()) {
                sourceIndex = flushParagraph(blocks, paragraph, sourceIndex);
                blocks.add(new DocumentBlock(
                        DocumentBlockType.LIST_ITEM, listItem.group(1), null, sourceIndex++));
            } else if (line.isBlank()) {
                sourceIndex = flushParagraph(blocks, paragraph, sourceIndex);
            } else if (line.stripLeading().startsWith(">")) {
                sourceIndex = flushParagraph(blocks, paragraph, sourceIndex);
                blocks.add(new DocumentBlock(
                        DocumentBlockType.QUOTE,
                        line.stripLeading().substring(1).stripLeading(),
                        null,
                        sourceIndex++));
            } else if (line.contains("|") && line.strip().startsWith("|")) {
                sourceIndex = flushParagraph(blocks, paragraph, sourceIndex);
                blocks.add(new DocumentBlock(DocumentBlockType.TABLE_ROW, line.strip(), null, sourceIndex++));
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(line);
            }
        }
        if (inCode && !code.isEmpty()) {
            blocks.add(new DocumentBlock(DocumentBlockType.CODE, code.toString(), null, sourceIndex++));
        }
        flushParagraph(blocks, paragraph, sourceIndex);
        return blocks;
    }

    private static List<DocumentBlock> parseParagraphs(String content) {
        List<DocumentBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int sourceIndex = 0;
        for (String line : normalizeLines(content)) {
            if (line.isBlank()) {
                sourceIndex = flushParagraph(blocks, paragraph, sourceIndex);
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(line);
            }
        }
        flushParagraph(blocks, paragraph, sourceIndex);
        return blocks;
    }

    private static List<DocumentBlock> parseLines(String content, DocumentBlockType type) {
        List<DocumentBlock> blocks = new ArrayList<>();
        int sourceIndex = 0;
        for (String line : normalizeLines(content)) {
            if (!line.isBlank()) {
                blocks.add(new DocumentBlock(type, line, null, sourceIndex++));
            }
        }
        return blocks;
    }

    private static int flushParagraph(
            List<DocumentBlock> blocks, StringBuilder paragraph, int sourceIndex) {
        if (!paragraph.isEmpty()) {
            blocks.add(new DocumentBlock(
                    DocumentBlockType.PARAGRAPH, paragraph.toString(), null, sourceIndex++));
            paragraph.setLength(0);
        }
        return sourceIndex;
    }

    private static Integer headingLevel(XWPFParagraph paragraph, XWPFDocument document) {
        if (paragraph.getCTP().getPPr() != null && paragraph.getCTP().getPPr().isSetOutlineLvl()) {
            int outlineLevel = paragraph.getCTP().getPPr().getOutlineLvl().getVal().intValue();
            if (outlineLevel >= 0 && outlineLevel <= 5) {
                return outlineLevel + 1;
            }
        }

        String styleId = paragraph.getStyle();
        Integer level = headingLevelFromStyle(styleId);
        if (level != null) {
            return level;
        }
        if (styleId != null && document.getStyles() != null) {
            XWPFStyle style = document.getStyles().getStyle(styleId);
            if (style != null) {
                return headingLevelFromStyle(style.getName());
            }
        }
        return null;
    }

    private static Integer headingLevelFromStyle(String style) {
        if (style == null || style.isBlank()) {
            return null;
        }
        String normalized = style.strip().toLowerCase(Locale.ROOT);
        if ("title".equals(normalized) || "标题".equals(normalized)) {
            return 1;
        }
        int headingIndex = normalized.indexOf("heading");
        int chineseHeadingIndex = normalized.indexOf("标题");
        int markerEnd = -1;
        if (headingIndex >= 0) {
            markerEnd = headingIndex + "heading".length();
        } else if (chineseHeadingIndex >= 0) {
            markerEnd = chineseHeadingIndex + "标题".length();
        }
        for (int i = markerEnd; i >= 0 && i < normalized.length(); i++) {
            char value = normalized.charAt(i);
            if (value >= '1' && value <= '6') {
                return value - '0';
            }
            if (value != ' ' && value != '_' && value != '-') {
                break;
            }
        }
        return null;
    }

    private static String[] normalizeLines(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n').split("\\n", -1);
    }

    private static String renderPlainText(List<DocumentBlock> blocks) {
        return DocumentTextRenderer.renderRaw(blocks);
    }

    private static IllegalArgumentException toParseException(
            String fileName, String extension, BinaryFormat format, Exception cause) {
        log.warn("文件解析失败 fileName={} extension={} detectedFormat={} reason={}",
                fileName, extension, format, cause.getMessage(), cause);
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        if (message.contains("OOXML") || message.contains("Office Open XML")) {
            return new IllegalArgumentException(
                    "文件「" + fileName + "」不是有效的 .docx 格式。"
                            + " 常见原因：旧版 .doc 误改后缀、文件损坏、或上传了 Word 临时锁文件。"
                            + " 请用 Word/WPS 打开后另存为 .docx，或改为上传 .txt/.md", cause);
        }
        return new IllegalArgumentException("解析文件内容失败: " + fileName + "（" + message + "）", cause);
    }
}
