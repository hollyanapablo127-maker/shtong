package com.shtong.gateway;

import java.util.List;

public class ApiError extends RuntimeException {
    private final String code;
    private final String messageKey;
    private final int status;
    private final List<FieldError> fieldErrors;

    public ApiError(int status, String code, String messageKey) {
        this(status, code, messageKey, List.of());
    }

    public ApiError(int status, String code, String messageKey, List<FieldError> fieldErrors) {
        super(code);
        this.status = status;
        this.code = code;
        this.messageKey = messageKey;
        this.fieldErrors = fieldErrors == null ? List.of() : fieldErrors;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String messageKey() { return messageKey; }
    public List<FieldError> fieldErrors() { return fieldErrors; }
    public record FieldError(String field, String code) {}
}
