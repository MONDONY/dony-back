package com.dony.api.common;

import org.springframework.http.HttpStatus;

public class DonyBusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String title;

    public DonyBusinessException(HttpStatus status, String errorCode, String title, String detail) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
        this.title = title;
    }

    public HttpStatus getStatus() { return status; }

    public String getErrorCode() { return errorCode; }

    public String getTitle() { return title; }
}
