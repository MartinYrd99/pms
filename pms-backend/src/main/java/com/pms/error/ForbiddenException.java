package com.pms.error;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String messageKey) {
        super(messageKey);
    }
}