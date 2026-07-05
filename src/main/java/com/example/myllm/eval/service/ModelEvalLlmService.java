package com.example.myllm.eval.service;

import com.example.myllm.eval.config.EvalProperties;
import com.example.myllm.eval.entity.ModelEvalQuestion;
import com.example.myllm.eval.support.EvalJudgePrompt;
import com.example.myllm.eval.support.EvalScoreSupport;
import com.example.myllm.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 调用本地模型生成评测答案并裁判打分。 */
@Service
public class ModelEvalLlmService {

    private static final Logger log = LoggerFactory.getLogger(ModelEvalLlmService.class);

    private final ChatService chatService;
    private final EvalProperties properties;
    private final ObjectMapper objectMapper;

    public ModelEvalLlmService(
            ChatService chatService, EvalProperties properties, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AnswerResult answer(ModelEvalQuestion question) {
        long start = System.nanoTime();
        String systemPrompt = properties.getJudge().getAnswerSystemPrompt();
        ChatService.DirectCallResult result = chatService.directChat(
                question.getPrompt(),
                systemPrompt,
                "EVAL_ANSWER",
                properties.getJudge().getAnswerMaxTokens(),
                null);
        return new AnswerResult(result.reply(), elapsedMs(start));
    }

    public EvalScoreSupport.EvalJudgeResult judge(ModelEvalQuestion question, String modelAnswer) {
        long start = System.nanoTime();
        String userPrompt = EvalJudgePrompt.buildUserPrompt(question, modelAnswer);
        ChatService.DirectCallResult result = chatService.directChat(
                userPrompt,
                properties.getJudge().getJudgeSystemPrompt(),
                "EVAL_JUDGE",
                properties.getJudge().getJudgeMaxTokens(),
                properties.getJudge().getJudgeTemperature());
        try {
            EvalScoreSupport.EvalJudgeResult parsed =
                    EvalScoreSupport.parseJudgeJson(objectMapper, result.reply());
            log.info("评测裁判完成 title={} durationMs={}", question.getTitle(), elapsedMs(start));
            return parsed;
        } catch (Exception e) {
            log.warn("评测裁判 JSON 解析失败，重试一次 title={} error={}", question.getTitle(), e.getMessage());
            ChatService.DirectCallResult retry = chatService.directChat(
                    userPrompt + "\n\n上次输出无法解析，请严格只输出合法 JSON。",
                    properties.getJudge().getJudgeSystemPrompt(),
                    "EVAL_JUDGE_RETRY",
                    properties.getJudge().getJudgeMaxTokens(),
                    properties.getJudge().getJudgeTemperature());
            return EvalScoreSupport.parseJudgeJson(objectMapper, retry.reply());
        }
    }

    public String currentModelName() {
        return chatService.getModelInfo();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public record AnswerResult(String answer, long durationMs) {}
}
