package androidx.test.platform.app;
import android.app.Instrumentation;
import android.os.Bundle;
public final class InstrumentationRegistry {
    public static Instrumentation instrumentation;
    public static Bundle arguments;
    public static Instrumentation getInstrumentation() { return instrumentation; }
    public static Bundle getArguments() { return arguments; }
}
