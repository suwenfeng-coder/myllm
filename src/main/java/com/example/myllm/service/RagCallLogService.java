package com.example.myllm.service;

import com.example.myllm.entity.RagCallLog;
import com.example.myllm.entity.TransactionLog;
import com.example.myllm.repository.RagCallLogRepository;
import com.example.myllm.support.retrieval.QueryRewriteResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagCallLogService {

    private static final Logger log = LoggerFactory.getLogger(RagCallLogService.class);

    private final RagCallLogRepository repository;

    public RagCallLogService(RagCallLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("java:S107") // Audit persistence mirrors the retrieval diagnostic schema.
    public void save(
            TransactionLog transactionLog,
            boolean ragEnabled,
            boolean ragAttempted,
            boolean ragApplied,
            int retrievedChunks,
            List<String> selectedFileIds,
            String ragStatus,
            String ragError,
            QueryRewriteResult rewrite,
            RagRetrievalService.RetrievalDiagnostics diagnostics) {
        if (transactionLog == null || transactionLog.getId() == null) {
            log.warn("跳过RAG日志保存：transactionLog为空");
            return;
        }
        RagCallLog logRecord = new RagCallLog();
        logRecord.setTransactionLog(transactionLog);
        logRecord.setRagEnabled(ragEnabled);
        logRecord.setRagAttempted(ragAttempted);
        logRecord.setRagApplied(ragApplied);
        logRecord.setRetrievedChunks(Math.max(0, retrievedChunks));
        logRecord.setSelectedFileIds(joinFileIds(selectedFileIds));
        logRecord.setRagStatus(ragStatus == null ? "DISABLED" : ragStatus);
        logRecord.setRagError(ragError);
        applyRewrite(logRecord, rewrite);
        applyDiagnostics(logRecord, diagnostics);
        repository.save(logRecord);
    }

    private static void applyRewrite(RagCallLog logRecord, QueryRewriteResult rewrite) {
        if (rewrite == null) {
            return;
        }
        logRecord.setNormalizedQuery(blankToNull(rewrite.normalizedQuery()));
        logRecord.setRewrittenQuery(blankToNull(rewrite.retrievalQuery()));
        logRecord.setRewriteStrategy(rewrite.strategy() == null ? null : rewrite.strategy().name());
        logRecord.setQueryIntent(rewrite.intent() == null ? null : rewrite.intent().name());
    }

    private static void applyDiagnostics(
            RagCallLog logRecord,
            RagRetrievalService.RetrievalDiagnostics diagnostics) {
        if (diagnostics == null) {
            return;
        }
        logRecord.setRetrievalMode(blankToNull(diagnostics.retrievalMode()));
        logRecord.setDenseHitCount(Math.max(0, diagnostics.denseHitCount()));
        logRecord.setBm25HitCount(Math.max(0, diagnostics.bm25HitCount()));
        logRecord.setFusedHitCount(Math.max(0, diagnostics.fusedHitCount()));
        logRecord.setBm25DurationMs(Math.max(0, diagnostics.bm25DurationMs()));
        logRecord.setRrfDurationMs(Math.max(0, diagnostics.rrfDurationMs()));
        logRecord.setBm25FallbackReason(blankToNull(diagnostics.bm25FallbackReason()));
        logRecord.setGraphAttempted(diagnostics.graphAttempted());
        logRecord.setGraphAvailable(diagnostics.graphAvailable());
        logRecord.setGraphSeedCount(Math.max(0, diagnostics.graphSeedCount()));
        logRecord.setGraphHitCount(Math.max(0, diagnostics.graphHitCount()));
        logRecord.setGraphShadowOverlapCount(Math.max(0, diagnostics.graphShadowOverlapCount()));
        logRecord.setGraphDurationMs(Math.max(0, diagnostics.graphDurationMs()));
        logRecord.setGraphFallbackReason(blankToNull(diagnostics.graphFallbackReason()));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String joinFileIds(List<String> selectedFileIds) {
        if (selectedFileIds == null || selectedFileIds.isEmpty()) {
            return null;
        }
        return selectedFileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
