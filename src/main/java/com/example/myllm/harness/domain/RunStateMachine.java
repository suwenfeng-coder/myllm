package com.example.myllm.harness.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Run 状态转换守卫。
 *
 * <p>非法转移抛出 {@link HarnessDomainException}，供 Service 与 Worker 在持久化前校验。</p>
 */
public final class RunStateMachine {

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = Map.ofEntries(
            Map.entry(RunStatus.CREATED, EnumSet.of(RunStatus.QUEUED, RunStatus.CANCELLED)),
            Map.entry(RunStatus.QUEUED, EnumSet.of(RunStatus.PLANNING, RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.TIMED_OUT)),
            Map.entry(RunStatus.PLANNING, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.CANCELLED)),
            Map.entry(RunStatus.RUNNING, EnumSet.of(
                    RunStatus.WAITING_APPROVAL,
                    RunStatus.VERIFYING,
                    RunStatus.SUCCEEDED,
                    RunStatus.FAILED,
                    RunStatus.CANCELLED,
                    RunStatus.TIMED_OUT,
                    RunStatus.QUEUED)),
            Map.entry(RunStatus.WAITING_APPROVAL, EnumSet.of(RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.FAILED)),
            Map.entry(RunStatus.VERIFYING, EnumSet.of(
                    RunStatus.RUNNING, RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED)),
            Map.entry(RunStatus.FAILED, EnumSet.of(RunStatus.QUEUED)),
            Map.entry(RunStatus.SUCCEEDED, EnumSet.noneOf(RunStatus.class)),
            Map.entry(RunStatus.CANCELLED, EnumSet.noneOf(RunStatus.class)),
            Map.entry(RunStatus.TIMED_OUT, EnumSet.of(RunStatus.QUEUED)));

    private RunStateMachine() {
    }

    public static void validateTransition(RunStatus from, RunStatus to) {
        if (from == to) {
            return;
        }
        Set<RunStatus> allowed = ALLOWED.getOrDefault(from, EnumSet.noneOf(RunStatus.class));
        if (!allowed.contains(to)) {
            throw new HarnessDomainException(
                    HarnessErrorCode.INVALID_STATE_TRANSITION,
                    "非法 Run 状态转换: " + from + " -> " + to);
        }
    }

    public static boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.CANCELLED
                || status == RunStatus.TIMED_OUT;
    }
}
