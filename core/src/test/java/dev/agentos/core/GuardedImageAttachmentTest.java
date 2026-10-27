package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.StringWriter;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** A local condition travels with exactly one PNG; it is never JSON authority. */
public final class GuardedImageAttachmentTest {
    @Test public void originalPngAndGuardCrossDispatchOnceWithoutCopyingOrEnteringLedger() throws Exception {
        byte[] bytes = PngImageTest.png(); ImageWorkset pool = new ImageWorkset(20_000);
        RunControl run = new RunControl("guarded-image");
        ToolRequest request = LocalToolResultTest.request(run, "guarded-original", "take_screenshot");
        AtomicInteger copies = new AtomicInteger(), executions = new AtomicInteger(), checks = new AtomicInteger();
        DisclosureGuard guard = new DisclosureGuard() {
            @Override public boolean isCurrent(RunControl owner, long epoch) {
                assertSame(run, owner); assertEquals(request.executionEpoch, epoch); checks.incrementAndGet(); return true;
            }
            @Override public String toString() { throw new AssertionError("A guard must not become text"); }
        };
        try (PngImage png = PngImage.fromBytes(bytes, pool, source -> { copies.incrementAndGet(); return source.clone(); });
             ToolDispatcher dispatcher = new ToolDispatcher(new LocalToolResultTest.NativeHandler() {
                 @Override public LocalToolResult execute(ToolRequest original, RunControl owner) {
                     assertSame(request, original); executions.incrementAndGet();
                     return LocalToolResult.withPng(LocalToolResultTest.metadata(), png, guard);
                 }
             }); ToolDispatchResult result = dispatcher.dispatchWithAttachment(request, run);
             ImageAttachment attachment = result.takeImage()) {
            assertNotNull(attachment); assertSame(guard, attachment.imageGuard());
            assertEquals(bytes.length, pool.reservedBytes()); assertEquals(1, copies.get());
            assertEquals("Dispatch validation is not disclosure consent", 0, checks.get());
            String metadata = result.getResult().toString();
            assertFalse(metadata.contains("imageGuard")); assertFalse(metadata.contains("imageDataUrl"));
            new ModelRequestContext(run, request.executionEpoch, (owner, epoch) -> true, attachment.imageGuard()).check();
            assertEquals(1, checks.get());
            try (PngImage same = attachment.consumePng(run, request.executionEpoch, LocalToolResultTest.REVISION)) {
                assertSame(png, same); assertArrayEquals(bytes, PngImageTest.read(same));
                try (ToolDispatchResult duplicate = dispatcher.dispatchWithAttachment(request, run)) {
                    assertNull(duplicate.takeImage()); assertEquals(1, executions.get());
                    assertEquals("not_replayed", duplicate.getResult().getAsJsonObject("imageAttachment").get("delivery").getAsString());
                    assertEquals(1, checks.get());
                }
            }
        } finally { run.cancel(); }
        assertEquals(0, pool.reservedBytes());
    }

    @Test public void nullGuardAndMetadataCopyErrorStillConsumeTheFactoryPng() throws Exception {
        ImageWorkset pool = new ImageWorkset(20_000);
        try (PngImage png = PngImage.fromBytes(PngImageTest.png(), pool)) {
            assertThrows(NullPointerException.class, () -> LocalToolResult.withPng(LocalToolResultTest.metadata(), png, null));
            assertTrue(png.isClosed()); assertEquals(0, pool.reservedBytes());
        }
        AssertionError injected = new AssertionError("synthetic metadata failure");
        JsonObject metadata = LocalToolResultTest.metadata(); metadata.add("copy", new JsonElement() {
            @Override public JsonElement deepCopy() { throw injected; }
        });
        try (PngImage png = PngImage.fromBytes(PngImageTest.png(), pool)) {
            assertSame(injected, assertThrows(AssertionError.class,
                    () -> LocalToolResult.withPng(metadata, png, (owner, epoch) -> true)));
            assertTrue(png.isClosed()); assertEquals(0, pool.reservedBytes());
        }
    }

