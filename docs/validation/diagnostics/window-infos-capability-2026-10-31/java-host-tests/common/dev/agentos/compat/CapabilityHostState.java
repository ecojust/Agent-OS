package dev.agentos.compat;
public final class CapabilityHostState {
    public static int outerInitialized,windowInitialized,displayInitialized,constructors,listenerCalls,clockReads;
    public static boolean enforceLookupOrder;
    public static void reset() { outerInitialized=windowInitialized=displayInitialized=constructors=listenerCalls=clockReads=0; enforceLookupOrder=false; }
    public static void outerInit() {
        outerInitialized++;
        if (enforceLookupOrder && clockReads<29) throw new AssertionError("class initialized before all lookups");
    }
}
