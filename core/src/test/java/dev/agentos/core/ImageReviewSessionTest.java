package dev.agentos.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.agentos.core.ImageReviewSession.*;

public final class ImageReviewSessionTest {
    private static final String SIGNER = "a".repeat(64);

    private static final class Review implements AutoCloseable {
        final RunControl run = new RunControl("user:0");
        final AtomicLong clock = new AtomicLong(1000);
        final Binding binding = binding(run);
        final AtomicReference<Binding> current = new AtomicReference<>(binding);
        final PngImage image;
        final ImageReviewSession session;
        Review() throws Exception { this(ImageWorkset.process()); }
        Review(ImageWorkset workset) throws Exception {
            image = PngImage.fromBytes(PngImageTest.png(), workset);
            session = new ImageReviewSession(binding, image, run, current::get, clock::get);
        }
        HostToken present() throws IOException {
            HostToken host = session.claimHost(); assertNotNull(host);
            try (PngImage.ReadLease stream = session.openPreviewRead(host)) {
                assertNotNull(stream); assertEquals(137, stream.read());
            }
            assertTrue(session.markPresented(host)); return host;
        }
        @Override public void close() { session.close(); }
    }

    private static Binding binding(RunControl control) {
        return new Binding(new CoordinateNodeResolver.Binding(control.getTaskId(), control.getExecutionEpoch(),
                control.getProfileId(), "test.application", 0, 12, "revision-1",
                new CoordinateNodeResolver.Bounds(10, 20, 210, 420)), "call-1", "capture-1",
                new AppIdentity(10001, 1000, Collections.singleton(SIGNER)),
                new Recipient("https://model.example/v1", "vision-test"), "configuration-1");
    }
    private static Binding replace(Binding value, CoordinateNodeResolver.Binding observation,
                                   String call, String capture, AppIdentity app, Recipient recipient, String config) {
        return new Binding(observation, call, capture, app, recipient, config);
    }
    private static Binding observation(Binding value, String task, long epoch, String profile,
                                       String application, int window, String revision, CoordinateNodeResolver.Bounds bounds) {
        return replace(value, new CoordinateNodeResolver.Binding(task, epoch, profile, application, 0, window,
                revision, bounds), value.callId, value.captureId, value.applicationIdentity, value.recipient,
                value.configurationToken);
    }
    private static List<Binding> changedBindings(Binding value) {
        CoordinateNodeResolver.Binding o = value.observation;
        List<Binding> changes = new ArrayList<>();
        changes.add(observation(value, "another-task", o.executionEpoch, o.profileId, o.packageName, o.windowId, o.revision, o.windowBounds));
        changes.add(observation(value, o.taskId, o.executionEpoch + 1, o.profileId, o.packageName, o.windowId, o.revision, o.windowBounds));
        changes.add(observation(value, o.taskId, o.executionEpoch, "user:1", o.packageName, o.windowId, o.revision, o.windowBounds));
        changes.add(observation(value, o.taskId, o.executionEpoch, o.profileId, "another.application", o.windowId, o.revision, o.windowBounds));
        changes.add(observation(value, o.taskId, o.executionEpoch, o.profileId, o.packageName, o.windowId + 1, o.revision, o.windowBounds));
        changes.add(observation(value, o.taskId, o.executionEpoch, o.profileId, o.packageName, o.windowId, "revision-2", o.windowBounds));
        for (CoordinateNodeResolver.Bounds bounds : new CoordinateNodeResolver.Bounds[]{
                new CoordinateNodeResolver.Bounds(11, 20, 210, 420), new CoordinateNodeResolver.Bounds(10, 21, 210, 420),
                new CoordinateNodeResolver.Bounds(10, 20, 211, 420), new CoordinateNodeResolver.Bounds(10, 20, 210, 421)}) {
            changes.add(observation(value, o.taskId, o.executionEpoch, o.profileId, o.packageName, o.windowId, o.revision, bounds));
        }
        changes.add(replace(value, o, "call-2", value.captureId, value.applicationIdentity, value.recipient, value.configurationToken));
        changes.add(replace(value, o, value.callId, "capture-2", value.applicationIdentity, value.recipient, value.configurationToken));
        changes.add(replace(value, o, value.callId, value.captureId, new AppIdentity(10002, 1000, Collections.singleton(SIGNER)), value.recipient, value.configurationToken));
        changes.add(replace(value, o, value.callId, value.captureId, new AppIdentity(10001, 1001, Collections.singleton(SIGNER)), value.recipient, value.configurationToken));
        changes.add(replace(value, o, value.callId, value.captureId, new AppIdentity(10001, 1000, Collections.singleton("b".repeat(64))), value.recipient, value.configurationToken));
        changes.add(replace(value, o, value.callId, value.captureId, value.applicationIdentity, new Recipient("https://other.example/v1", value.recipient.model), value.configurationToken));
        changes.add(replace(value, o, value.callId, value.captureId, value.applicationIdentity, new Recipient(value.recipient.endpoint, "other-model"), value.configurationToken));
        changes.add(replace(value, o, value.callId, value.captureId, value.applicationIdentity, value.recipient, "configuration-2"));
        return changes;
    }

