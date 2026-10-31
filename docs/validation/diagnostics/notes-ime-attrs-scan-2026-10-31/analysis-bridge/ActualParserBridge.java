package dev.agentos.compat;
import org.json.JSONObject;

/** Synthetic captured-text fixtures; not device dumps or collector execution. */
public final class ActualParserBridge {
    public static void main(String[] args) throws Exception {
        String prefix = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
                + "  mInputMethodWindow=Window{abc u0 private-window-title}\n"
                + "  Window #0 Window{abc u0 private-window-title}:\n"
                + "    mDisplayId=0 mSession=Session{private-session} mClient=android.os.BinderProxy@abc123\n"
                + "    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n    mAttrs=";
        String longPrefix = prefix + "{ty=INPUT_METHOD " + " ".repeat(12000);
        System.out.println(new JSONObject().put("synthetic", true)
                .put("helperSha256", "5de8008990573422edb9cbd33bace0dfaa2cfd860ddea1d49a17d30183534bec")
                .put("completeBeyondLegacyLimit", parse(longPrefix + "}"))
                .put("unclosedBeyondLegacyLimit", parse(longPrefix))
                .put("duplicateTypeBeyondLegacyLimit", parse(longPrefix + "ty=2011}"))
                .put("directInputLimit", parse("x".repeat(262145))).toString(2));
    }
    private static JSONObject parse(String text) throws Exception {
        return NotesImeAssociationDiagnostic.parseWms(text, 10012, "com.android.inputmethod.latin");
    }
}
