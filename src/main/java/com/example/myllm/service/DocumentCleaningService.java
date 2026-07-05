package com.example.myllm.service;

import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.CleaningReport;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import com.example.myllm.support.document.DocumentBlockNormalizer;
import com.example.myllm.support.document.DocumentTextRenderer;
import com.example.myllm.support.document.ParsedDocument;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 保守型文档数据清理服务。
 *
 * <p>负责 Unicode NFC 规范化、不可见/控制字符移除、普通文本空白规范化、
 * 完全重复长段落去重以及清理后的质量门禁。代码块内部空白会被保留。</p>
 */
@Service
public class DocumentCleaningService {

    private static final Pattern INLINE_WHITESPACE = Pattern.compile("[\\p{Zs}\\t]+");
    private static final int MIN_DEDUPLICATE_LENGTH = 20;
    private static final String PERCENT_FORMAT = "%.1f%%";

    private final String cleanerVersion;
    private final int minCleanedChars;
    private final double maxRemovalRatio;

    public DocumentCleaningService(
            @Value("${rag.cleaning.version:conservative-v2}") String cleanerVersion,
            @Value("${rag.cleaning.quality.min-cleaned-chars:50}") int minCleanedChars,
            @Value("${rag.cleaning.quality.max-removal-ratio:0.70}") double maxRemovalRatio) {
        this.cleanerVersion = cleanerVersion;
        this.minCleanedChars = Math.max(1, minCleanedChars);
        this.maxRemovalRatio = Math.max(0.0, Math.min(1.0, maxRemovalRatio));
    }

    /**
     * 执行确定性的保守清理并生成审计报告。
     *
     * @param document 文件解析结果
     * @return 同时包含清理前后文本与统计报告的文档
     * @throws IllegalArgumentException 文档为空或未通过质量门禁时抛出
     */
    @SuppressWarnings({"java:S3776", "java:S135"}) // Ordered cleanup stages use early continue for clarity.
    public CleanedDocument clean(ParsedDocument document) {
        if (document == null || document.blocks().isEmpty()) {
            throw new IllegalArgumentException("文档解析结果为空，无法清理");
        }

        String rawContent = DocumentTextRenderer.renderRaw(document.blocks());
        Map<String, Integer> affected = new LinkedHashMap<>();
        List<DocumentBlock> normalizedBlocks = DocumentBlockNormalizer.normalize(document.blocks(), affected);
        Set<String> seenParagraphs = new LinkedHashSet<>();
        List<DocumentBlock> cleanedBlocks = new ArrayList<>();
        int duplicateBlocks = 0;

        for (DocumentBlock block : normalizedBlocks) {
            RuleResult result = cleanBlock(block);
            result.affectedCounts().forEach((key, value) -> affected.merge(key, value, Integer::sum));
            if (result.content().isBlank()) {
                affected.merge("empty-block-removed", 1, Integer::sum);
                continue;
            }

            if (isDuplicateCandidate(block.type(), result.content()) && !seenParagraphs.add(result.content())) {
                duplicateBlocks++;
                continue;
            }

            cleanedBlocks.add(new DocumentBlock(
                    block.type(), result.content(), block.headingLevel(), block.sourceIndex()));
        }

        String cleanedContent = DocumentTextRenderer.renderCleaned(cleanedBlocks).trim();
        int rawChars = rawContent.codePointCount(0, rawContent.length());
        int cleanedChars = cleanedContent.codePointCount(0, cleanedContent.length());
        int removedChars = Math.max(0, rawChars - cleanedChars);
        double removalRatio = rawChars == 0 ? 0.0 : removedChars / (double) rawChars;
        List<String> warnings = new ArrayList<>();
        if (removalRatio >= 0.40) {
            warnings.add("清理后内容减少比例较高: "
                    + String.format(PERCENT_FORMAT, removalRatio * 100));
        }
        if (duplicateBlocks > 0) {
            warnings.add("已移除完全重复段落: " + duplicateBlocks);
        }

        validateQuality(cleanedContent, removalRatio);
        CleaningReport report = new CleaningReport(
                cleanerVersion,
                rawChars,
                cleanedChars,
                removedChars,
                removalRatio,
                duplicateBlocks,
                affected,
                warnings);
        return new CleanedDocument(cleanedBlocks, rawContent, cleanedContent, report);
    }

