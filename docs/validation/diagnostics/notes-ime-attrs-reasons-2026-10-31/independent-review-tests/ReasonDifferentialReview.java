package dev.agentos.compat;
import org.json.*;
import java.util.*;
public class ReasonDifferentialReview {
 static int compared, checks;
 static void check(boolean ok,String name) {checks++;if(!ok)throw new AssertionError(name);}
 static String block(int index,String attrs) {return "  Window #"+index+" Window{"+(index==0?"abc":"def"+index)+" u0 DO_NOT_PERSIST_TITLE}:\n"
  +"    mDisplayId=0 mSession=Session{private} mClient=android.os.BinderProxy@abc123\n"
  +"    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n"
  +"    "+attrs+"\n";}
 static String dump(String records) {return "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"+records+"  mInputMethodWindow=Window{abc u0 DO_NOT_PERSIST_TITLE}\n";}
 static JSONObject compare(String d) throws Exception {
  JSONObject before=NotesImeAssociationBaseline.parseWms(d,10012,"com.android.inputmethod.latin");
  JSONObject after=NotesImeAssociationDiagnostic.parseWms(d,10012,"com.android.inputmethod.latin");
  JSONObject copy=new JSONObject(after.toString());copy.remove("attrsDiagnostics");
  compared++;if(!before.similar(copy))throw new AssertionError("outcome differs at "+compared+": "+before+" vs "+copy);
  if(after.toString().contains("DO_NOT_PERSIST"))throw new AssertionError("raw title/type leaked");
  if(after.has("attrsDiagnostics")) {
   JSONObject diag=after.getJSONObject("attrsDiagnostics");JSONArray entries=diag.getJSONArray("entries");
   if(entries.length()>8||diag.getInt("omittedCount")+entries.length()!=diag.getInt("unparsedCount"))throw new AssertionError("retention accounting");
   for(int i=0;i<entries.length();i++) {JSONObject e=entries.getJSONObject(i);
    if(e.getInt("scanChars")>8192||e.getInt("maxDepth")>9||(!e.isNull("typeValueLength")&&e.getInt("typeValueLength")>65))throw new AssertionError("bounds");}
  }
  return after;
 }
 static JSONObject entry(JSONObject p) {return p.getJSONObject("attrsDiagnostics").getJSONArray("entries").getJSONObject(0);}
 public static void main(String[] args) throws Exception {
  String[] variants={"mAttrs={ty=INPUT_METHOD}","mAttrs={sim={adjust=resize} ty=INPUT_METHOD}","mAttrs={ty=2011}","mAttrs={ty=APPLICATION}","no_attrs", "mAttrs={}","mAttrs={ty=INPUT_METHOD ty=INPUT_METHOD}","mAttrs={ty=INPUT_METHOD ty=APPLICATION}","mAttrs={ty=-1}","mAttrs={ty=0x7db}","mAttrs={ty=DO_NOT_PERSIST_ß}","mAttrs={ty=}","mAttrs={ty=INPUT_METHOD{bad}}","mAttrs={sim={ty=INPUT_METHOD}}","mAttrs={ty=INPUT_METHOD", "mAttrs={ty="+"A".repeat(65)+"}","mAttrs={ty="+"9".repeat(80)+"}","mAttrs={ty=INPUT_METHOD}\n    mAttrs={ty=APPLICATION}"};
  for(String v:variants)compare(dump(block(0,v)));
  for(int depth=0;depth<=12;depth++)for(int n:new int[]{0,1,63,64,65,66,67,68,200})for(boolean close:new boolean[]{false,true})
   compare(dump(block(0,"mAttrs={"+"{".repeat(depth)+" ty="+"A".repeat(n)+"}".repeat(depth)+(close?"}":""))));
  for(int pos=8170;pos<=8200;pos++)for(String type:new String[]{"INPUT_METHOD","2011","-1","A".repeat(64),"A".repeat(65),"9".repeat(80)})
   compare(dump(block(0,"mAttrs={"+" ".repeat(pos)+"ty="+type+"}")));
  String[] heads={"Hiding System Alert Windows:","Windows force removing:","Windows waiting to destroy their surface:","Windows waiting to resize:"};
  String[] kinds={"HIDING_SYSTEM_ALERT_WINDOWS","FORCE_REMOVING_WINDOWS","DESTROY_SURFACE_WINDOWS","RESIZING_WINDOWS"};
  for(int i=0;i<heads.length;i++) {
   String tail="\n  "+heads[i]+"\n  Destroy #0 Window{aaa u0 DO_NOT_PERSIST_TITLE}:\n    mAttrs={ty=APPLICATION}\n";
   JSONObject p=compare(dump(block(0,"mAttrs={ty=INPUT_METHOD}")+tail));JSONObject e=entry(p);
   check(e.getString("reason").equals("ATTRS_ANCHOR_DUPLICATE"),"tailduplicate reason");
   check(e.getString("recordEnd").equals("DUMP_END")&&e.getString("headerRelation").equals("MATCHES_SELECTED_REFERENCE"),"tailrecord boundaries");
   check(e.getBoolean("knownTailSectionBetweenAnchors")&&e.getJSONObject("firstKnownTailSection").getString("kind").equals(kinds[i]),"tail kind and position");
  }
  StringBuilder many=new StringBuilder();for(int i=0;i<20;i++)many.append(block(i,"mAttrs={ty=-1}"));
  JSONObject cap=compare(dump(many.toString())).getJSONObject("attrsDiagnostics");
  check(cap.getInt("recordCount")==20&&cap.getInt("unparsedCount")==20&&cap.getInt("omittedCount")==12&&cap.getJSONArray("entries").length()==8,"20failure retention8");
  check(cap.toString().length()<8192,"metadata retained bound below half marker cap");
  JSONObject signed=entry(compare(dump(block(0,"mAttrs={ty=-1}"))));
  check(signed.getString("typeLexicalClass").equals("SIGNED_DECIMAL")&&signed.getInt("typeValueLength")==2&&signed.getBoolean("typeValueComplete")&&signed.getInt("topLevelTypeCount")==1,"signed type coarse metadata");
  JSONObject prefix=entry(compare(dump(block(0,"mAttrs={ty="+"9".repeat(80)+"}"))));
  check(prefix.getString("typeLexicalClass").equals("DECIMAL")&&prefix.getInt("typeValueLength")==65&&!prefix.getBoolean("typeValueComplete"),"prefix classification explicitly incomplete");
  System.out.println(new JSONObject().put("outcomeComparisonsPassed",compared).put("metadataChecksPassed",checks).put("retained20FailureMetadataChars",cap.toString().length()).toString());
 }
}