    @Test public void onlyPresentedHostCanApproveAndConsumeExactFinalPngOnce() throws Exception {
        try (Review review = new Review()) {
            HostToken host = review.session.claimHost();
            assertNull(review.session.approve(host));
            assertFalse(review.session.markPresented(host));
            try (PngImage.ReadLease preview = review.session.openPreviewRead(host)) {
                assertNotNull(preview); assertArrayEquals(PngImageTest.png(), preview.readAllBytes());
            }
            assertNull(review.session.openPreviewRead(host));
            assertTrue(review.session.markPresented(host));
            ApprovalToken approved = review.session.approve(host); assertNotNull(approved);
            assertNull(review.session.approve(host));
            PngImage consumed = review.session.consume(approved); assertSame(review.image, consumed);
            assertEquals(State.CONSUMED, review.session.state());
            assertNull(review.session.consume(approved));
            review.session.close(); assertFalse(consumed.isClosed());
            try (PngImage owned = consumed) { assertArrayEquals(PngImageTest.png(), PngImageTest.read(owned)); }
        }
    }

    @Test public void everyMutableBindingFieldInvalidatesBeforeApproval() throws Exception {
        try (Review template = new Review()) {
            for (int index = 0; index < changedBindings(template.binding).size(); index++) {
                try (Review review = new Review()) {
                    HostToken host = review.present();
                    review.current.set(changedBindings(review.binding).get(index));
                    assertNull("changed field " + index, review.session.approve(host));
                    assertEquals(State.INVALIDATED, review.session.state()); assertTrue(review.image.isClosed());
                }
            }
        }
    }

    @Test public void everyMutableBindingFieldIsRecheckedAtConsumption() throws Exception {
        try (Review template = new Review()) {
            for (int index = 0; index < changedBindings(template.binding).size(); index++) {
                try (Review review = new Review()) {
                    ApprovalToken approval = review.session.approve(review.present()); assertNotNull(approval);
                    review.current.set(changedBindings(review.binding).get(index));
                    assertNull("changed field " + index, review.session.consume(approval));
                    assertTrue(review.image.isClosed());
                }
            }
        }
    }

    @Test public void unsupportedDisplayCannotEnterTheReviewBinding() {
        RunControl run = new RunControl("user:0");
        assertThrows(IllegalArgumentException.class, () -> new CoordinateNodeResolver.Binding(run.getTaskId(),
                run.getExecutionEpoch(), run.getProfileId(), "test.application", 1, 12, "r1",
                new CoordinateNodeResolver.Bounds(0, 0, 100, 100)));
    }

    @Test public void sixtySecondDeadlineIncludesPreparationAndDoesNotRestartAtApproval() throws Exception {
        try (Review review = new Review()) {
            HostToken host = review.present(); review.clock.addAndGet(TIMEOUT_MILLIS - 1);
            assertEquals(1, review.session.remainingMillis());
            ApprovalToken approval = review.session.approve(host); assertNotNull(approval);
            review.clock.incrementAndGet(); review.session.expire();
            assertEquals(State.EXPIRED, review.session.state()); assertTrue(review.image.isClosed());
            assertNull(review.session.consume(approval)); assertEquals(0, review.session.remainingMillis());
        }
        try (Review review = new Review()) {
            review.clock.addAndGet(TIMEOUT_MILLIS); review.session.expire();
            assertNull(review.session.claimHost()); assertTrue(review.image.isClosed());
        }
    }

