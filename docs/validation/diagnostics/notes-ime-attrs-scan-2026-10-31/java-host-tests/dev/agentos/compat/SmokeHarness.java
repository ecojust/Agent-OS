package dev.agentos.compat;
final class SmokeHarness {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
