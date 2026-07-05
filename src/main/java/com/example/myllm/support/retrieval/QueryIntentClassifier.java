package com.example.myllm.support.retrieval;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QueryIntentClassifier {

    private static final Pattern CONTENT_QUESTION = Pattern.compile("(怎么|如何|为什么|多少|几|是否|步骤|办法|处理)");
    private static final Pattern FILE_ACTION = Pattern.compile("(查看|打开|找|定位|搜索|检索|看看)");
    private static final Pattern FILE_NOUN = Pattern.compile("(文件|文档|手册|说明书|报告|资料|附件|docx|doc|pdf|md|txt)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CHITCHAT = Pattern.compile("^(你好|hi|hello|谢谢|感谢|在吗|早上好|晚上好)[!,.?，。？！]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public QueryIntent classify(String normalizedQuery, List<String> filenameHints) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return QueryIntent.SKIP_RAG;
        }
        if (CHITCHAT.matcher(normalizedQuery.strip()).matches()) {
            return QueryIntent.SKIP_RAG;
        }
        boolean hasFileHint = filenameHints != null && !filenameHints.isEmpty();
        boolean hasContentAsk = CONTENT_QUESTION.matcher(normalizedQuery).find();
        boolean hasFileAction = FILE_ACTION.matcher(normalizedQuery).find();
        if (hasFileHint && hasContentAsk) {
            return QueryIntent.HYBRID;
        }
        if (hasFileHint) {
            return QueryIntent.FILENAME_LOOKUP;
        }
        if (hasContentAsk) {
            return QueryIntent.CONTENT_QA;
        }
        if (hasFileAction && FILE_NOUN.matcher(normalizedQuery).find()) {
            return QueryIntent.FILENAME_LOOKUP;
        }
        return QueryIntent.CONTENT_QA;
    }
}
