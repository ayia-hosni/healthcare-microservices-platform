package com.healthplatform.common.exception;

/** Thrown for domain/business rule violations (e.g. double-booking, invalid state transition). Maps to HTTP 409/422. */
public class BusinessException extends RuntimeException {
    private final String errorCode;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