    @Test public void pauseResumeAndStopNeverReviveAnOldReviewOrReplaceTheCancelHook() throws Exception {
        try (Review review = new Review()) {
            AtomicInteger cancelCalls = new AtomicInteger(); review.run.setCancelHook(cancelCalls::incrementAndGet);
            ApprovalToken approval = review.session.approve(review.present());
            review.run.pause(); review.run.resume();
            assertNull(review.session.consume(approval)); assertEquals(1, cancelCalls.get());
            assertTrue(review.image.isClosed());
        }
        try (Review review = new Review()) {
            HostToken host = review.present(); review.run.cancel();
            assertNull(review.session.approve(host)); assertTrue(review.image.isClosed());
        }
    }

    @Test public void unrelatedAndStaleHostCallbacksCannotPresentApproveOrInvalidateCurrentReview() throws Exception {
        try (Review first = new Review(); Review second = new Review()) {
            HostToken old = first.present(), current = second.present(); first.session.close();
            assertNull(second.session.openPreviewRead(old)); assertFalse(second.session.markPresented(old));
            assertNull(second.session.approve(old)); second.session.invalidateHost(old); second.session.reject(old);
            assertEquals(State.PRESENTED, second.session.state());
            assertNull(second.session.claimHost());
            assertNotNull(second.session.approve(current));
        }
    }

    @Test public void approvalFromAnotherReviewCannotConsumeOrReplaceThisOne() throws Exception {
        try (Review first = new Review(); Review second = new Review()) {
            ApprovalToken firstApproval = first.session.approve(first.present());
            ApprovalToken secondApproval = second.session.approve(second.present());
            assertNull(second.session.consume(firstApproval));
            assertEquals(State.APPROVED, second.session.state());
            PngImage consumed = second.session.consume(secondApproval); assertNotNull(consumed); consumed.close();
        }
    }

    @Test public void rejectionAndHostLossClearBytesAndRejectLateDecodeOrApproval() throws Exception {
        try (Review review = new Review()) {
            HostToken host = review.session.claimHost(); review.session.reject(host);
            assertEquals(State.REJECTED, review.session.state()); assertTrue(review.image.isClosed());
            assertNull(review.session.openPreviewRead(host)); assertFalse(review.session.markPresented(host));
            assertNull(review.session.approve(host)); review.session.close();
        }
        try (Review review = new Review()) {
            HostToken host = review.present(); ApprovalToken approval = review.session.approve(host);
            review.session.invalidateHost(host); review.session.invalidateHost(host);
            assertEquals(State.INVALIDATED, review.session.state()); assertTrue(review.image.isClosed());
            assertNull(review.session.consume(approval));
        }
    }

    @Test public void lossOfTrustedContextOrClockCannotKeepAnApprovalAlive() throws Exception {
        try (Review review = new Review()) {
            review.present(); review.current.set(null);
            assertEquals(State.INVALIDATED, review.session.state()); assertTrue(review.image.isClosed());
        }
        try (Review review = new Review()) {
            review.present(); review.clock.decrementAndGet();
            assertEquals(State.INVALIDATED, review.session.state()); assertEquals(Reason.CLOCK_CHANGED, review.session.reason());
            assertTrue(review.image.isClosed());
        }
    }

    @Test public void constructionRejectsAControlMismatchAndClosesOnMissingDependencies() throws Exception {
        RunControl expected = new RunControl("user:0"), other = new RunControl("user:0");
        Binding binding = binding(expected);
        PngImage stale = PngImage.fromBytes(PngImageTest.png());
        try (ImageReviewSession session = new ImageReviewSession(binding, stale, other, () -> binding)) {
            assertEquals(State.INVALIDATED, session.state()); assertTrue(stale.isClosed());
        }
        PngImage failed = PngImage.fromBytes(PngImageTest.png());
        assertThrows(NullPointerException.class, () -> new ImageReviewSession(binding, failed, expected, null));
        assertTrue(failed.isClosed());
    }

