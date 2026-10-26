package dev.agentos.core;

public final class TaskControlException extends InterruptedException {
    private final String code;
    public TaskControlException(String code, String message) { super(message); this.code = code; }
    public String getCode() { return code; }
}
