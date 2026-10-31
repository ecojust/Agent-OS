package android.os;
public final class SystemClock {
    private SystemClock() { }
    public static volatile Error injected;
    public static long elapsedRealtime() { if (injected != null) throw injected; return System.nanoTime() / 1_000_000L; }
}
