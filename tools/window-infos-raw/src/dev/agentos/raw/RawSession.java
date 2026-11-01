package dev.agentos.raw;

/** Loaded by the trampoline without resolving hidden platform listener classes. */
public interface RawSession {
    void registerAndCaptureInitial();
    void unregisterOnce();
}
