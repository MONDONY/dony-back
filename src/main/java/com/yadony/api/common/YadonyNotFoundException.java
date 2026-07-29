package com.yadony.api.common;

public class YadonyNotFoundException extends RuntimeException {

    public YadonyNotFoundException(String message) {
        super(message);
    }

    public YadonyNotFoundException(String entityName, Object id) {
        super(entityName + " not found with id: " + id);
    }
}
