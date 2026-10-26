package dev.agentos.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Numeric recorder contract only; this test does not claim visual or performance acceptance. */
@RunWith(AndroidJUnit4.class)
public final class PerformanceTraceInstrumentedTest {
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence("numeric_diagnostic_contract_no_visual_claim");

    @Test public void diagnosticsAreExplicitBoundedAndDoNotMixSessions() {
        PerformanceTrace.Span disabled = PerformanceTrace.begin(PerformanceTrace.Stage.MAIN_CALLBACK);
        PerformanceTrace.Span late;
        try (PerformanceTrace.Session session = PerformanceTrace.startDiagnostics()) {
            disabled.finish();
            assertTrue(session.events().isEmpty());
            for (int index = 0; index < PerformanceTrace.CAPACITY + 7; index++) {
                PerformanceTrace.mark(PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED, index, index);
            }
            assertEquals(PerformanceTrace.CAPACITY, session.events().size());
            assertEquals(7, session.overwrittenCount());
            assertEquals(7, session.events().get(0).epoch);
            PerformanceTrace.Event last = session.events().get(PerformanceTrace.CAPACITY - 1);
            assertEquals(PerformanceTrace.Stage.STOP_EPOCH_INVALIDATED, last.stage);
            assertEquals(PerformanceTrace.CAPACITY + 6, last.dispatchAttempts);
            assertTrue(last.durationNanos >= 0);
            assertEquals(-1, last.nodeCount);
            late = PerformanceTrace.begin(PerformanceTrace.Stage.SNAPSHOT_OBSERVE);
            assertThrows(IllegalStateException.class, PerformanceTrace::startDiagnostics);
        }
        try (PerformanceTrace.Session next = PerformanceTrace.startDiagnostics()) {
            late.finish();
            assertTrue(next.events().isEmpty());
            PerformanceTrace.begin(PerformanceTrace.Stage.SNAPSHOT_OBSERVE).finishSnapshot(3, 1200, true, 9);
            PerformanceTrace.Event sample = next.events().get(0);
            assertEquals(3, sample.nodeCount);
            assertEquals(1200, sample.snapshotJsonBytes);
            assertTrue(sample.truncated);
            assertEquals(9, sample.epoch);
        }
    }
}
