package com.example.myllm.service;

import com.example.myllm.dto.ChatResponse;
import com.example.myllm.dto.InferenceMetrics;
import com.example.myllm.dto.RagSource;
import com.example.myllm.dto.VectorChunkResult;
import com.example.myllm.entity.TransactionLog;
import com.example.myllm.support.InferenceMetricsExtractor;
import com.example.myllm.support.retrieval.QueryRewriteResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 对话业务入口服务，负责把“是否使用 RAG、是否限定文件、如何审计”这些业务决策串起来。
 *
 * <p>这里刻意保持一次请求只调用一次最终 Chat 模型：RAG 检索、问题改写和引用来源都在模型调用前完成，
 * 模型本身不直接执行工具。后续 Harness Shadow/Agent Loop 会在独立 bounded context 中演进，避免改变
 * 当前在线问答链路的稳定性。</p>
 *
 * <p>失败语义：RAG 检索失败会 fail-soft 回退普通对话，并在 {@code rag_call_log} 中记录
 * {@code RETRIEVAL_FAILED}；模型调用失败才会让本次请求整体失败。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int LOG_PREVIEW_LEN = 200;

    private final ChatClient chatClient;
    private final TransactionLogService transactionLogService;
    private final RagCallLogService ragCallLogService;
    private final FileEmbeddingService fileEmbeddingService;
    private final RagRetrievalService ragRetrievalService;
    private final String provider;
    private final String model;
    private final double minSimilarity;

    public ChatService(
            ChatClient chatClient,
            TransactionLogService transactionLogService,
            RagCallLogService ragCallLogService,
            FileEmbeddingService fileEmbeddingService,
            RagRetrievalService ragRetrievalService,
            @Value("${llm.provider:ollama}") String provider,
            @Value("${spring.ai.ollama.chat.options.model:local-model}") String ollamaModel,
            @Value("${spring.ai.openai.chat.options.model:local-model}") String openAiModel,
            @Value("${rag.retrieval.min-similarity:0.45}") double minSimilarity) {
        this.chatClient = chatClient;
        this.transactionLogService = transactionLogService;
        this.ragCallLogService = ragCallLogService;
        this.fileEmbeddingService = fileEmbeddingService;
        this.ragRetrievalService = ragRetrievalService;
        this.provider = provider;
        this.model = "openai-compatible".equals(provider) ? openAiModel : ollamaModel;
        this.minSimilarity = minSimilarity;
    }

    /**
     * 执行一次用户对话请求。
     *
     * <p>当 {@code useRag=true} 时，先由 {@link RagRetrievalService} 完成规则改写、多路召回和候选过滤；
     * 只有有效分片非空时才把参考资料拼入提示词，并将 {@code ragApplied=true}。检索为空、低相关或被意图
     * 分类跳过时，业务上认为“没有应用知识库”，但仍允许普通模型回答。</p>
     *
     * <p>审计边界：无论模型成功还是失败，都会尽量写入交易日志；RAG 诊断信息单独落到
     * {@code rag_call_log}，避免把模型调用成功误认为 RAG 检索成功。</p>
     */
    @SuppressWarnings("java:S3776") // Coordinates retrieval, model invocation and audit persistence.
    public ChatResponse chat(String message, String systemPrompt, boolean useRag, List<String> fileIds, String requestType) {
        List<RagSource> sources = List.of();
        String effectiveMessage = message;
        boolean ragAttempted = false;
        boolean ragApplied = false;
        String ragStatus = useRag ? "FALLBACK" : "DISABLED";
        String ragError = null;
        QueryRewriteResult rewrite = null;
        RagRetrievalService.RetrievalDiagnostics retrievalDiagnostics = null;
        List<String> effectiveRagFileIds = fileIds;
        if (!useRag && effectiveRagFileIds != null && !effectiveRagFileIds.isEmpty()) {
            log.debug("直连模式忽略 fileIds count={}", effectiveRagFileIds.size());
            effectiveRagFileIds = List.of();
        }

        if (useRag) {
            ragAttempted = true;
            try {
                RagRetrievalService.RetrievalResult retrieval = ragRetrievalService.retrieve(message, fileIds);
                rewrite = retrieval.rewrite();
                retrievalDiagnostics = retrieval.diagnostics();
                effectiveRagFileIds = retrieval.effectiveFileIds();
                List<VectorChunkResult> chunks = retrieval.rawHits();
                List<VectorChunkResult> relevantChunks = retrieval.acceptedHits();
                if (rewrite != null && rewrite.skipRag()) {
                    ragStatus = "SKIPPED_BY_INTENT";
                    logRagSkipped(rewrite, message);
                } else if (relevantChunks.isEmpty()) {
                    double topScore = chunks.isEmpty() ? 0.0 : chunks.get(0).similarity();
                    ragStatus = chunks.isEmpty() ? "NO_HIT" : "LOW_RELEVANCE";
                    logRagMiss(chunks.size(), topScore);
                } else {
                    sources = relevantChunks.stream().map(this::toRagSource).toList();
                    effectiveMessage = fileEmbeddingService.buildRagPrompt(message, relevantChunks);
                    ragApplied = true;
                    ragStatus = "APPLIED";
                    logRagApplied(chunks.size(), sources.size(), relevantChunks.get(0).similarity());
                }
                logRewrite(rewrite);
            } catch (Exception e) {
                ragError = e.getMessage();
                ragStatus = "RETRIEVAL_FAILED";
                log.warn("RAG 检索失败，回退为普通对话 message={}", preview(message), e);
            }
        }

        logModelStart(message, systemPrompt, useRag);

        long start = System.currentTimeMillis();
        try {
            ModelCallResult result = invokeModel(effectiveMessage, systemPrompt);
            InferenceMetrics metrics = result.metrics();

            logModelSuccess(metrics, result.reply());

            TransactionLog txLog = transactionLogService.saveSuccess(
                    provider, model, message, result.reply(), systemPrompt, requestType, metrics);
            ragCallLogService.save(
                    txLog,
                    useRag,
                    ragAttempted,
                    ragApplied,
                    sources.size(),
                    effectiveRagFileIds,
                    ragStatus,
                    ragError,
                    rewrite,
                    retrievalDiagnostics);

            return new ChatResponse(result.reply(), provider, sources);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            InferenceMetrics metrics = new InferenceMetrics(
                    null, null, null, null, null, null, null, null, elapsed);
            TransactionLog txLog = transactionLogService.saveFailure(
                    provider, model, message, systemPrompt, requestType, e.getMessage(), metrics);
            ragCallLogService.save(
                    txLog,
                    useRag,
                    ragAttempted,
                    ragApplied,
                    sources.size(),
                    effectiveRagFileIds,
                    ragStatus,
                    ragError,
                    rewrite,
                    retrievalDiagnostics);

            throw new IllegalStateException(
                    "调用模型失败 provider=" + provider + " model=" + model + ": " + rootCauseMessage(e),
                    e);
        }
    }

    /**
     * 轻量健康/示例对话入口，不走 RAG，也不支持临时系统提示词。
     */
    public String simpleChat(String message) {
        logSimpleStart(message);

        long start = System.currentTimeMillis();
        try {
            ModelCallResult result = invokeModel(message, null);
            InferenceMetrics metrics = result.metrics();

            logSimpleSuccess(metrics);

            transactionLogService.saveSuccess(
                    provider, model, message, result.reply(), null, "GET", metrics);

            return result.reply();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            InferenceMetrics metrics = new InferenceMetrics(
                    null, null, null, null, null, null, null, null, elapsed);
            transactionLogService.saveFailure(
                    provider, model, message, null, "GET", e.getMessage(), metrics);

            throw new IllegalStateException(
                    "快速模型调用失败 provider=" + provider + " model=" + model + ": " + rootCauseMessage(e),
                    e);
        }
    }

    public String getModelInfo() {
        return model;
    }

    /**
     * 直连模型调用，不走 RAG，供评测等场景使用。
     *
     * <p>评测模块需要控制模型输出长度和温度，并且不希望把检索质量与模型裸能力混在一起，因此提供
     * directChat 旁路入口。</p>
     */
    public DirectCallResult directChat(String message, String systemPrompt, String requestType) {
        return directChat(message, systemPrompt, requestType, null, null);
    }

    /** 直连模型调用，并为当前请求覆盖生成长度和温度。 */
    public DirectCallResult directChat(
            String message,
            String systemPrompt,
            String requestType,
            Integer maxTokens,
            Double temperature) {
        long start = System.currentTimeMillis();
        try {
            ModelCallResult result = invokeModel(message, systemPrompt, maxTokens, temperature);
            InferenceMetrics metrics = result.metrics();
            transactionLogService.saveSuccess(
                    provider, model, message, result.reply(), systemPrompt, requestType, metrics);
            return new DirectCallResult(result.reply(), metrics.wallClockDurationMs());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            InferenceMetrics metrics = new InferenceMetrics(
                    null, null, null, null, null, null, null, null, elapsed);
            transactionLogService.saveFailure(
                    provider, model, message, systemPrompt, requestType, e.getMessage(), metrics);
            throw new IllegalStateException(
                    "直连模型调用失败 provider=" + provider + " model=" + model + ": " + rootCauseMessage(e),
                    e);
        }
    }

    public record DirectCallResult(String reply, Long durationMs) {}

    private void logRagSkipped(QueryRewriteResult rewrite, String message) {
        if (log.isInfoEnabled()) {
            log.info("RAG 跳过 useRag=true intent={} originalQuery={}", rewrite.intent(), preview(message));
        }
    }

    private void logRagMiss(int rawHits, double topScore) {
        if (log.isInfoEnabled()) {
            log.info("RAG 未命中有效分片 rawHits={} topScore={} threshold={}，回退为普通对话",
                    rawHits, String.format("%.3f", topScore), minSimilarity);
        }
    }

    private void logRagApplied(int rawHits, int appliedHits, double topScore) {
        if (log.isInfoEnabled()) {
            log.info("RAG 检索完成 rawHits={} appliedHits={} topScore={}",
                    rawHits, appliedHits, String.format("%.3f", topScore));
        }
    }

    private void logRewrite(QueryRewriteResult rewrite) {
        if (rewrite != null && log.isInfoEnabled()) {
            log.info("RAG 问题改写 original={} normalized={} retrieval={} intent={} strategy={} hints={}",
                    preview(rewrite.originalQuery()), preview(rewrite.normalizedQuery()),
                    preview(rewrite.retrievalQuery()), rewrite.intent(), rewrite.strategy(),
                    rewrite.filenameHints());
        }
    }

    private void logModelStart(String message, String systemPrompt, boolean useRag) {
        if (log.isInfoEnabled()) {
            log.info("调用本地模型开始 provider={} model={} useRag={} message={} systemPrompt={}",
                    provider, model, useRag, preview(message), preview(systemPrompt));
        }
    }

    private void logModelSuccess(InferenceMetrics metrics, String reply) {
        if (log.isInfoEnabled()) {
            log.info("调用本地模型完成 provider={} model={} elapsedMs={} inputTokens={} outputTokens={} totalTokens={} inferenceSpeedTps={} evalDurationMs={} reply={}",
                    provider, model, metrics.wallClockDurationMs(), metrics.inputTokens(),
                    metrics.outputTokens(), metrics.totalTokens(), formatSpeed(metrics.inferenceSpeedTps()),
                    metrics.evalDurationMs(), preview(reply));
        }
    }

    private void logSimpleStart(String message) {
        if (log.isInfoEnabled()) {
            log.info("快速对话开始 provider={} model={} message={}", provider, model, preview(message));
        }
    }

    private void logSimpleSuccess(InferenceMetrics metrics) {
        if (log.isInfoEnabled()) {
            log.info("快速对话完成 provider={} model={} elapsedMs={} inputTokens={} outputTokens={} totalTokens={} inferenceSpeedTps={}",
                    provider, model, metrics.wallClockDurationMs(), metrics.inputTokens(),
                    metrics.outputTokens(), metrics.totalTokens(), formatSpeed(metrics.inferenceSpeedTps()));
        }
    }

    private ModelCallResult invokeModel(String message, String systemPrompt) {
        return invokeModel(message, systemPrompt, null, null);
    }

    private ModelCallResult invokeModel(
            String message, String systemPrompt, Integer maxTokens, Double temperature) {
        long startNanos = System.nanoTime();
        var prompt = chatClient.prompt().user(message);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt = prompt.system(systemPrompt);
        }
        if (maxTokens != null || temperature != null) {
            ChatOptions.Builder options = ChatOptions.builder();
            if (maxTokens != null) {
                options.maxTokens(maxTokens);
            }
            if (temperature != null) {
                options.temperature(temperature);
            }
            prompt = prompt.options(options.build());
        }
        org.springframework.ai.chat.model.ChatResponse response = prompt.call().chatResponse();
        long wallClockDurationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("模型返回空响应");
        }
        InferenceMetrics metrics = InferenceMetricsExtractor.from(response, wallClockDurationMs);
        String reply = response.getResult().getOutput().getText();
        return new ModelCallResult(reply, metrics);
    }

    private static String preview(String text) {
        if (text == null) {
            return "(null)";
        }
        if (text.isBlank()) {
            return "(empty)";
        }
        if (text.length() <= LOG_PREVIEW_LEN) {
            return text;
        }
        return text.substring(0, LOG_PREVIEW_LEN) + "...(+" + (text.length() - LOG_PREVIEW_LEN) + " chars)";
    }

    private static String formatSpeed(Double speed) {
        return speed == null ? "n/a" : String.format("%.2f", speed);
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private RagSource toRagSource(VectorChunkResult chunk) {
        String text = chunk.chunkText();
        String snippet = text.length() <= 120 ? text : text.substring(0, 120) + "...";
        return new RagSource(
                chunk.fileName(),
                chunk.chunkIndex(),
                chunk.similarity(),
                snippet,
                chunk.headingPath(),
                chunk.contentHash());
    }

    private record ModelCallResult(String reply, InferenceMetrics metrics) {
    }
}
