package com.example.myllm.harness.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BudgetManagerTests {

    @Test
    void rejectsExceededStepBudget() {
        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> BudgetManager.checkStepBudget(8, 8));
        assertEquals(HarnessErrorCode.BUDGET_EXHAUSTED, ex.getErrorCode());
    }

    @Test
    void allowsWithinBudget() {
        assertDoesNotThrow(() -> {
            BudgetManager.checkStepBudget(3, 8);
            BudgetManager.checkModelCallBudget(2, 6);
            BudgetManager.checkToolCallBudget(5, 10);
            BudgetManager.checkTokenBudget(100, 200, 50, 100);
        });
    }

    @Test
    void rejectsExceededTokenBudget() {
        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> BudgetManager.checkTokenBudget(201, 200, 0, 100));
        assertEquals(HarnessErrorCode.BUDGET_EXHAUSTED, ex.getErrorCode());
    }
}
