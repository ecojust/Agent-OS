package dev.agentos.compat;
import org.json.JSONObject;
public final class NestedAttrsRepro {
 public static void main(String[] ignored) throws Exception {
  String token="android.os.BinderProxy@abc123";
  String prefix="WINDOW MANAGER WINDOWS (dumpsys window windows)\n  Window #0 Window{123 u0 synthetic-title}:\n"
    +"    mDisplayId=0 mSession=Session{synthetic} mClient="+token+"\n"
    +"    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n";
  String suffix="    isOnScreen=true isVisible=false isReadyForDisplay()=false\n"
    +"    mLastHidden=false mEnterAnimationPending=false mHasSurface=true Surface: shown=true\n"
    +"  mInputMethodWindow=Window{123 u0 synthetic-title}\n";
  JSONObject plain=NotesImeAssociationDiagnostic.parseWms(prefix+"    mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD}\n"+suffix,10012,"com.android.inputmethod.latin");
  JSONObject nested=NotesImeAssociationDiagnostic.parseWms(prefix+"    mAttrs={(0,0)(fillxwrap) sim={adjust=resize} ty=INPUT_METHOD}\n"+suffix,10012,"com.android.inputmethod.latin");
  if(!"SELECTED".equals(plain.getString("status"))||!"UNAVAILABLE".equals(nested.getString("status"))||nested.getInt("inputMethodRecordCount")!=0)throw new AssertionError("Expected exact-source nested-attrs extraction failure");
  System.out.println(new JSONObject().put("plain",plain).put("nested",nested).put("syntheticFixtureOnly",true));
 }
}
