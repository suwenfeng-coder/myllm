package com.example.myllm.harness.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RunStateMachineTests {

    @ParameterizedTest
    @CsvSource({
            "CREATED,QUEUED",
            "QUEUED,RUNNING",
            "RUNNING,WAITING_APPROVAL",
            "WAITING_APPROVAL,RUNNING",
            "RUNNING,VERIFYING",
            "VERIFYING,SUCCEEDED",
            "FAILED,QUEUED",
            "RUNNING,QUEUED"
    })
    void allowsValidTransitions(String from, String to) {
        assertDoesNotThrow(() -> RunStateMachine.validateTransition(
                RunStatus.valueOf(from), RunStatus.valueOf(to)));
    }

    @Test
    void rejectsIllegalTransition() {
        HarnessDomainException ex = assertThrows(
                HarnessDomainException.class,
                () -> RunStateMachine.validateTransition(RunStatus.SUCCEEDED, RunStatus.RUNNING));
        assertEquals(HarnessErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
    }

    @Test
    void terminalStates() {
        assertTrue(RunStateMachine.isTerminal(RunStatus.SUCCEEDED));
        assertTrue(RunStateMachine.isTerminal(RunStatus.CANCELLED));
        assertTrue(RunStateMachine.isTerminal(RunStatus.TIMED_OUT));
    }
}
