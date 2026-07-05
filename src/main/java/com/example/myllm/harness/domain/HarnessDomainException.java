package com.example.myllm.harness.domain;

/** Harness 领域规则违反时抛出。 */
public class HarnessDomainException extends RuntimeException {

    private final HarnessErrorCode errorCode;

    public HarnessDomainException(HarnessErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public HarnessErrorCode getErrorCode() {
        return errorCode;
    }
}
