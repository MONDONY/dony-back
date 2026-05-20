package com.dony.api.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class DonyBusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String title;
    private final Map<String, Object> properties;

    public DonyBusinessException(HttpStatus status, String errorCode, String title, String detail) {
        this(status, errorCode, title, detail, Map.of());
    }

    public DonyBusinessException(HttpStatus status, String errorCode, String title, String detail,
                                 Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
        this.title = title;
        this.properties = properties;
    }

    public HttpStatus getStatus() { return status; }

    public String getErrorCode() { return errorCode; }

    public String getTitle() { return title; }

    public Map<String, Object> getProperties() { return properties; }
}
