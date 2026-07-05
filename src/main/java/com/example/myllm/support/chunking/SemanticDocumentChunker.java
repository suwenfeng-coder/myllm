package com.example.myllm.support.chunking;

import com.example.myllm.support.document.CleanedDocument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于相邻文本 embedding 相似度的语义分块器。
 *
 * <p>算法先把句子合并为最小语义单元，再批量生成向量并计算相邻单元的余弦相似度。
 * 低于自适应分位点的相似度被视为主题切换边界，同时使用最小、最大块大小约束结果。
 * 文本不足以形成两个语义单元时会回退到递归分块。</p>
 *
 * <p>该策略会比其他策略额外执行一轮 embedding 调用。</p>
 */
@Component
public class SemanticDocumentChunker implements DocumentChunker {

    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？!?；;])|(?<=\\.)\\s+|\\n+");

    private final EmbeddingModel embeddingModel;
    private final RecursiveDocumentChunker recursiveChunker;
    private final int unitMinSize;
    private final int minChunkSize;
    private final int maxChunkSize;
    private final int batchSize;
    private final double breakpointPercentile;

    public SemanticDocumentChunker(
            EmbeddingModel embeddingModel,
            RecursiveDocumentChunker recursiveChunker,
            @Value("${rag.chunking.semantic.unit-min-size:180}") int unitMinSize,
            @Value("${rag.chunking.min-size:200}") int minChunkSize,
            @Value("${rag.chunking.semantic.max-size:1600}") int maxChunkSize,
            @Value("${rag.chunking.semantic.embedding-batch-size:32}") int batchSize,
            @Value("${rag.chunking.semantic.breakpoint-percentile:25}") double breakpointPercentile) {
        this.embeddingModel = embeddingModel;
        this.recursiveChunker = recursiveChunker;
        this.unitMinSize = Math.max(20, unitMinSize);
        this.minChunkSize = Math.max(20, minChunkSize);
        this.maxChunkSize = Math.max(300, maxChunkSize);
        this.batchSize = Math.max(1, Math.min(128, batchSize));
        this.breakpointPercentile = Math.max(0.0, Math.min(100.0, breakpointPercentile));
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.SEMANTIC;
    }

    /**
     * 执行语义分块。
     *
     * @param document 清理后的文档
     * @return 语义分块结果；语义单元不足时实际策略为 recursive
     */
    @Override
    public ChunkingResult chunk(CleanedDocument document) {
        List<String> units = semanticUnits(document.content());
        if (units.size() < 2) {
            return new ChunkingResult(
                    strategy(),
                    ChunkStrategy.RECURSIVE,
                    recursiveChunker.splitToChunks(document.content()));
        }

        List<float[]> embeddings = embedInBatches(units);
        double[] adjacentSimilarities = new double[embeddings.size() - 1];
        for (int i = 0; i < adjacentSimilarities.length; i++) {
            adjacentSimilarities[i] = cosineSimilarity(embeddings.get(i), embeddings.get(i + 1));
        }
        double threshold = percentile(adjacentSimilarities, breakpointPercentile);
        List<String> chunks = assemble(units, adjacentSimilarities, threshold);
        return new ChunkingResult(strategy(), strategy(), DocumentChunkFactory.fromPlainTexts(chunks));
    }

    /**
     * 按中英文句末标点和换行拆句，并合并过短句子以减少 embedding 调用。
     */
    List<String> semanticUnits(String content) {
        String normalized = ChunkingSupport.normalize(content);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_BOUNDARY.split(normalized)) {
            String value = sentence.strip();
            if (value.isEmpty()) {
                continue;
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(value);
            if (current.length() >= unitMinSize) {
                units.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            if (!units.isEmpty() && current.length() < unitMinSize / 2) {
                int last = units.size() - 1;
                units.set(last, units.get(last) + " " + current);
            } else {
                units.add(current.toString());
            }
        }
        return units;
    }

    private List<float[]> embedInBatches(List<String> units) {
        List<float[]> result = new ArrayList<>(units.size());
        for (int start = 0; start < units.size(); start += batchSize) {
            int end = Math.min(units.size(), start + batchSize);
            List<float[]> batch = embeddingModel.embed(units.subList(start, end));
            if (batch.size() != end - start) {
                throw new IllegalStateException("语义分块向量数量与文本单元数量不一致");
            }
            result.addAll(batch);
        }
        return result;
    }

    private List<String> assemble(List<String> units, double[] similarities, double threshold) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < units.size(); i++) {
            String unit = units.get(i);
            if (!current.isEmpty() && current.length() + unit.length() + 1 > maxChunkSize) {
                addWithinLimit(chunks, current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(unit);

            boolean semanticBoundary = i < similarities.length && similarities[i] <= threshold;
            if (semanticBoundary && current.length() >= minChunkSize) {
                addWithinLimit(chunks, current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            addWithinLimit(chunks, current.toString());
        }
        mergeTinyTail(chunks);
        return chunks;
    }

    private void addWithinLimit(List<String> chunks, String content) {
        if (content.length() <= maxChunkSize) {
            chunks.add(content.strip());
        } else {
            chunks.addAll(recursiveChunker.split(content));
        }
    }

    private void mergeTinyTail(List<String> chunks) {
        if (chunks.size() < 2) {
            return;
        }
        int last = chunks.size() - 1;
        if (chunks.get(last).length() < minChunkSize
                && chunks.get(last - 1).length() + chunks.get(last).length() + 1 <= maxChunkSize) {
            chunks.set(last - 1, chunks.get(last - 1) + " " + chunks.get(last));
            chunks.remove(last);
        }
    }

    /**
     * 计算两个同维向量的余弦相似度。
     */
    static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            throw new IllegalArgumentException("语义分块向量维度不一致");
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /**
     * 使用 nearest-rank 的离散形式计算分位值，作为文档内自适应语义断点。
     */
    static double percentile(double[] values, double percentile) {
        if (values.length == 0) {
            return 0.0;
        }
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int index = (int) Math.floor((sorted.length - 1) * percentile / 100.0);
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
