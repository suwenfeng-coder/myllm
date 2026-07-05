package com.example.myllm.support.chunking;

import com.example.myllm.support.document.CleanedDocument;
import com.example.myllm.support.document.DocumentBlock;
import com.example.myllm.support.document.DocumentBlockType;
import com.example.myllm.support.document.DocumentTextRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于文档结构的分块器。
 *
 * <p>利用解析阶段保留的标题、段落、列表、表格、代码和引用块进行切分，
 * 章节路径单独写入元数据，不污染 embedding 文本。</p>
 */
@Component
public class StructureDocumentChunker implements DocumentChunker {

    private final int maxSize;
    private final int minSize;
    private final RecursiveDocumentChunker recursiveChunker;

    public StructureDocumentChunker(
            @Value("${rag.chunking.document.max-size:1400}") int maxSize,
            @Value("${rag.chunking.min-size:200}") int minSize,
            RecursiveDocumentChunker recursiveChunker) {
        this.maxSize = Math.max(200, maxSize);
        this.minSize = Math.max(50, minSize);
        this.recursiveChunker = recursiveChunker;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.DOCUMENT;
    }

    /**
     * 按 Block 边界累积内容；超长的单个 Block 交给递归分块器继续拆分。
     *
     * @param document 包含结构化 Block 的清理文档
     * @return 保留章节路径元数据的分块结果
     */
    @Override
    @SuppressWarnings({"java:S1854", "java:S3776", "java:S6541"})
    public ChunkingResult chunk(CleanedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String[] headings = new String[6];
        StringBuilder current = new StringBuilder();
        String currentHeadingPath = null;
        Integer startSourceIndex = null;
        Integer endSourceIndex = null;
        Set<String> blockTypes = new LinkedHashSet<>();
        DocumentBlock previousBlock = null;

        for (DocumentBlock block : document.blocks()) {
            if (block.type() == DocumentBlockType.HEADING) {
                flushChunk(chunks, current, currentHeadingPath, startSourceIndex, endSourceIndex, blockTypes);
                resetAccumulator(current, blockTypes);
                startSourceIndex = null;
                endSourceIndex = null;
                currentHeadingPath = null;
                previousBlock = null;

                int level = block.headingLevel() == null ? 1 : Math.max(1, Math.min(6, block.headingLevel()));
                headings[level - 1] = block.content();
                Arrays.fill(headings, level, headings.length, null);
                currentHeadingPath = headingPath(headings);
                appendBlock(current, blockTypes, DocumentTextRenderer.renderBlock(block), block);
                startSourceIndex = block.sourceIndex();
                endSourceIndex = block.sourceIndex();
                previousBlock = block;
                continue;
            }

            String rendered = DocumentTextRenderer.renderBlock(block);
            String headingPath = headingPath(headings);
            if (currentHeadingPath == null && !headingPath.isBlank()) {
                currentHeadingPath = headingPath;
            }
            String separator = "";
            if (!current.isEmpty()) {
                separator = previousBlock == null
                        ? "\n"
                        : DocumentTextRenderer.separator(previousBlock, block);
            }
            if (previousBlock != null && current.length() + separator.length() + rendered.length() > maxSize) {
                flushChunk(chunks, current, currentHeadingPath, startSourceIndex, endSourceIndex, blockTypes);
                resetAccumulator(current, blockTypes);
                previousBlock = null;
                startSourceIndex = null;
                endSourceIndex = null;
                if (!headingPath.isBlank()) {
                    currentHeadingPath = headingPath;
                }
                separator = "";
            }
            if (rendered.length() > maxSize) {
                flushChunk(chunks, current, currentHeadingPath, startSourceIndex, endSourceIndex, blockTypes);
                resetAccumulator(current, blockTypes);
                previousBlock = null;
                startSourceIndex = null;
                endSourceIndex = null;
                String pathForPieces = headingPath.isBlank() ? currentHeadingPath : headingPath;
                for (String piece : recursiveChunker.split(rendered)) {
                    chunks.add(DocumentChunkFactory.of(
                            chunks.size(),
                            piece,
                            pathForPieces,
                            block.sourceIndex(),
                            block.sourceIndex(),
                            metadataForTypes(Set.of(block.type().name()))));
                }
                currentHeadingPath = pathForPieces;
            } else {
                if (current.isEmpty()) {
                    appendBlock(current, blockTypes, rendered, block);
                    startSourceIndex = block.sourceIndex();
                } else {
                    current.append(separator).append(rendered);
                    blockTypes.add(block.type().name());
                }
                endSourceIndex = block.sourceIndex();
                previousBlock = block;
            }
        }
        flushChunk(chunks, current, currentHeadingPath, startSourceIndex, endSourceIndex, blockTypes);
        mergeTinyChunks(chunks);
        return new ChunkingResult(strategy(), strategy(), DocumentChunkFactory.reindex(chunks));
    }

    private void mergeTinyChunks(List<DocumentChunk> chunks) {
        for (int i = chunks.size() - 1; i > 0; i--) {
            DocumentChunk current = chunks.get(i);
            DocumentChunk previous = chunks.get(i - 1);
            if (current.embeddingContent().length() < minSize
                    && previous.embeddingContent().length() + current.embeddingContent().length() + 1 <= maxSize
                    && pathsEqual(previous.headingPath(), current.headingPath())) {
                String merged = previous.embeddingContent() + "\n" + current.embeddingContent();
                Integer start = previous.startSourceIndex();
                Integer end = current.endSourceIndex();
                Set<String> types = new LinkedHashSet<>();
                addBlockTypes(types, previous.metadata());
                addBlockTypes(types, current.metadata());
                chunks.set(i - 1, DocumentChunkFactory.of(
                        i - 1, merged, previous.headingPath(), start, end, metadataForTypes(types)));
                chunks.remove(i);
            }
        }
    }

    private static boolean pathsEqual(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static void addBlockTypes(Set<String> target, Map<String, Object> metadata) {
        Object value = metadata.get("blockTypes");
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    target.add(item.toString());
                }
            }
        }
    }

    private static Map<String, Object> metadataForTypes(Set<String> blockTypes) {
        if (blockTypes == null || blockTypes.isEmpty()) {
            return Map.of();
        }
        return Map.of("blockTypes", List.copyOf(blockTypes));
    }

    private static void appendBlock(
            StringBuilder current, Set<String> blockTypes, String rendered, DocumentBlock block) {
        current.append(rendered);
        blockTypes.add(block.type().name());
    }

    private static void resetAccumulator(StringBuilder current, Set<String> blockTypes) {
        current.setLength(0);
        blockTypes.clear();
    }

    private static void flushChunk(
            List<DocumentChunk> chunks,
            StringBuilder current,
            String headingPath,
            Integer startSourceIndex,
            Integer endSourceIndex,
            Set<String> blockTypes) {
        String value = current.toString().strip();
        if (value.isEmpty()) {
            return;
        }
        chunks.add(DocumentChunkFactory.of(
                chunks.size(),
                value,
                headingPath,
                startSourceIndex,
                endSourceIndex,
                metadataForTypes(blockTypes)));
        current.setLength(0);
        blockTypes.clear();
    }

    private static String headingPath(String[] headings) {
        return Arrays.stream(headings).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " > " + right).orElse("");
    }
}
