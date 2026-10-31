package dev.agentos.compat;
import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import org.json.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
public class ReasonMarkerReview {
 public static void main(String[]args) throws Exception {
  StringBuilder records=new StringBuilder();
  for(int i=0;i<8;i++)records.append(ReasonDifferentialReview.block(i,"mAttrs={ty=INPUT_METHOD}"))
   .append("    DO_NOT_PERSIST_PRIVATE_PADDING ").append("x".repeat(30000)).append("\n  Windows waiting to destroy their surface:\n  Destroy #0 Window{f u0 DO_NOT_PERSIST_TITLE}:\n    mAttrs={ty=APPLICATION}\n");
  String raw=ReasonDifferentialReview.dump(records.toString());
  if(raw.getBytes(StandardCharsets.UTF_8).length>=NotesImeAssociationDiagnostic.MAX_BYTES)throw new AssertionError("synthetic payload exceeds declared read bound");
  AtomicInteger calls=new AtomicInteger();UiAutomation automation=new UiAutomation(command->{calls.incrementAndGet();String out=command.endsWith("-a windows")?raw:command.endsWith("a11y")?"AccessibilityWindowsPopulator\n mWindowsNotificationEnabled: true\n No visible windows\n No window handles on displays\n":"ACCESSIBILITY MANAGER (dumpsys accessibility)\n";return new ParcelFileDescriptor(new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)));});
  AssertionError failure=new AssertionError("original failure remains");Throwable prior=new AssertionError("prior frozen marker");failure.addSuppressed(prior);
  NotesImeAssociationDiagnostic.attach(failure,automation,10012,"com.android.inputmethod.latin/.LatinIME");
  if(failure.getSuppressed().length!=2||failure.getSuppressed()[0]!=prior||!failure.getMessage().equals("original failure remains")||calls.get()!=3)throw new AssertionError("original/context/command bound changed");
  String marker=failure.getSuppressed()[1].getMessage();
  if(marker.length()>16*1024||marker.contains("DO_NOT_PERSIST")||!marker.chars().allMatch(c->c<128))throw new AssertionError("marker bound/privacy/ASCII");
  JSONObject d=new JSONObject(marker.substring(NotesImeAssociationDiagnostic.MARKER.length()));JSONObject a=d.getJSONArray("commands").getJSONObject(0).getJSONObject("parsed").getJSONObject("attrsDiagnostics");
  if(d.getBoolean("diagnosticTruncated")||a.getInt("unparsedCount")!=8||a.getJSONArray("entries").length()!=8||a.getInt("omittedCount")!=0)throw new AssertionError("bounded details unavailable");
  System.out.println(new JSONObject().put("syntheticWmsBytes",raw.getBytes(StandardCharsets.UTF_8).length).put("markerChars",marker.length()).put("retainedReasons",8).put("commands",calls.get()).put("privacyAsciiOriginalPreservation","passed").toString());
 }
}
