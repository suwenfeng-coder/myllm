package com.example.myllm.eval.support;

import com.example.myllm.eval.entity.EvalCategory;
import com.example.myllm.eval.entity.ModelEvalQuestion;

/** 裁判 Prompt 构建。 */
public final class EvalJudgePrompt {

    private EvalJudgePrompt() {
    }

    public static String buildUserPrompt(ModelEvalQuestion question, String modelAnswer) {
        return buildUserPrompt(
                question.getTitle(),
                question.getPrompt(),
                question.getCategory(),
                question.getReferenceAnswer(),
                question.getScoringRubric(),
                question.getOutputFormat(),
                modelAnswer);
    }

    public static String buildUserPrompt(
            String title,
            String prompt,
            EvalCategory category,
            String referenceAnswer,
            String scoringRubric,
            String outputFormat,
            String modelAnswer) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对以下模型回答进行六维打分（每维 0-5 整数）。\n\n");
        sb.append("## 评分量表\n");
        sb.append("5=完全正确+专业+可直接用; 4=基本正确小瑕疵; 3=能用但不专业; ");
        sb.append("2=部分正确明显问题; 1=严重错误; 0=胡说/跑偏\n\n");
        sb.append("## 六维说明\n");
        sb.append("- understanding: 是否看懂复杂问题\n");
        sb.append("- reasoning: 是否能一步步推导\n");
        sb.append("- code: Java/SQL/系统设计能力\n");
        sb.append("- domain: 银行/后端专业知识贴合\n");
        sb.append("- stability: 是否胡说/幻觉/跑偏\n");
        sb.append("- instruction: 是否按要求输出格式\n\n");
        sb.append("## 题目\n");
        sb.append("标题: ").append(title).append('\n');
        sb.append("类别: ").append(category).append('\n');
        sb.append("题干:\n").append(prompt).append('\n');
        if (outputFormat != null && !outputFormat.isBlank()) {
            sb.append("期望输出格式: ").append(outputFormat).append('\n');
        }
        if (referenceAnswer != null && !referenceAnswer.isBlank()) {
            sb.append("\n## 参考答案要点\n").append(referenceAnswer).append('\n');
        }
        if (scoringRubric != null && !scoringRubric.isBlank()) {
            sb.append("\n## 补充评分细则\n").append(scoringRubric).append('\n');
        }
        sb.append("\n## 模型回答\n").append(modelAnswer).append('\n');
        sb.append("\n## 输出要求\n");
        sb.append("只输出 JSON，字段: understanding, reasoning, code, domain, stability, instruction (整数0-5), summary (字符串).\n");
        return sb.toString();
    }
}
