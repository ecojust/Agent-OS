package dev.agentos.compat;
import org.json.JSONObject;
public final class ActualParserBridge {
    public static void main(String[] args) throws Exception {
        String header = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n  Window #0 Window{abc u0 private-title}:\n";
        String footer = "\n  mInputMethodWindow=Window{abc u0 private-title}\n";
        String duplicate = header + "    mAttrs={ty=INPUT_METHOD}\n  Windows waiting to resize:\n  Resizing #0 Window{abc u0 private-title}:\n    mAttrs={ty=INPUT_METHOD}" + footer;
        String prefix = header + "    mAttrs={ty=" + "A".repeat(80) + "}" + footer;
        System.out.println(new JSONObject().put("synthetic", true)
                .put("helperSha256", "c920dce631dbd4e81397282dbf598c0b754cbcb7934eb32c98be90172f9ca90d")
                .put("duplicateTail", NotesImeAssociationDiagnostic.parseWms(duplicate, 10012, "com.android.inputmethod.latin"))
                .put("incompleteTypePrefix", NotesImeAssociationDiagnostic.parseWms(prefix, 10012, "com.android.inputmethod.latin")).toString(2));
    }
}
