package dev.agentos.core;

import com.google.gson.*;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class ToolDispatcherTest {
    private static JsonObject json(String value) {return JsonParser.parseString(value).getAsJsonObject();}
    private static JsonObject accepted() {return json("{\"ok\":true,\"dispatchAccepted\":true,\"verification\":\"pending\"}");}
    private static ToolRequest request(RunControl control,String id,String tool,JsonObject args) {
        return new ToolRequest(1,control.getTaskId(),id,control.getExecutionEpoch(),control.getProfileId(),tool,args);
    }

    @Test public void protocolAndTrustedContextAreCheckedBeforeDeviceExecution() throws Exception {
        RunControl control=new RunControl("personal-test");AtomicInteger executed=new AtomicInteger();
        try(ToolDispatcher dispatcher=new ToolDispatcher((name,args,c)->{executed.incrementAndGet();return accepted();})) {
            JsonObject incompatible=dispatcher.dispatch(new ToolRequest(2,control.getTaskId(),"a",control.getExecutionEpoch(),control.getProfileId(),"observe",new JsonObject()),control);
            assertEquals("UNSUPPORTED_VERSION",incompatible.get("code").getAsString());
            JsonObject otherTask=dispatcher.dispatch(new ToolRequest(1,"other-task","b",control.getExecutionEpoch(),control.getProfileId(),"observe",new JsonObject()),control);
            assertEquals("PERMISSION_DENIED",otherTask.get("code").getAsString());
            JsonObject otherProfile=dispatcher.dispatch(new ToolRequest(1,control.getTaskId(),"c",control.getExecutionEpoch(),"other-profile","observe",new JsonObject()),control);
            assertEquals("PERMISSION_DENIED",otherProfile.get("code").getAsString());assertEquals(0,executed.get());
            JsonObject valid=dispatcher.dispatch(request(control,"d","observe",new JsonObject()),control);
            assertEquals(control.getTaskId(),valid.get("taskId").getAsString());
            assertEquals(control.getProfileId(),valid.get("profileId").getAsString());
            assertEquals(control.getExecutionEpoch(),valid.get("executionEpoch").getAsLong());
            assertEquals(1,valid.get("protocolVersion").getAsInt());assertEquals(1,executed.get());
        }
    }

    @Test public void repeatedCallUsesCanonicalImmutableRequestAndCachedResult() throws Exception {
        RunControl control=new RunControl();AtomicInteger executed=new AtomicInteger();AtomicReference<String> node=new AtomicReference<>();
        try(ToolDispatcher dispatcher=new ToolDispatcher((name,args,c)->{executed.incrementAndGet();node.set(args.get("nodeId").getAsString());return accepted();})) {
            JsonObject original=json("{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}");
            ToolRequest first=request(control,"same","click",original);original.addProperty("nodeId","changed-after-construction");
            JsonObject result=dispatcher.dispatch(first,control);assertEquals("n1",node.get());result.addProperty("ok",false);
            JsonObject duplicate=dispatcher.dispatch(request(control,"same","click",json("{\"expectedRevision\":\"r1\",\"nodeId\":\"n1\"}")),control);
            assertTrue(duplicate.get("ok").getAsBoolean());assertEquals(1,executed.get());
            JsonObject changed=dispatcher.dispatch(request(control,"same","click",json("{\"nodeId\":\"n2\",\"expectedRevision\":\"r1\"}")),control);
            assertEquals("INVALID_ARGUMENT",changed.get("code").getAsString());assertEquals(1,executed.get());
        }
    }

    @Test public void concurrentDuplicateDoesNotEnterASecondDeviceOperation() throws Exception {
        RunControl control=new RunControl();AtomicInteger executed=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ToolDispatcher dispatcher=new ToolDispatcher((name,args,c)->{executed.incrementAndGet();entered.countDown();release.await(3,TimeUnit.SECONDS);return accepted();})) {
            ToolRequest request=request(control,"same","click",json("{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"));
            Future<JsonObject> first=worker.submit(()->dispatcher.dispatch(request,control));assertTrue(entered.await(1,TimeUnit.SECONDS));
            JsonObject duplicate=dispatcher.dispatch(request,control);assertEquals("IN_PROGRESS",duplicate.get("code").getAsString());
            release.countDown();assertTrue(first.get(1,TimeUnit.SECONDS).get("ok").getAsBoolean());
            assertTrue(dispatcher.dispatch(request,control).get("ok").getAsBoolean());assertEquals(1,executed.get());
        } finally {release.countDown();control.cancel();worker.shutdownNow();}
    }

    @Test public void interruptedOrUncertainWriteIsNotRetriedAsANewSubmission() throws Exception {
        RunControl control=new RunControl();AtomicInteger executed=new AtomicInteger();
        try(ToolDispatcher dispatcher=new ToolDispatcher((name,args,c)->{executed.incrementAndGet();throw new IllegalStateException("sensitive device detail");})) {
            ToolRequest request=request(control,"write","click",json("{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"));
            JsonObject first=dispatcher.dispatch(request,control);
            assertEquals("OUTCOME_UNKNOWN",first.get("code").getAsString());assertEquals("unknown",first.get("verification").getAsString());
            assertFalse(first.toString().contains("sensitive device detail"));
            JsonObject repeated=dispatcher.dispatch(request,control);assertEquals(first,repeated);assertEquals(1,executed.get());
        }
    }

    @Test public void oldEpochCannotBeRevivedAfterPauseAndResume() throws Exception {
        RunControl control=new RunControl();AtomicInteger executed=new AtomicInteger();
        try(ToolDispatcher dispatcher=new ToolDispatcher((name,args,c)->{executed.incrementAndGet();return accepted();})) {
            ToolRequest old=request(control,"old","observe",new JsonObject());control.pause();control.resume();
            assertThrows(TaskControlException.class,()->dispatcher.dispatch(old,control));assertEquals(0,executed.get());
            assertTrue(dispatcher.dispatch(request(control,"fresh","observe",new JsonObject()),control).get("ok").getAsBoolean());assertEquals(1,executed.get());
        }
    }

    @Test public void unavailableScreenshotAndClosedDispatcherRejectWithoutDeviceAccess() throws Exception {
        RunControl control=new RunControl();AtomicInteger executed=new AtomicInteger();
        ToolDispatcher dispatcher=new ToolDispatcher((name,args,c)->{executed.incrementAndGet();return accepted();});
        JsonObject unavailable=dispatcher.dispatch(request(control,"screenshot","take_screenshot",json("{\"expectedRevision\":\"r1\"}")),control);
        assertEquals("CAPABILITY_UNAVAILABLE",unavailable.get("code").getAsString());
        dispatcher.close();JsonObject closed=dispatcher.dispatch(request(control,"late","observe",new JsonObject()),control);
        assertEquals("TASK_STOPPED",closed.get("code").getAsString());assertEquals(0,executed.get());
    }
}