    @Test public void recipientHasNoCredentialInputsAndUsesActualCompletionUrl() {
        Recipient value = new Recipient("https://model.example/v1/", " vision-test ");
        assertEquals("https://model.example/v1/chat/completions", value.endpoint);
        assertEquals("vision-test", value.model);
        assertThrows(IllegalArgumentException.class, () -> new Recipient("https://name:secret@model.example/v1", "vision-test"));
        assertThrows(IllegalArgumentException.class, () -> new Recipient("https://model.example/v1?key=secret", "vision-test"));
        assertThrows(IllegalArgumentException.class, () -> new Recipient("https://model.example/v1", "bad\nmodel"));
    }

    @Test public void invalidatedReviewDoesNotReclaimBytesStillHeldByItsDecoder() throws Exception {
        byte[] png = PngImageTest.png();
        for (int mode = 0; mode < 4; mode++) {
            ImageWorkset workset = new ImageWorkset(png.length + PngValidator.SCRATCH_BYTES);
            try (Review review = new Review(workset)) {
                HostToken host = review.session.claimHost();
                try (PngImage.ReadLease preview = review.session.openPreviewRead(host)) {
                    assertNotNull(preview); assertEquals(png.length, workset.reservedBytes());
                    if (mode == 0) { review.run.pause(); review.run.resume(); review.session.state(); }
                    else if (mode == 1) { review.clock.addAndGet(TIMEOUT_MILLIS); review.session.expire(); }
                    else if (mode == 2) review.session.invalidateHost(host);
                    else review.session.reject(host);
                    assertTrue(review.image.isClosed());
                    assertFalse(review.session.markPresented(host)); assertNull(review.session.approve(host));
                    assertNull(review.session.openPreviewRead(host));
                    assertEquals(png.length, workset.reservedBytes());
                    assertArrayEquals(png, preview.readAllBytes());
                    assertEquals("invalidity and EOF do not close the decoder's lease", png.length, workset.reservedBytes());
                }
                assertEquals(0, workset.reservedBytes());
            }
        }
    }

    @Test public void approvedConsumptionTransfersOneOwnerWithoutCopyOrAdditionalReservation() throws Exception {
        byte[] png = PngImageTest.png(); ImageWorkset workset = new ImageWorkset(png.length + PngValidator.SCRATCH_BYTES);
        try (Review review = new Review(workset)) {
            HostToken host = review.present(); assertEquals(png.length, workset.reservedBytes());
            ApprovalToken approval = review.session.approve(host);
            try (PngImage consumed = review.session.consume(approval)) {
                assertSame(review.image, consumed); assertEquals(png.length, workset.reservedBytes());
                review.session.close(); assertEquals(png.length, workset.reservedBytes());
                try (PngImage.ReadLease stream = consumed.openRead()) {
                    assertArrayEquals(png, stream.readAllBytes());
                    assertEquals(png.length, workset.reservedBytes());
                }
            }
            assertEquals(0, workset.reservedBytes());
        }
    }

    @Test public void constructionErrorReleasesTransferredImageAndApprovalWait() throws Exception {
        byte[] png = PngImageTest.png(); ImageWorkset workset = new ImageWorkset(png.length + PngValidator.SCRATCH_BYTES);
        AtomicLong taskClock = new AtomicLong();
        RunControl run = new RunControl("user:0", TaskBudget.DEFAULT, taskClock::get); Binding binding = binding(run);
        PngImage image = PngImage.fromBytes(png, workset); AtomicInteger checks = new AtomicInteger();
        AssertionError injected = new AssertionError("synthetic context supplier error");
        assertSame(injected, assertThrows(AssertionError.class, () -> new ImageReviewSession(binding, image, run, () -> {
            if (checks.incrementAndGet() == 2) { taskClock.set(10_000_000L); throw injected; }
            return binding;
        })));
        assertEquals(2, checks.get()); assertTrue(image.isClosed()); assertEquals(0, workset.reservedBytes());
        taskClock.set(20_000_000L);
        assertEquals(10, run.budgetSnapshot().approvalMillis);
        assertEquals("constructor failure must not leave the task waiting for approval", 10, run.budgetSnapshot().activeMillis);
    }
}
