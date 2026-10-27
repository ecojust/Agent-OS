package dev.agentos.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Native PNG ownership is transferred, never serialized to get through the local tool layer. */
public final class LocalToolResultTest {
    static final String REVISION = "native-image-r1";

    static JsonObject metadata() {
        JsonObject result = new JsonObject(); result.addProperty("ok", true);
        JsonObject data = new JsonObject(); data.addProperty("revision", REVISION);
        data.addProperty("width", 2); data.addProperty("height", 1);
        data.addProperty("captureId", "local-synthetic-capture");
        result.add("data", data); return result;
    }
    static ToolRequest request(RunControl control, String call, String name) {
        JsonObject args = new JsonObject();
        if (ToolCatalog.requiresRevision(name)) args.addProperty("expectedRevision", REVISION);
        if ("click".equals(name)) args.addProperty("nodeId", "n1");
        return new ToolRequest(ToolCatalog.PROTOCOL_VERSION, control.getTaskId(), call,
                control.getExecutionEpoch(), control.getProfileId(), name, args);
    }
    abstract static class NativeHandler implements ToolHandler {
        @Override public JsonObject execute(String name, JsonObject args, RunControl control) {
            throw new AssertionError("The complete original request must reach the native adapter");
        }
        @Override public Set<String> availableTools() {
            Set<String> names = new HashSet<>(ToolHandler.super.availableTools()); names.add("take_screenshot"); return names;
        }
        @Override public abstract LocalToolResult execute(ToolRequest request, RunControl control) throws Exception;
    }

