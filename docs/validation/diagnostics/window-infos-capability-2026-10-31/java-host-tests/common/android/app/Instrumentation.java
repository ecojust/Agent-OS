package android.app;
import android.content.Context;
import android.os.Bundle;
public final class Instrumentation {
    private final Context target,test;
    public int statusCalls,statusCode; public Bundle status;
    public Instrumentation(Context target, Context test) { this.target=target; this.test=test; }
    public Context getTargetContext() { return target; }
    public Context getContext() { return test; }
    public void sendStatus(int code, Bundle status) { statusCalls++; statusCode=code; this.status=status; }
}
