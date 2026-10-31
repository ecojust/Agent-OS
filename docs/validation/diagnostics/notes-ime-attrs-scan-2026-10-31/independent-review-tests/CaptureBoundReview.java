package dev.agentos.compat;
import org.json.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
public class CaptureBoundReview {
 static int comparisons,allowedOldSizeChanges,checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static String block(int i,String attrs){return "  Window #"+i+" Window{"+(i==0?"abc":"def"+i)+" u0 DO_NOT_PERSIST_TITLE}:\n    mDisplayId=0 mSession=Session{private} mClient=android.os.BinderProxy@"+(i==0?"abc123":"def"+i)+"\n    mOwnerUid=10012 showForAllUsers=false package=com.android.inputmethod.latin appop=NONE\n    mAttrs="+attrs+"\n";}
 static String dump(String records){return "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"+records+"  mInputMethodWindow=Window{abc u0 DO_NOT_PERSIST_TITLE}\n";}
 static JSONObject parse(String raw)throws Exception{return NotesImeAssociationDiagnostic.parseWms(raw,10012,"com.android.inputmethod.latin");}
 static boolean oldSize(JSONObject p)throws Exception{if(!p.has("attrsDiagnostics"))return false;JSONArray a=p.getJSONObject("attrsDiagnostics").getJSONArray("entries");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("reason").equals("ATTRS_SIZE_LIMIT"))return true;return false;}
 static JSONObject compare(String raw)throws Exception{
  check(raw.length()<=NotesImeAssociationDiagnostic.MAX_BYTES,"corpus within inputcap");
  JSONObject old=CaptureBoundBaseline.parseWms(raw,10012,"com.android.inputmethod.latin"),now=parse(raw);
  JSONObject o=new JSONObject(old.toString()),n=new JSONObject(now.toString());o.remove("attrsDiagnostics");n.remove("attrsDiagnostics");comparisons++;
  if(!o.similar(n)){if(!oldSize(old))throw new AssertionError("change outside oldsize domain "+old+" -> "+now);allowedOldSizeChanges++;}
  if(now.has("attrsDiagnostics")){JSONObject d=now.getJSONObject("attrsDiagnostics");check(d.getInt("schemaVersion")==2&&d.getInt("scanLimitChars")==262144&&d.getInt("inputLimitChars")==262144,"v2explicitcaps");}
  check(!now.toString().contains("DO_NOT_PERSIST"),"rawdata excluded");return now;
 }
 static JSONObject entry(JSONObject p){return p.getJSONObject("attrsDiagnostics").getJSONArray("entries").getJSONObject(0);}
 static void refused(String attrs,String reason)throws Exception{JSONObject p=compare(dump(block(0,attrs)));check(p.getString("status").equals("UNAVAILABLE")&&entry(p).getString("reason").equals(reason),"late refusal "+reason+" "+p);}
 static ParcelFileDescriptor text(String s){return new ParcelFileDescriptor(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));}
 public static void main(String[]args)throws Exception{
  NotesImeAssociationDiagnostic.verifyParsers();
  String[] tails={"}"," ty=APPLICATION}"," ty=-1}","{".repeat(8)+"}".repeat(9),"", "\n    mAttrs={ty=APPLICATION}"};
  for(int padding:new int[]{0,1,100,8100,8150,8170,8180,8190,8191,8192,8193,8200,10000,11600,16000,24000,64000,200000})for(String tail:tails)
    compare(dump(block(0,"{ty=INPUT_METHOD "+" ".repeat(padding)+tail)));
  for(int depth=0;depth<=10;depth++)for(int pad:new int[]{0,8190,12000})for(String ty:new String[]{"INPUT_METHOD","2011","-1","A".repeat(65)})
    compare(dump(block(0,"{"+"{".repeat(depth)+" ".repeat(pad)+" ty="+ty+"}".repeat(depth+1))));
  String late="{ty=INPUT_METHOD "+" ".repeat(10000);
  check(compare(dump(block(0,late+"}"))).getString("status").equals("SELECTED"),"complete long block selected");
  refused(late+" ty=APPLICATION}","TOP_LEVEL_TYPE_DUPLICATE");
  refused(late+" ty=-1}","TYPE_VALUE_UNRECOGNIZED");
  refused(late+"{".repeat(8)+"}".repeat(9),"DEPTH_LIMIT");
  refused(late,"ATTRS_UNCLOSED");
  refused(late+"}\n    mAttrs={ty=APPLICATION}","ATTRS_ANCHOR_DUPLICATE");
  JSONObject nested=compare(dump(block(0,"{ty=INPUT_METHOD "+"{".repeat(4)+" ".repeat(11600)+"}".repeat(5))));
  check(nested.getString("status").equals("SELECTED"),"complete depth5 long block");
  check(compare(dump(block(0,"{ty=INPUT_METHOD}")+block(1,"{ty=APPLICATION "+" ".repeat(11600)+"}"))).getString("status").equals("SELECTED"),"complete long nonIME record no longer blocks candidate");
  check(compare(dump(block(0,"{ty=INPUT_METHOD}")+block(1,"{ty=APPLICATION "+" ".repeat(11600)))).getString("status").equals("UNAVAILABLE"),"incomplete nonIME still blocks");
  String empty=dump(block(0,"{ty=INPUT_METHOD }"));int padding=NotesImeAssociationDiagnostic.MAX_BYTES-empty.length();
  String exact=dump(block(0,"{ty=INPUT_METHOD "+" ".repeat(padding)+"}"));check(exact.length()==262144,"exactcap fixture");
  check(compare(exact).getString("status").equals("SELECTED"),"complete entirecapture atcap");
  JSONObject tooLarge=parse(exact+"x");check(tooLarge.getString("reason").equals("WMS_INPUT_LIMIT")&&!tooLarge.has("attrsDiagnostics"),"overinputcap rejected before record parse");
  AssertionError failure=new AssertionError("original hidden timeout");Throwable prior=new AssertionError("prior frozen diagnostic");failure.addSuppressed(prior);
  final int[] calls={0};UiAutomation automation=new UiAutomation(c->{calls[0]++;return text(c.endsWith("-a windows")?exact+"x":"");});
  NotesImeAssociationDiagnostic.attach(failure,automation,10012,"com.android.inputmethod.latin/.LatinIME");
  check(failure.getSuppressed().length==2&&failure.getSuppressed()[0]==prior&&calls[0]==3,"original preserved and3commands");
  String marker=failure.getSuppressed()[1].getMessage();JSONObject receipt=new JSONObject(marker.substring(NotesImeAssociationDiagnostic.MARKER.length()));JSONObject w=receipt.getJSONArray("commands").getJSONObject(0);
  check(w.getString("status").equals("BYTE_LIMIT")&&w.getInt("outputBytes")==262144&&w.getJSONObject("parsed").getString("status").equals("UNAVAILABLE"),"validprefix plus overflow never parsed");
  check(marker.length()<=16384&&marker.chars().allMatch(c->c<128)&&!marker.contains("DO_NOT_PERSIST"),"bounded ASCII marker privacy");
  AssertionError fullFailure=new AssertionError("original complete capture failure");
  NotesImeAssociationDiagnostic.attach(fullFailure,new UiAutomation(c->text(c.endsWith("-a windows")?exact:"")),10012,"com.android.inputmethod.latin/.LatinIME");
  String fullMarker=fullFailure.getSuppressed()[0].getMessage();JSONObject fullReceipt=new JSONObject(fullMarker.substring(NotesImeAssociationDiagnostic.MARKER.length()));
  JSONObject fullWms=fullReceipt.getJSONArray("commands").getJSONObject(0);
  check(fullWms.getString("status").equals("COMPLETE")&&fullWms.getJSONObject("parsed").getString("status").equals("SELECTED"),"worker parses complete entire capture atcap");
  check(fullMarker.length()<=16384&&!fullMarker.contains("DO_NOT_PERSIST")&&fullMarker.chars().allMatch(c->c<128),"complete captured source marker bounded and private");
  StringBuilder many=new StringBuilder();for(int i=0;i<8;i++)many.append(block(i,"{ty=INPUT_METHOD}")).append("    DO_NOT_PERSIST_PADDING ").append("x".repeat(30000)).append("\n  Windows waiting to destroy their surface:\n  Destroy #0 Window{f u0 DO_NOT_PERSIST_TITLE}:\n    mAttrs={ty=APPLICATION}\n");
  String detailed=dump(many.toString());check(detailed.length()<262144,"detailedmarker input undercapture cap");
  AssertionError detailedFailure=new AssertionError("original details failure");NotesImeAssociationDiagnostic.attach(detailedFailure,new UiAutomation(c->text(c.endsWith("-a windows")?detailed:"")),10012,"com.android.inputmethod.latin/.LatinIME");
  String detailedMarker=detailedFailure.getSuppressed()[0].getMessage();JSONObject detailedReceipt=new JSONObject(detailedMarker.substring(NotesImeAssociationDiagnostic.MARKER.length()));
  JSONObject details=detailedReceipt.getJSONArray("commands").getJSONObject(0).getJSONObject("parsed").getJSONObject("attrsDiagnostics");
  check(details.getInt("schemaVersion")==2&&details.getJSONArray("entries").length()==8&&details.getInt("scanLimitChars")==262144,"v2retains8failure details");
  check(detailedMarker.length()<=16384&&!detailedMarker.contains("DO_NOT_PERSIST")&&detailedMarker.chars().allMatch(c->c<128),"8detailmarker bounded and private");
  System.out.println(new JSONObject().put("completeCaptureMarkerChars",fullMarker.length()).put("retained8DetailsMarkerChars",detailedMarker.length()).put("fullWmsComparisons",comparisons).put("changesConfinedToOldSizeDomain",allowedOldSizeChanges).put("assertionsPassed",checks).put("exactCaptureChars",exact.length()).put("overCaptureTransport","BYTE_LIMIT_UNPARSED").put("truncatedMarkerChars",marker.length()).toString());
 }
}
