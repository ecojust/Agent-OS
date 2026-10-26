package dev.agentos.core;

final class ModelException extends Exception {
    final String code;
    ModelException(String code, String message) { super(message); this.code = code; }
}
