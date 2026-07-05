package com.example.myllm.service;

import com.example.myllm.dto.RequestLogItemResponse;
import com.example.myllm.entity.RagCallLog;
import com.example.myllm.entity.TransactionLog;
import com.example.myllm.repository.RagCallLogRepository;
import com.example.myllm.repository.TransactionLogRepository;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class RequestLogQueryService {

    private final TransactionLogRepository transactionLogRepository;
    private final RagCallLogRepository ragCallLogRepository;

    public RequestLogQueryService(
            TransactionLogRepository transactionLogRepository,
            RagCallLogRepository ragCallLogRepository) {
        this.transactionLogRepository = transactionLogRepository;
        this.ragCallLogRepository = ragCallLogRepository;
    }

    public List<RequestLogItemResponse> queryRecentLogs(Integer limit, LocalDateTime from, LocalDateTime to) {
        int safeLimit = normalizeLimit(limit);
        validateTimeRange(from, to);
        List<TransactionLog> txLogs = queryTransactionLogs(safeLimit, from, to);
        if (txLogs.isEmpty()) {
            return List.of();
        }

        List<Long> txIds = txLogs.stream().map(TransactionLog::getId).toList();
        Map<Long, RagCallLog> ragByTxId = ragCallLogRepository.findByTransactionLogIdIn(txIds).stream()
                .collect(Collectors.toMap(
                        log -> log.getTransactionLog().getId(),
                        Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b));

        return txLogs.stream()
                .map(tx -> new RequestLogItemResponse(toTransactionInfo(tx), toRagInfo(ragByTxId.get(tx.getId()))))
                .toList();
    }

    private List<TransactionLog> queryTransactionLogs(int limit, LocalDateTime from, LocalDateTime to) {
        PageRequest page = PageRequest.of(0, limit);
        if (from != null || to != null) {
            LocalDateTime start = from != null ? from : LocalDateTime.of(1970, Month.JANUARY, 1, 0, 0);
            LocalDateTime end = to != null ? to : LocalDateTime.now(ZoneId.systemDefault());
            return transactionLogRepository.findByCreatedAtBetweenOrderByIdDesc(start, end, page);
        }
        return transactionLogRepository.findAllByOrderByIdDesc(page);
    }

    private static RequestLogItemResponse.TransactionInfo toTransactionInfo(TransactionLog tx) {
        return new RequestLogItemResponse.TransactionInfo(
                tx.getId(),
                tx.getCreatedAt(),
                tx.getProvider(),
                tx.getModel(),
                tx.getStatus(),
                tx.getRequestType(),
                tx.getDurationMs(),
                tx.getInputTokens(),
                tx.getOutputTokens(),
                tx.getTotalTokens(),
                tx.getErrorMessage());
    }

    private static RequestLogItemResponse.RagInfo toRagInfo(RagCallLog rag) {
        if (rag == null) {
            return null;
        }
        return new RequestLogItemResponse.RagInfo(
                rag.getId(),
                Boolean.TRUE.equals(rag.getRagEnabled()),
                Boolean.TRUE.equals(rag.getRagAttempted()),
                Boolean.TRUE.equals(rag.getRagApplied()),
                rag.getRetrievedChunks() == null ? 0 : rag.getRetrievedChunks(),
                rag.getSelectedFileIds(),
                rag.getRagStatus(),
                rag.getRagError(),
                rag.getNormalizedQuery(),
                rag.getRewrittenQuery(),
                rag.getRewriteStrategy(),
                rag.getQueryIntent(),
                rag.getRetrievalMode(),
                rag.getDenseHitCount() == null ? 0 : rag.getDenseHitCount(),
                rag.getBm25HitCount() == null ? 0 : rag.getBm25HitCount(),
                rag.getFusedHitCount() == null ? 0 : rag.getFusedHitCount(),
                rag.getBm25DurationMs() == null ? 0 : rag.getBm25DurationMs(),
                rag.getRrfDurationMs() == null ? 0 : rag.getRrfDurationMs(),
                rag.getBm25FallbackReason(),
                Boolean.TRUE.equals(rag.getGraphAttempted()),
                Boolean.TRUE.equals(rag.getGraphAvailable()),
                rag.getGraphSeedCount() == null ? 0 : rag.getGraphSeedCount(),
                rag.getGraphHitCount() == null ? 0 : rag.getGraphHitCount(),
                rag.getGraphShadowOverlapCount() == null ? 0 : rag.getGraphShadowOverlapCount(),
                rag.getGraphDurationMs() == null ? 0 : rag.getGraphDurationMs(),
                rag.getGraphFallbackReason(),
                rag.getCreatedAt());
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private static void validateTimeRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
    }
}
