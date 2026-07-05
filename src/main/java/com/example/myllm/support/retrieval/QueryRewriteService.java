package com.example.myllm.support.retrieval;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QueryRewriteService {

    private final QueryPreprocessor preprocessor;
    private final QueryIntentClassifier intentClassifier;
    private final boolean rewriteEnabled;
    private final boolean skipRagOnChitchat;

    public QueryRewriteService(
            QueryPreprocessor preprocessor,
            QueryIntentClassifier intentClassifier,
            @Value("${rag.query-rewrite.enabled:true}") boolean rewriteEnabled,
            @Value("${rag.query-rewrite.skip-rag-on-chitchat:true}") boolean skipRagOnChitchat) {
        this.preprocessor = preprocessor;
        this.intentClassifier = intentClassifier;
        this.rewriteEnabled = rewriteEnabled;
        this.skipRagOnChitchat = skipRagOnChitchat;
    }

    public QueryRewriteResult rewrite(String rawQuery) {
        String original = rawQuery == null ? "" : rawQuery;
        if (!rewriteEnabled) {
            return new QueryRewriteResult(
                    original,
                    original,
                    original,
                    QueryIntent.CONTENT_QA,
                    List.of(),
                    false,
                    QueryRewriteStrategy.DISABLED);
        }

        QueryPreprocessor.ProcessedQuery processed = preprocessor.preprocess(rawQuery);
        QueryIntent intent = intentClassifier.classify(processed.normalizedQuery(), processed.filenameHints());
        boolean skipRag = skipRagOnChitchat && intent == QueryIntent.SKIP_RAG;

        String retrievalQuery = buildRetrievalQuery(intent, processed.normalizedQuery(), processed.filenameHints());
        if (retrievalQuery.isBlank()) {
            retrievalQuery = processed.normalizedQuery().isBlank() ? original : processed.normalizedQuery();
        }

        QueryRewriteStrategy strategy = resolveStrategy(intent, processed.filenameHints());

        return new QueryRewriteResult(
                original,
                processed.normalizedQuery(),
                retrievalQuery,
                intent,
                List.copyOf(processed.filenameHints()),
                skipRag,
                strategy);
    }

    private static String buildRetrievalQuery(QueryIntent intent, String normalizedQuery, List<String> hints) {
        if (intent == QueryIntent.FILENAME_LOOKUP && hints != null && !hints.isEmpty()) {
            return String.join(" ", hints);
        }
        return normalizedQuery == null ? "" : normalizedQuery;
    }

    private static QueryRewriteStrategy resolveStrategy(QueryIntent intent, List<String> hints) {
        if (intent == QueryIntent.FILENAME_LOOKUP && hints != null && !hints.isEmpty()) {
            return QueryRewriteStrategy.RULE_FILENAME_HINT;
        }
        return QueryRewriteStrategy.RULE_NORMALIZED;
    }
}