    @Test public void theOriginalPngCrossesDispatchWithoutAnotherCloneOrReservation() throws Exception {
        byte[] bytes = PngImageTest.png(); ImageWorkset pool = new ImageWorkset(bytes.length + PngValidator.SCRATCH_BYTES);
        AtomicInteger clones = new AtomicInteger(), executions = new AtomicInteger();
        RunControl control = new RunControl("native-profile"); ToolRequest request = request(control, "original-call", "take_screenshot");
        try (PngImage original = PngImage.fromBytes(bytes, pool, source -> { clones.incrementAndGet(); return source.clone(); });
             ImageWorkset.Reservation occupied = pool.reserve(pool.limitBytes() - pool.reservedBytes());
             ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
                 @Override public LocalToolResult execute(ToolRequest received, RunControl active) {
                     assertSame(request, received); assertSame(control, active);
                     executions.incrementAndGet();
                     JsonObject source = metadata(); LocalToolResult result = LocalToolResult.withPng(source, original);
                     source.getAsJsonObject("data").addProperty("revision", "mutated-after-creation");
                     return result;
                 }
             }); ToolDispatchResult dispatched = dispatcher.dispatchWithAttachment(request, control)) {
            assertTrue(dispatched.getResult().get("ok").getAsBoolean());
            assertEquals(pool.limitBytes(), pool.reservedBytes()); assertEquals(1, clones.get());
            JsonObject visible = dispatched.getResult(); visible.addProperty("ok", false);
            assertTrue(dispatched.getResult().get("ok").getAsBoolean());
            assertFalse(dispatched.getResult().toString().contains("imageDataUrl"));
            assertFalse(dispatched.getResult().toString().contains(Base64.getEncoder().encodeToString(bytes)));
            try (ImageAttachment attachment = dispatched.takeImage()) {
                assertNotNull(attachment); assertNull(dispatched.takeImage());
                assertEquals(request.callId, attachment.callId); assertEquals(request.taskId, attachment.taskId);
                assertEquals(request.profileId, attachment.profileId); assertEquals(request.executionEpoch, attachment.executionEpoch);
                try (PngImage transferred = attachment.consumePng(control, request.executionEpoch, REVISION)) {
                    assertSame(original, transferred); assertArrayEquals(bytes, PngImageTest.read(transferred));
                    try (ToolDispatchResult duplicate = dispatcher.dispatchWithAttachment(request, control)) {
                        assertNull(duplicate.takeImage()); assertEquals(1, executions.get());
                        assertEquals("not_replayed", duplicate.getResult().getAsJsonObject("imageAttachment").get("delivery").getAsString());
                    }
                }
            }
            assertEquals(occupied.bytes(), pool.reservedBytes());
        }
        assertEquals(0, pool.reservedBytes());
    }

    @Test public void returningTheSameConsumedContainerForAnotherCallCannotReplayOrCloseTheFirstOwner() throws Exception {
        byte[] bytes = PngImageTest.png(); ImageWorkset pool = new ImageWorkset(20_000); RunControl control = new RunControl();
        try (PngImage original = PngImage.fromBytes(bytes, pool);
             LocalToolResult local = LocalToolResult.withPng(metadata(), original);
             ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
                 @Override public LocalToolResult execute(ToolRequest request, RunControl active) { return local; }
             }); ToolDispatchResult first = dispatcher.dispatchWithAttachment(request(control, "first", "take_screenshot"), control)) {
            try (ImageAttachment attachment = first.takeImage()) {
                assertNotNull(attachment);
                try (ToolDispatchResult reused = dispatcher.dispatchWithAttachment(request(control, "new-id", "take_screenshot"), control)) {
                    assertEquals("INVALID_RESULT", reused.getResult().get("code").getAsString());
                    assertNull(reused.takeImage()); assertFalse(original.isClosed());
                }
                local.close(); assertFalse("Consumed container no longer owns the first attachment", original.isClosed());
                try (PngImage accepted = attachment.consumePng(control, control.getExecutionEpoch(), REVISION)) {
                    assertSame(original, accepted);
                }
            }
        }
        assertEquals(0, pool.reservedBytes());
    }

    @Test public void wrongToolStatusRevisionDimensionsMixedEncodingAndClosedOwnersAreRejectedAndReleased() throws Exception {
        byte[] bytes = PngImageTest.png();
        for (int mode = 0; mode < 11; mode++) {
            ImageWorkset pool = new ImageWorkset(20_000); RunControl control = new RunControl();
            PngImage original = PngImage.fromBytes(bytes, pool); JsonObject data = metadata();
            String name = mode == 0 ? "observe" : mode == 1 ? "click" : "take_screenshot";
            if (mode == 2) data.addProperty("ok", false);
            if (mode == 3) data.addProperty("ok", "true");
            if (mode == 4) data.getAsJsonObject("data").addProperty("revision", "old-revision");
            if (mode == 5) data.getAsJsonObject("data").addProperty("width", 3);
            if (mode == 6) data.getAsJsonObject("data").add("height", JsonNull.INSTANCE);
            if (mode == 7) data.addProperty("imageDataUrl", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
            if (mode == 8) data.add("imageDataUrl", JsonNull.INSTANCE);
            if (mode == 9) original.close();
            if (mode == 10) data.remove("data");
            AtomicInteger writes = new AtomicInteger();
            try (PngImage safetyOwner = original; ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
                @Override public LocalToolResult execute(ToolRequest request, RunControl active) throws Exception {
                    if ("click".equals(request.tool)) {
                        try (RunControl.DispatchTicket ticket = active.beginDispatch(request.executionEpoch)) {
                            ticket.checkExecutionEpoch(); writes.incrementAndGet();
                        }
                    }
                    return LocalToolResult.withPng(data, original);
                }
            }); ToolDispatchResult result = dispatcher.dispatchWithAttachment(request(control, "invalid", name), control)) {
                assertEquals("mode=" + mode, mode == 1 ? "OUTCOME_UNKNOWN" : "INVALID_RESULT", result.getResult().get("code").getAsString());
                assertNull(result.takeImage()); assertTrue(original.isClosed()); assertEquals(0, pool.reservedBytes());
                assertFalse(result.getResult().toString().contains("imageDataUrl"));
                assertFalse(result.getResult().toString().contains(Base64.getEncoder().encodeToString(bytes)));
                assertEquals(mode == 1, dispatcher.hasUnknownOutcome());
                assertEquals(mode == 1 ? 1 : 0, writes.get());
            }
            assertEquals(0, pool.reservedBytes());
        }
    }

    @Test public void unavailableAndOldRequestsDoNotCreateAnyNativeResult() throws Exception {
        AtomicInteger executed = new AtomicInteger(); RunControl control = new RunControl();
        ToolRequest old = request(control, "old", "take_screenshot");
        try (ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
            @Override public Set<String> availableTools() { return ToolCatalog.basicTools(); }
            @Override public LocalToolResult execute(ToolRequest request, RunControl active) {
                executed.incrementAndGet(); throw new AssertionError("Unavailable adapter entered");
            }
        })) {
            assertEquals("CAPABILITY_UNAVAILABLE", dispatcher.dispatch(old, control).get("code").getAsString());
            control.pause(); control.resume();
            assertThrows(TaskControlException.class, () -> dispatcher.dispatch(old, control));
            assertEquals(0, executed.get());
        }
    }

    @Test public void pauseResumeOrStopAfterTheHandlerReturnsReleasesTheUnpublishedPng() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            ImageWorkset pool = new ImageWorkset(20_000); RunControl control = new RunControl();
            AtomicReference<PngImage> created = new AtomicReference<>();
            try (ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
                @Override public LocalToolResult execute(ToolRequest request, RunControl active) throws Exception {
                    PngImage png = PngImage.fromBytes(PngImageTest.png(), pool); created.set(png);
                    LocalToolResult result = LocalToolResult.withPng(metadata(), png);
                    if (cancel) active.cancel(); else { active.pause(); active.resume(); }
                    return result;
                }
            })) {
                ToolRequest request = request(control, "late-result", "take_screenshot");
                assertThrows(TaskControlException.class, () -> {
                    try (ToolDispatchResult unexpected = dispatcher.dispatchWithAttachment(request, control)) { fail("Old epoch returned a result"); }
                });
                assertNotNull(created.get()); assertTrue(created.get().isClosed()); assertEquals(0, pool.reservedBytes());
            } finally { if (created.get() != null) created.get().close(); }
        }
    }

    @Test public void metadataCopyErrorsAtConstructionCloseTheTransferredOwner() throws Exception {
        ImageWorkset pool = new ImageWorkset(20_000); AssertionError injected = new AssertionError("synthetic copy failure");
        JsonObject invalid = metadata(); invalid.add("copy_failure", new JsonElement() {
            @Override public JsonElement deepCopy() { throw injected; }
        });
        try (PngImage png = PngImage.fromBytes(PngImageTest.png(), pool)) {
            assertSame(injected, assertThrows(AssertionError.class, () -> LocalToolResult.withPng(invalid, png)));
            assertTrue(png.isClosed()); assertEquals(0, pool.reservedBytes());
        }
    }

    @Test public void aMetadataCopyErrorAfterHandlerReturnClosesTheStillLocalOwner() throws Exception {
        ImageWorkset pool = new ImageWorkset(20_000); RunControl control = new RunControl();
        AssertionError injected = new AssertionError("synthetic result-copy failure"); AtomicInteger copies = new AtomicInteger();
        JsonObject invalid = metadata(); invalid.add("copy_failure", new JsonElement() {
            @Override public JsonElement deepCopy() {
                if (copies.incrementAndGet() > 1) throw injected;
                return this;
            }
        });
        try (PngImage png = PngImage.fromBytes(PngImageTest.png(), pool);
             ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
                 @Override public LocalToolResult execute(ToolRequest request, RunControl active) {
                     return LocalToolResult.withPng(invalid, png);
                 }
             })) {
            assertSame(injected, assertThrows(AssertionError.class, () -> {
                try (ToolDispatchResult unexpected = dispatcher.dispatchWithAttachment(request(control, "copy-error", "take_screenshot"), control)) {
                    fail("Copy failure returned a result");
                }
            }));
            assertEquals(2, copies.get()); assertTrue(png.isClosed()); assertEquals(0, pool.reservedBytes());
        }
    }

    @Test public void legacyNullResultsKeepTheirInvalidResultClassification() throws Exception {
        RunControl control = new RunControl();
        try (ToolDispatcher dispatcher = new ToolDispatcher((name, arguments, active) -> null)) {
            JsonObject rejected = dispatcher.dispatch(request(control, "legacy-null", "observe"), control);
            assertEquals("INVALID_RESULT", rejected.get("code").getAsString());
        }
    }

    @Test public void errorsWhileValidatingReturnedMetadataCloseTheAlreadyTransferredOwner() throws Exception {
        ImageWorkset pool = new ImageWorkset(20_000); RunControl control = new RunControl();
        AssertionError injected = new AssertionError("synthetic width validation failure");
        JsonObject invalid = metadata();
        invalid.getAsJsonObject("data").addProperty("width", OwnedRequestPayloadTest.number(() -> { throw injected; }));
        try (PngImage png = PngImage.fromBytes(PngImageTest.png(), pool);
             ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
                 @Override public LocalToolResult execute(ToolRequest request, RunControl active) {
                     return LocalToolResult.withPng(invalid, png);
                 }
             })) {
            assertSame(injected, assertThrows(AssertionError.class, () -> {
                try (ToolDispatchResult unexpected = dispatcher.dispatchWithAttachment(request(control, "error", "take_screenshot"), control)) {
                    fail("Invalid dimension metadata returned an attachment");
                }
            }));
            assertTrue(png.isClosed()); assertEquals(0, pool.reservedBytes());
        }
    }

    @Test public void aHandlerThatFailsBeforeReturningStillClosesItsOwnLocalContainer() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            int failureMode = mode; ImageWorkset pool = new ImageWorkset(20_000); RunControl control = new RunControl();
            AtomicReference<PngImage> image = new AtomicReference<>();
            try (ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
                @Override public LocalToolResult execute(ToolRequest request, RunControl active) throws Exception {
                    PngImage png = PngImage.fromBytes(PngImageTest.png(), pool); image.set(png);
                    // No result has reached the dispatcher: the adapter retains ownership.
                    try (LocalToolResult owned = LocalToolResult.withPng(metadata(), png)) {
                        if (failureMode == 0) throw new IOException("private adapter detail");
                        if (failureMode == 1) throw new InterruptedException("private adapter detail");
                        throw new AssertionError("synthetic adapter error");
                    }
                }
            })) {
                ToolRequest request = request(control, "throwing", "take_screenshot");
                if (mode == 0) {
                    JsonObject result = dispatcher.dispatch(request, control);
                    assertEquals("DEVICE_ERROR", result.get("code").getAsString());
                    assertFalse(result.toString().contains("private adapter detail"));
                } else if (mode == 1) assertThrows(InterruptedException.class, () -> dispatcher.dispatch(request, control));
                else assertThrows(AssertionError.class, () -> dispatcher.dispatch(request, control));
                assertTrue(image.get().isClosed()); assertEquals(0, pool.reservedBytes());
            } finally { if (image.get() != null) image.get().close(); }
        }
    }

    @Test(timeout = 6000) public void concurrentDuplicateCannotCreateASecondImageOwner() throws Exception {
        ImageWorkset pool = new ImageWorkset(20_000); RunControl control = new RunControl();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); AtomicReference<PngImage> image = new AtomicReference<>();
        AtomicReference<ToolDispatchResult> delivered = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ToolDispatcher dispatcher = new ToolDispatcher(new NativeHandler() {
            @Override public LocalToolResult execute(ToolRequest request, RunControl active) throws Exception {
                calls.incrementAndGet(); PngImage png = PngImage.fromBytes(PngImageTest.png(), pool); image.set(png);
                LocalToolResult result = LocalToolResult.withPng(metadata(), png); boolean returned = false;
                try {
                    entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
                    returned = true; return result;
                } finally { if (!returned) result.close(); }
            }
        })) {
            ToolRequest request = request(control, "concurrent", "take_screenshot");
            Future<?> first = worker.submit(() -> {
                try { delivered.set(dispatcher.dispatchWithAttachment(request, control)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try (ToolDispatchResult duplicate = dispatcher.dispatchWithAttachment(request, control)) {
                assertEquals("IN_PROGRESS", duplicate.getResult().get("code").getAsString());
                assertNull(duplicate.takeImage()); assertEquals(1, calls.get());
            }
            release.countDown(); first.get(2, TimeUnit.SECONDS);
            try (ToolDispatchResult result = delivered.get()) {
                assertNotNull(result); assertTrue(result.getResult().get("ok").getAsBoolean());
            }
            assertTrue(image.get().isClosed()); assertEquals(0, pool.reservedBytes());
        } finally {
            release.countDown(); control.cancel(); worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            if (delivered.get() != null) delivered.get().close(); if (image.get() != null) image.get().close();
        }
    }
}
