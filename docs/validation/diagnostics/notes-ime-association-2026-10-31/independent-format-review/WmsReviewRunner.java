package dev.agentos.compat;
import org.json.*;
public class WmsReviewRunner {
 private static int n;
 private static String window(String attrs) {
  return "WINDOW MANAGER WINDOWS (dumpsys window windows)\n  Window #0 Window{123 u0 private-title}:\n"
   +"    mDisplayId=0 mSession=Session{private} mClient=android.os.BinderProxy@abc123\n"
   +"    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n"
   +"    "+attrs+"\n    isOnScreen=true isVisible=false isReadyForDisplay()=false\n"
   +"    mLastHidden=false mEnterAnimationPending=false mHasSurface=true Surface: shown=true\n"
   +"  mInputMethodWindow=Window{123 u0 private-title}\n";
 }
 private static JSONObject parse(String attrs) throws Exception { return NotesImeAssociationDiagnostic.parseWms(window(attrs),10012,"com.android.inputmethod.latin"); }
 private static void expect(String attrs,boolean selected,String label) throws Exception {
  n++; JSONObject got=parse(attrs); if(selected != got.getString("status").equals("SELECTED")) throw new AssertionError(label+" "+got);
  if(got.toString().contains("private-title")) throw new AssertionError("rawtitle");
 }
 public static void main(String[]args) throws Exception {
  if(args.length>0&&args[0].equals("old")) {
   JSONObject baseline=parse("mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD}");
   JSONObject nested=parse("mAttrs={(0,0)(fillxwrap) gr=BOTTOM sim={state=unchanged adjust=resize} ty=INPUT_METHOD fmt=TRANSPARENT}");
   if(!baseline.getString("status").equals("SELECTED")||!nested.getString("status").equals("UNAVAILABLE")||nested.getInt("inputMethodRecordCount")!=0) throw new AssertionError("old reproduction differs");
   System.out.println(new JSONObject().put("baseline",baseline).put("legalNestedSim",nested));return;
  }
  expect("mAttrs={(0,0)(fillxwrap) gr=BOTTOM sim={state=unchanged adjust=resize} ty=INPUT_METHOD fmt=TRANSPARENT}",true,"legal nested sim");
  expect("mAttrs={(0,0)(fillxwrap) sim={adjust=resize} ty=2011}",true,"numeric type");
  expect("mAttrs={(0,0)(fillxwrap) sim={adjust=resize} ty=INPUT_METHOD\n      fl=NOT_FOCUSABLE\n      paramsForRotation=[{sim={adjust=pan} ty=APPLICATION}]\n    }",true,"nested rotation type ignored");
  expect("mAttrs={(0,0)(fillxwrap) sim={ty=INPUT_METHOD} ty=APPLICATION}",false,"nested type cannot become IME");
  expect("mAttrs={(0,0)(fillxwrap) sim={ty=INPUT_METHOD}}",false,"nested-only type unavailable");
  expect("mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD ty=INPUT_METHOD}",false,"duplicate same top type");
  expect("mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD ty=APPLICATION}",false,"conflicting top type");
  expect("mAttrs={(0,0)(fillxwrap) sim={adjust=resize} ty=INPUT_METHOD",false,"missing outer close");
  expect("mAttrs={(0,0)(fillxwrap) ty=APPLICATION}\n    mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD}",false,"duplicate mAttrs");
  expect("mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD_EXTRA}",false,"token boundary");
  expect("mAttrs={(0,0)(fillxwrap) title=ty=INPUT_METHOD ty=APPLICATION}",false,"embedded fake type");
  expect("mAttrs={(0,0)(fillxwrap) ty=INPUT_METHOD "+"x".repeat(8200)+"}",false,"oversized attributes");
  System.out.println("Independent WMS nested-attribute fixtures passed: "+n);
 }
}
