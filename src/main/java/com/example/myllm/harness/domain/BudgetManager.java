package com.example.myllm.harness.domain;

/** 运行预算检查，超限时抛出 {@link HarnessDomainException}。 */
public final class BudgetManager {

    private BudgetManager() {
    }

    public static void checkStepBudget(int currentStep, int maxSteps) {
        if (currentStep >= maxSteps) {
            throw new HarnessDomainException(
                    HarnessErrorCode.BUDGET_EXHAUSTED,
                    "已超过最大步骤数: " + maxSteps);
        }
    }

    public static void checkModelCallBudget(int modelCallCount, int maxModelCalls) {
        if (modelCallCount >= maxModelCalls) {
            throw new HarnessDomainException(
                    HarnessErrorCode.BUDGET_EXHAUSTED,
                    "已超过最大模型调用次数: " + maxModelCalls);
        }
    }

    public static void checkToolCallBudget(int toolCallCount, int maxToolCalls) {
        if (toolCallCount >= maxToolCalls) {
            throw new HarnessDomainException(
                    HarnessErrorCode.BUDGET_EXHAUSTED,
                    "已超过最大工具调用次数: " + maxToolCalls);
        }
    }

    public static void checkWallTime(long startedAtMillis, long maxWallTimeMs) {
        if (startedAtMillis <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        if (elapsed > maxWallTimeMs) {
            throw new HarnessDomainException(
                    HarnessErrorCode.BUDGET_EXHAUSTED,
                    "已超过最大 wall time: " + maxWallTimeMs + "ms");
        }
    }

    public static void checkTokenBudget(int inputTokens, int maxInputTokens, int outputTokens, int maxOutputTokens) {
        if (maxInputTokens > 0 && inputTokens > maxInputTokens) {
            throw new HarnessDomainException(
                    HarnessErrorCode.BUDGET_EXHAUSTED,
                    "已超过最大 input tokens: " + maxInputTokens);
        }
        if (maxOutputTokens > 0 && outputTokens > maxOutputTokens) {
            throw new HarnessDomainException(
                    HarnessErrorCode.BUDGET_EXHAUSTED,
                    "已超过最大 output tokens: " + maxOutputTokens);
        }
    }
}
