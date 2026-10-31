package android.content;
import android.content.pm.ApplicationInfo;
public final class Context {
    private final String name; private final ClassLoader loader;
    public boolean permissionThrows;
    public Context(String name, ClassLoader loader) { this.name=name; this.loader=loader; }
    public String getPackageName() { return name; }
    public ClassLoader getClassLoader() { return loader; }
    public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(); }
    public int checkSelfPermission(String permission) {
        if (permissionThrows) throw new SecurityException("private-permission-exception");
        return -1;
    }
}
