package dev.agentos.compat;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Controlled public API classes, not Android runtime capability or permissions. */
public final class CapabilityHostTest {
    private static int checks;
    private static Path fixtures;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static final class FixtureLoader extends URLClassLoader {
        int windowClassRequests;
        FixtureLoader(String variant) throws Exception {
            super(new URL[] {fixtures.resolve(variant).toUri().toURL()}, CapabilityHostTest.class.getClassLoader());
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("android.window.WindowInfosListenerForTest")) windowClassRequests++;
            return super.loadClass(name, resolve);
        }
    }
    private static JSONObject item(JSONObject report, String id) throws Exception {
        JSONArray entries = report.getJSONArray("lookups");
        for (int i = 0; i < entries.length(); i++) if (id.equals(entries.getJSONObject(i).getString("id"))) return entries.getJSONObject(i);
        throw new AssertionError("Missing fixed catalog slot");
    }
    private static JSONObject inspect(FixtureLoader loader) throws Exception {
        CapabilityHostState.reset(); CapabilityHostState.enforceLookupOrder=true;
        return WindowInfosCapabilityProbe.inspect(loader, () -> { CapabilityHostState.clockReads++; return 100L; });
    }
    private static void noWindowData(JSONObject report) throws Exception {
        check(CapabilityHostState.windowInitialized == 0 && CapabilityHostState.displayInitialized == 0,
                "Member lookup initialized data classes or read static gap values");
        check(CapabilityHostState.listenerCalls == 0, "Listener method was invoked");
        check(report.getJSONObject("capability").getString("listenerRegistration").equals("NOT_ATTEMPTED")
                && report.getJSONObject("capability").getString("windowData").equals("NOT_READ"), "Metadata lookup became listener/data acceptance");
        check(!report.toString().contains("private-"), "Instance fields or arbitrary exception text escaped");
    }
    private static void expectedAndGapSurfaces() throws Exception {
        try (FixtureLoader loader = new FixtureLoader("normal")) {
            JSONObject report = inspect(loader);
            check(report.getInt("lookupLimit") == 27 && report.getInt("lookupAttempts") == 27 && report.getJSONArray("lookups").length() == 27,
                    "Fixed lookup count or denominator changed");
            check(report.getString("probeExecution").equals("COMPLETE") && report.getJSONObject("capability").getString("requiredSurface").equals("MATCHED"),
                    "Complete known public surface did not match");
            check(report.getJSONObject("constructor").getString("status").equals("CONSTRUCTED")
                    && report.getJSONObject("constructor").getInt("attempts") == 1 && report.getJSONObject("constructor").getInt("completed") == 1,
                    "The one allowed constructor was not separately reported");
            check(CapabilityHostState.outerInitialized == 1 && CapabilityHostState.constructors == 1 && CapabilityHostState.clockReads == 30,
                    "ForTest initialized before all lookups or constructor ran more than once");
            for (String gap : new String[] {"inputConfig","isClone","frame","layoutParamsType","ownerUid"})
                check(item(report,"gap.windowInfo."+gap).getString("status").equals("NOT_FOUND_OR_FILTERED"), "Missing public gap was treated as underlying absence");
            noWindowData(report);
        }
        try (FixtureLoader loader = new FixtureLoader("gap")) {
            JSONObject report = inspect(loader);
            for (String gap : new String[] {"inputConfig","isClone","frame","layoutParamsType","ownerUid"}) {
                JSONObject entry=item(report,"gap.windowInfo."+gap);
                check(entry.getString("status").equals("FOUND_SOURCE_UNLISTED") && entry.isNull("typeMatches"), "Unexpected gap field inherited an invented expected type");
            }
            check(item(report,"gap.windowInfo.inputConfig").getBoolean("static")
                    && item(report,"gap.windowInfo.inputConfig").getString("observedType").equals("STRING")
                    && item(report,"gap.windowInfo.isClone").getString("observedType").equals("OTHER"), "Gap metadata did not report the observed fixed type category");
            noWindowData(report);
        }
    }
    private static void mismatchesMissingAndConstruction() throws Exception {
        try (FixtureLoader loader = new FixtureLoader("mismatch")) {
            JSONObject report = inspect(loader);
            for (String id : new String[] {"field.windowInfo.displayId","field.windowInfo.bounds","method.add.biConsumer","method.remove.consumer"})
                check(item(report,id).getString("status").equals("FOUND_MISMATCH"), "Wrong exact type/modifier was accepted");
            check(report.getJSONObject("capability").getString("requiredSurface").equals("NOT_FULLY_MATCHED"), "Mismatched members became capability acceptance");
            noWindowData(report);
        }
        try (FixtureLoader loader = new FixtureLoader("missing")) {
            JSONObject report = inspect(loader);
            check(item(report,"field.windowInfo.touchableRegion").getString("status").equals("NOT_FOUND_OR_FILTERED")
                    && item(report,"method.remove.biConsumer").getString("status").equals("NOT_FOUND_OR_FILTERED"), "Private member was reached outside public lookup");
            check(report.getString("probeExecution").equals("COMPLETE") && report.getJSONObject("constructor").getInt("attempts") == 1,
                    "Ordinary missing-member observation prevented completion");
            noWindowData(report);
        }
        try (FixtureLoader loader = new FixtureLoader("abstract")) {
            JSONObject report = inspect(loader);
            check(item(report,"class.forTest").getString("status").equals("FOUND_MISMATCH")
                    && !item(report,"class.forTest").getBoolean("typeMatches"), "Abstract ForTest was accepted as the known concrete class");
            check(item(report,"constructor.forTest").getString("status").equals("SKIPPED_CLASS_UNAVAILABLE")
                    && report.getJSONObject("constructor").getInt("attempts") == 0 && CapabilityHostState.constructors == 0,
                    "Unavailable class still authorized construction");
            noWindowData(report);
        }
        try (FixtureLoader loader = new FixtureLoader("constructor_failure")) {
            JSONObject report = inspect(loader);
            check(report.getJSONObject("constructor").getString("status").equals("INVOCATION_FAILED")
                    && report.getJSONObject("constructor").getInt("attempts") == 1 && report.getJSONObject("constructor").getInt("completed") == 0,
                    "Constructor failure became construction success or disappeared");
            check(report.getJSONObject("capability").getString("requiredSurface").equals("MATCHED") && report.getString("probeExecution").equals("COMPLETE"),
                    "Lookup completion and constructor invocation were conflated");
            noWindowData(report);
        }
    }
    private static void unavailableClassesAndDeadline() throws Exception {
        String[] expected={"NOT_FOUND_OR_FILTERED","ACCESS_DENIED","LINKAGE_ERROR","REFLECTION_ERROR"};
        for (int mode=0;mode<expected.length;mode++) {
            final int selected=mode;
            ClassLoader loader=new ClassLoader(CapabilityHostTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("android.window.WindowInfosListenerForTest")) {
                        if(selected==0) throw new ClassNotFoundException("private-missing");
                        if(selected==1) throw new SecurityException("private-denied");
                        if(selected==2) throw new NoClassDefFoundError("private-linkage");
                        throw new IllegalStateException("private-runtime");
                    }
                    return super.loadClass(name,resolve);
                }
            };
            JSONObject report=WindowInfosCapabilityProbe.inspect(loader,()->100L);
            check(report.getInt("lookupAttempts")==3 && report.getJSONArray("lookups").length()==27, "Missing classes removed dependent denominator slots");
            check(item(report,"class.forTest").getString("status").equals(expected[mode])
                    && item(report,"constructor.forTest").getString("status").equals("SKIPPED_CLASS_UNAVAILABLE"), "Class failure was mislabeled or retried");
            check(report.getJSONObject("constructor").getInt("attempts")==0 && !report.toString().contains("private-"), "Class failure triggered construction or exposed exception text");
        }
        try(FixtureLoader loader=new FixtureLoader("normal")) {
            CapabilityHostState.reset();
            JSONObject report=WindowInfosCapabilityProbe.inspect(loader,()->(CapabilityHostState.clockReads++)*1000L);
            check(report.getString("probeExecution").equals("INCOMPLETE_DEADLINE") && report.getBoolean("deadlineExceeded")
                    && report.getInt("lookupAttempts")==4, "Between-operation deadline was not enforced");
            check(item(report,"method.add.consumer").getString("status").equals("SKIPPED_DEADLINE")
                    && report.getJSONObject("constructor").getString("status").equals("SKIPPED_DEADLINE")
                    && CapabilityHostState.constructors==0 && CapabilityHostState.outerInitialized==0, "Deadline still invoked or initialized ForTest");
            check(report.getJSONObject("capability").getString("requiredSurface").equals("UNVERIFIED_DEADLINE"), "Deadline truncation became complete capability");
            noWindowData(report);
        }
    }
    private static Bundle arguments() {
        Bundle args=new Bundle(); args.putString("windowInfosCapability","true"); args.putString("sourceSha","a".repeat(40));
        args.putString("runId","123");args.putString("runAttempt","1");args.putString("expectedApi","37");args.putString("deviceSerial","emulator-5554");return args;
    }
    private static Instrumentation setup(FixtureLoader loader,Bundle args,boolean permissionThrows) {
        Context target=new Context("dev.agentos.compat",loader);target.permissionThrows=permissionThrows;
        Instrumentation instrumentation=new Instrumentation(target,new Context("dev.agentos.compat.test",loader));
        InstrumentationRegistry.instrumentation=instrumentation;InstrumentationRegistry.arguments=args;return instrumentation;
    }
    private static JSONObject testEntryAndGates() throws Exception {
        JSONObject cross;
        try(FixtureLoader loader=new FixtureLoader("normal")) {
            CapabilityHostState.reset(); Instrumentation instrumentation=setup(loader,arguments(),false);
            new WindowInfosCapabilityInstrumentedTest().inspectPublicTestApiSurface();
            check(instrumentation.statusCalls==1 && instrumentation.statusCode==2
                    && instrumentation.status.getString("class").equals(WindowInfosCapabilityInstrumentedTest.TEST_CLASS)
                    && instrumentation.status.getString("test").equals(WindowInfosCapabilityInstrumentedTest.TEST_METHOD), "Marker lost explicit same-block method binding");
            String marker=instrumentation.status.getString("windowInfosCapabilityDiagnostic");
            check(marker.startsWith(WindowInfosCapabilityProbe.MARKER) && marker.length()<=WindowInfosCapabilityProbe.MAX_MARKER_CHARS
                    && marker.chars().allMatch(value->value<128), "Bounded ASCII capability marker missing");
            cross=new JSONObject(marker.substring(WindowInfosCapabilityProbe.MARKER.length()));
            check(cross.getInt("sdkInt")==37 && cross.getJSONObject("process").getInt("targetSdk")==35
                    && cross.getJSONObject("permissions").getString("accessSurfaceFlinger").equals("DENIED"), "Actual test-entry metadata was not recorded independently");
            noWindowData(cross);
        }
        for(int mode=0;mode<4;mode++) {
            try(FixtureLoader loader=new FixtureLoader("normal")) {
                CapabilityHostState.reset();Bundle args=arguments();
                if(mode==0) args.putString("windowInfosCapability","false");
                if(mode==1) Build.VERSION.SDK_INT=36;
                if(mode==2) args.putString("sourceSha","bad-source");
                if(mode==3) args.putString("expectedApi","36");
                Instrumentation instrumentation=setup(loader,args,false);boolean refused=false;
                try { new WindowInfosCapabilityInstrumentedTest().inspectPublicTestApiSurface(); }
                catch(AssertionError expected) { refused=true; }
                finally { Build.VERSION.SDK_INT=37; }
                check(refused && instrumentation.statusCalls==0 && loader.windowClassRequests==0 && CapabilityHostState.constructors==0,
                        "Gate or source validation failure performed a capability operation");
            }
        }
        try(FixtureLoader loader=new FixtureLoader("normal")) {
            CapabilityHostState.reset(); Instrumentation instrumentation=setup(loader,arguments(),true);
            Build.ID="private build value\n";Build.FINGERPRINT="x".repeat(1025);
            try { new WindowInfosCapabilityInstrumentedTest().inspectPublicTestApiSurface(); }
            finally { Build.ID="HOST";Build.FINGERPRINT="synthetic-host-fixture"; }
            JSONObject report=new JSONObject(instrumentation.status.getString("windowInfosCapabilityDiagnostic").substring(WindowInfosCapabilityProbe.MARKER.length()));
            check(report.getJSONObject("build").isNull("buildId") && report.getJSONObject("build").isNull("fingerprintSha256")
                    && report.getJSONObject("permissions").getString("accessSurfaceFlinger").equals("CHECK_FAILED") && !report.toString().contains("private-"),
                    "Rejected metadata or permission failure text escaped");
        }
        return cross;
    }
    public static void main(String[] args) throws Exception {
        fixtures=Path.of(args[0]);expectedAndGapSurfaces();mismatchesMissingAndConstruction();unavailableClassesAndDeadline();
        JSONObject cross=testEntryAndGates();
        Files.writeString(Path.of(args[1]),new JSONObject().put("synthetic",true).put("kind","controlled-host-capability-fixture")
                .put("report",cross).toString(2)+"\n",StandardCharsets.UTF_8);
        System.out.println("capability_host_checks="+checks);
        System.out.println("public_lookup_slots=27");
        System.out.println("per_probe_constructor_attempt_limit=1");
        System.out.println("capability_host_tests=passed");
        System.out.println("cross_fixture="+args[1]);
    }
}