    @Test public void guardedMalformedAndOldEpochResultsReleaseWithoutEvaluatingTheCondition() throws Exception {
        for (boolean oldEpoch : new boolean[]{false, true}) {
            ImageWorkset pool = new ImageWorkset(20_000); RunControl run = new RunControl(); AtomicInteger checks = new AtomicInteger();
            try (PngImage png = PngImage.fromBytes(PngImageTest.png(), pool);
                 ToolDispatcher dispatcher = new ToolDispatcher(new LocalToolResultTest.NativeHandler() {
                     @Override public LocalToolResult execute(ToolRequest request, RunControl owner) {
                         JsonObject metadata = LocalToolResultTest.metadata();
                         if (!oldEpoch) metadata.getAsJsonObject("data").addProperty("width", 999);
                         LocalToolResult local = LocalToolResult.withPng(metadata, png,
                                 (original, epoch) -> { checks.incrementAndGet(); return true; });
                         if (oldEpoch) { owner.pause(); owner.resume(); }
                         return local;
                     }
                 })) {
                ToolRequest request = LocalToolResultTest.request(run, "rejected", "take_screenshot");
                if (oldEpoch) assertThrows(TaskControlException.class, () -> {
                    try (ToolDispatchResult unexpected = dispatcher.dispatchWithAttachment(request, run)) { fail("Old epoch accepted"); }
                });
                else try (ToolDispatchResult result = dispatcher.dispatchWithAttachment(request, run)) {
                    assertEquals("INVALID_RESULT", result.getResult().get("code").getAsString()); assertNull(result.takeImage());
                }
                assertTrue(png.isClosed()); assertEquals(0, pool.reservedBytes()); assertEquals(0, checks.get());
            } finally { run.cancel(); }
        }
    }

