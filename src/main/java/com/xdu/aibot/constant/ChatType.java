package com.xdu.aibot.constant;

public enum ChatType {
    PDF("pdf"),
    SERVICE("service");

    private final String type;

    ChatType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }
}
