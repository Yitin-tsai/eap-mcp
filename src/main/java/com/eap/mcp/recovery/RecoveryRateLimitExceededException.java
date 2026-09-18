package com.eap.mcp.recovery;

public class RecoveryRateLimitExceededException extends RuntimeException {

    public RecoveryRateLimitExceededException(String message) {
        super(message);
    }
}
