package com.youlxb2008.admin.exceptions.enums;

public enum ResourceStatusEnum {
    WAIT_VALID(1,"待生效"),
    VALID(2,"有效"),
    INVALID(3,"无效"),
    ;
    private final Integer code;

    private final String message;

    ResourceStatusEnum(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
