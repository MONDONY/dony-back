package com.dony.api.common;

public class DonyNotFoundException extends RuntimeException {

    public DonyNotFoundException(String message) {
        super(message);
    }

    public DonyNotFoundException(String entityName, Object id) {
        super(entityName + " not found with id: " + id);
    }
}
