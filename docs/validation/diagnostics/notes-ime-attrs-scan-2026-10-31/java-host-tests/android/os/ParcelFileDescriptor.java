package android.os;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
public final class ParcelFileDescriptor implements Closeable {
    private final InputStream input;
    public ParcelFileDescriptor(InputStream input) { this.input = input; }
    @Override public void close() throws IOException { input.close(); }
    public static final class AutoCloseInputStream extends FilterInputStream {
        public AutoCloseInputStream(ParcelFileDescriptor descriptor) { super(descriptor.input); }
    }
}