    @Test public void configurationFailurePrecedesImageAndInvalidImageRunsHookOutsideControlMonitor() throws Exception {
        RunControl configurationStopped = new RunControl(); AtomicInteger imageCalls = new AtomicInteger();
        try {
            ModelRequestContext context = new ModelRequestContext(configurationStopped, configurationStopped.getExecutionEpoch(),
                    (owner, epoch) -> false, (owner, epoch) -> { imageCalls.incrementAndGet(); return true; });
            assertTrue(assertThrows(TaskControlException.class, context::check).getMessage().contains("配置已改变"));
            assertEquals(0, imageCalls.get());
        } finally { configurationStopped.cancel(); }
        for (boolean throwing : new boolean[]{false, true}) {
            RunControl run = new RunControl(); AtomicInteger hooks = new AtomicInteger();
            ExecutorService observer = Executors.newSingleThreadExecutor();
            run.setCancelHook(() -> {
                assertFalse(Thread.holdsLock(run)); hooks.incrementAndGet();
                try { assertTrue(observer.submit(run::isCancelled).get(2, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            try {
                ModelRequestContext context = new ModelRequestContext(run, run.getExecutionEpoch(), (owner, epoch) -> true,
                        (owner, epoch) -> { if (throwing) throw new IllegalStateException("private synthetic detail"); return false; });
                TaskControlException stopped = assertThrows(TaskControlException.class, context::check);
                assertEquals("TASK_STOPPED", stopped.getCode()); assertTrue(stopped.getMessage().contains("图像发送条件"));
                assertFalse(stopped.getMessage().contains("private synthetic detail")); assertEquals(1, hooks.get());
                assertThrows(TaskControlException.class, context::check); assertEquals(1, hooks.get());
            } finally { run.cancel(); observer.shutdownNow(); assertTrue(observer.awaitTermination(3, TimeUnit.SECONDS)); }
        }
    }

    @Test public void delayedOldImageConditionCannotCancelAResumedExecution() throws Exception {
        for (boolean throwing : new boolean[]{false, true}) {
            RunControl run = new RunControl(); long originalEpoch = run.getExecutionEpoch();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            ExecutorService worker = Executors.newSingleThreadExecutor(); AtomicInteger newHook = new AtomicInteger();
            try {
                ModelRequestContext context = new ModelRequestContext(run, originalEpoch, (owner, epoch) -> true,
                        (owner, epoch) -> {
                            assertSame(run, owner); assertEquals(originalEpoch, epoch); entered.countDown();
                            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                            if (throwing) throw new IllegalStateException("old private image detail"); return false;
                        });
                Future<TaskControlException> result = worker.submit(() -> assertThrows(TaskControlException.class, context::check));
                assertTrue(entered.await(2, TimeUnit.SECONDS)); run.pause(); run.resume();
                long resumed = run.getExecutionEpoch(); run.setCancelHook(newHook::incrementAndGet); release.countDown();
                assertEquals("TASK_PAUSED", result.get(2, TimeUnit.SECONDS).getCode());
                assertFalse(run.isCancelled()); assertEquals(resumed, run.getExecutionEpoch()); assertEquals(0, newHook.get());
            } finally { release.countDown(); run.cancel(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
        }
    }

    @Test public void guardErrorBeforeRequestEncodingStillClosesTheTransferredPng() throws Exception {
        ImageWorkset pool = new ImageWorkset(20_000); RunControl run = new RunControl();
        AssertionError injected = new AssertionError("synthetic local guard failure");
        try (PngImage png = PngImage.fromBytes(PngImageTest.png(), pool)) {
            ModelRequestContext context = new ModelRequestContext(run, run.getExecutionEpoch(),
                    (owner, epoch) -> true, (owner, epoch) -> { throw injected; });
            assertSame(injected, assertThrows(AssertionError.class, () -> new HttpModelClient().request(
                    new ModelSettings("http://127.0.0.1:9/v1", "synthetic", "test"),
                    new JsonArray(), new JsonArray(), context, png)));
            assertTrue(png.isClosed()); assertEquals(0, pool.reservedBytes());
            assertFalse("Errors are not ordinary false authorization results", run.isCancelled());
        } finally { run.cancel(); }
    }

    @Test public void revocationWithinBase64EncodingDropsScratchAndStopsBeforeAnotherImageBlock() throws Exception {
        int width = 128, height = 40, row = width * 3 + 1;
        byte[] pixels = new byte[row * height]; new Random(4217).nextBytes(pixels);
        for (int y = 0; y < height; y++) pixels[y * row] = 0;
        byte[] png = PngImageTest.png(width, height, pixels); assertTrue(png.length > 6144);
        ImageWorkset pool = new ImageWorkset(100_000); RunControl run = new RunControl();
        AtomicBoolean permitted = new AtomicBoolean(true); AtomicInteger blocks = new AtomicInteger();
        ModelRequestContext context = new ModelRequestContext(run, run.getExecutionEpoch(),
                (owner, epoch) -> true, (owner, epoch) -> permitted.get());
        JsonObject body = new JsonObject(); body.add("messages", new JsonArray());
        try (PngImage image = PngImage.fromBytes(png, pool)) {
            try (ImageRequestSerializer serializer = new ImageRequestSerializer(image, pool)) {
                StringWriter revokeAfterBlock = new StringWriter() {
                    @Override public void write(char[] chars, int offset, int length) {
                        super.write(chars, offset, length);
                        if (length == 4096) { blocks.incrementAndGet(); permitted.set(false); }
                    }
                };
                assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                        () -> serializer.write(body, revokeAfterBlock, context)).getCode());
                assertEquals(1, blocks.get());
            }
            assertEquals(png.length, pool.reservedBytes()); assertArrayEquals(png, PngImageTest.read(image));
        } finally { run.cancel(); }
        assertEquals(0, pool.reservedBytes());
    }

    @Test public void budgetOrInterruptDuringImageCheckKeepTheirOriginalFailureSemantics() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            AtomicLong clock = new AtomicLong(); RunControl run = new RunControl("image-test", new TaskBudget(1, 1, 1000), clock::get);
            long epoch = run.getExecutionEpoch(); AtomicInteger hooks = new AtomicInteger(); run.setCancelHook(hooks::incrementAndGet);
            try {
                ModelRequestContext context = new ModelRequestContext(run, epoch, (owner, original) -> true,
                        (owner, original) -> {
                            if (interrupt) Thread.currentThread().interrupt(); else clock.set(TimeUnit.MILLISECONDS.toNanos(1000));
                            return false;
                        });
                assertEquals(interrupt ? "TASK_STOPPED" : "BUDGET_EXCEEDED",
                        assertThrows(TaskControlException.class, context::check).getCode());
                assertFalse(run.isCancelled()); assertEquals(epoch, run.getExecutionEpoch()); assertEquals(0, hooks.get());
            } finally { Thread.interrupted(); run.cancel(); }
        }
    }
}
