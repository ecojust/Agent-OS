package dev.agentos.core;

/**
 * Revalidates the original task's permission to send data to its frozen model
 * configuration. Implementations must inspect only in-memory identities and a
 * configuration lease; no storage, UI, Binder or network work belongs here.
 * A true value is a local check, not an atomic guarantee about a network write.
 */
@FunctionalInterface
public interface DisclosureGuard {
    boolean isCurrent(RunControl originalControl, long originalEpoch);
}
