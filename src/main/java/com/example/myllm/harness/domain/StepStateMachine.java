package com.example.myllm.harness.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Step 状态转换守卫。 */
public final class StepStateMachine {

    private static final Map<StepStatus, Set<StepStatus>> ALLOWED = Map.of(
            StepStatus.PENDING, EnumSet.of(StepStatus.RUNNING, StepStatus.SKIPPED),
            StepStatus.RUNNING, EnumSet.of(StepStatus.SUCCEEDED, StepStatus.FAILED),
            StepStatus.SUCCEEDED, EnumSet.noneOf(StepStatus.class),
            StepStatus.FAILED, EnumSet.noneOf(StepStatus.class),
            StepStatus.SKIPPED, EnumSet.noneOf(StepStatus.class));

    private StepStateMachine() {
    }

    public static void validateTransition(StepStatus from, StepStatus to) {
        if (from == to) {
            return;
        }
        Set<StepStatus> allowed = ALLOWED.getOrDefault(from, EnumSet.noneOf(StepStatus.class));
        if (!allowed.contains(to)) {
            throw new HarnessDomainException(
                    HarnessErrorCode.INVALID_STATE_TRANSITION,
                    "非法 Step 状态转换: " + from + " -> " + to);
        }
    }
}
