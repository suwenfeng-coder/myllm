package com.example.myllm.support.docforge;

public class DocForgeServiceException extends RuntimeException {

    private final int statusCode;

    public DocForgeServiceException(String message) {
        this(message, 502);
    }

    public DocForgeServiceException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public DocForgeServiceException(String message, Throwable cause) {
        this(message, cause, 502);
    }

    public DocForgeServiceException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