    private RuleResult cleanBlock(DocumentBlock block) {
        Map<String, Integer> affected = new LinkedHashMap<>();
        String content = block.content();

        String normalized = Normalizer.normalize(content, Normalizer.Form.NFC);
        if (!normalized.equals(content)) {
            affected.put("unicode-nfc-normalized", 1);
        }

        String lineNormalized = normalizeCodePoints(normalized, affected);
        String cleaned = block.type() == DocumentBlockType.CODE
                ? trimBlankBoundaryLines(lineNormalized)
                : normalizeTextLines(lineNormalized, affected);
        return new RuleResult(cleaned, affected);
    }

    @SuppressWarnings("java:S135") // Guard clauses keep the character filters independent and readable.
    private static String normalizeCodePoints(String normalized, Map<String, Integer> affected) {
        StringBuilder safe = new StringBuilder(normalized.length());
        int removedControls = 0;
        int normalizedSpaces = 0;
        for (int i = 0; i < normalized.length(); i = normalized.offsetByCodePoints(i, 1)) {
            int codePoint = normalized.codePointAt(i);
            if (codePoint == 0xFEFF || codePoint == 0x200B || codePoint == 0x00AD) {
                removedControls++;
                continue;
            }
            if (codePoint == 0x00A0) {
                safe.append(' ');
                normalizedSpaces++;
                continue;
            }
            if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t') {
                removedControls++;
                continue;
            }
            safe.appendCodePoint(codePoint);
        }
        if (removedControls > 0) {
            affected.put("control-character-removed", removedControls);
        }
        if (normalizedSpaces > 0) {
            affected.put("non-breaking-space-normalized", normalizedSpaces);
        }
        return safe.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String normalizeTextLines(String text, Map<String, Integer> affected) {
        List<String> compactLines = new ArrayList<>();
        for (String line : text.split("\\n", -1)) {
            String compact = INLINE_WHITESPACE.matcher(line.strip()).replaceAll(" ");
            if (!line.equals(compact)) {
                affected.merge("whitespace-normalized", 1, Integer::sum);
            }
            if (!compact.isBlank()) {
                compactLines.add(compact);
            }
        }
        if (compactLines.size() > 1) {
            affected.merge("soft-line-break-normalized", compactLines.size() - 1, Integer::sum);
        }
        return String.join(" ", compactLines);
    }

    private void validateQuality(String cleanedContent, double removalRatio) {
        int cleanedCodePoints = cleanedContent.codePointCount(0, cleanedContent.length());
        if (cleanedCodePoints < minCleanedChars) {
            throw new IllegalArgumentException(
                    "清理后有效内容过少: " + cleanedCodePoints + " 字符，最少需要 " + minCleanedChars + " 字符");
        }
        if (removalRatio > maxRemovalRatio) {
            throw new IllegalArgumentException(
                    "清理删除比例异常: " + String.format(PERCENT_FORMAT, removalRatio * 100)
                            + "，超过允许值 " + String.format(PERCENT_FORMAT, maxRemovalRatio * 100));
        }
        long replacementChars = cleanedContent.chars().filter(ch -> ch == 0xFFFD).count();
        if (replacementChars > 0 && replacementChars / (double) cleanedCodePoints > 0.01) {
            throw new IllegalArgumentException("文档疑似存在编码或乱码问题，Unicode 替换字符比例超过 1%");
        }
    }

    private static boolean isDuplicateCandidate(DocumentBlockType type, String content) {
        return type == DocumentBlockType.PARAGRAPH && content.length() >= MIN_DEDUPLICATE_LENGTH;
    }

    private static String trimBlankBoundaryLines(String content) {
        int start = 0;
        int end = content.length();
        while (start < end && (content.charAt(start) == '\n' || content.charAt(start) == '\r')) {
            start++;
        }
        while (end > start && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
            end--;
        }
        return content.substring(start, end);
    }

    private record RuleResult(String content, Map<String, Integer> affectedCounts) {
    }
}
