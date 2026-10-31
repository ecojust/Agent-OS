package android.app;
import android.os.ParcelFileDescriptor;
public final class UiAutomation {
    public interface Shell { ParcelFileDescriptor execute(String command); }
    private final Shell shell;
    public UiAutomation(Shell shell) { this.shell = shell; }
    public ParcelFileDescriptor executeShellCommand(String command) { return shell.execute(command); }
}
