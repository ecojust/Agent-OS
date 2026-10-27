package dev.agentos.app;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Dedicated pinned-APK workflow only: actual service/page predicate, never a capture permission. */
@RunWith(AndroidJUnit4.class)
public final class PinnedCalculatorVisualPageInstrumentedTest {
    @Rule public final TestName name = new TestName();
    @Rule public final ScreenshotEvidence screenshots = new ScreenshotEvidence();

    @Test public void ordinaryZeroAndComputedResultUseTheActualProductionSnapshot() throws Exception {
        run(env -> {
            env.resetNormally();
            String zeroRevision = env.stableRevision();
            SupportedVisualPagePolicy.Match zero = env.accept("basic-zero", zeroRevision);
            env.calculateFive();
            String resultRevision = env.stableRevision();
            assertNotEquals("The arithmetic result must change the actual observation", zeroRevision, resultRevision);
            SupportedVisualPagePolicy.Match result = env.accept("basic-result", resultRevision);
            assertEquals(SupportedVisualPagePolicy.POLICY_ID, zero.policyId);
            assertEquals(zero.policyId, result.policyId);
        });
    }

    @Test public void actualOverflowMenuIsNotTheUnopenedToolbar() throws Exception {
        run(env -> {
            env.resetNormally();
            env.accept("before-overflow", env.stableRevision());
            env.openOverflow();
            env.awaitTree("actual overflow popup", tree -> tree.hasVisibleText("About"));
            env.refuse("overflow", env.stableRevision());
        });
    }

    @Test public void nonemptyHistoryIsRejectedAndReturningNeedsANewObservation() throws Exception {
        run(env -> {
            env.resetNormally(); env.calculateFive();
            String oldRevision = env.stableRevision();
            env.accept("before-history", oldRevision);
            env.openNonemptyHistory();
            String historyRevision = env.stableRevision();
            assertNotEquals(oldRevision, historyRevision);
            env.refuse("history", historyRevision);
            env.back();
            env.awaitTree("calculator after history dismissal", tree -> tree.id("btn_equals", false) != null
                    && tree.id("history_list", false) == null);
            env.accept("returned-from-history-new-observation", env.stableRevision());
        });
    }

    @Test public void actualSettingsPageIsRejected() throws Exception {
        run(env -> {
            env.resetNormally();
            env.accept("before-settings", env.stableRevision());
            if (!env.clickIdIfPresent("settings")) {
                env.openOverflow();
                env.awaitTree("settings item in actual popup", tree -> tree.hasVisibleText("Settings"));
                env.clickText("Settings");
            }
            env.awaitTree("actual settings page", tree -> tree.hasVisibleText("Settings")
                    && tree.id("btn_equals", false) == null);
            env.requireFocusedActivity("SettingsActivity");
            env.refuse("settings", env.stableRevision());
        });
    }

    @Test public void actualUnitConverterPageIsRejected() throws Exception {
        run(env -> {
            env.resetNormally();
            env.accept("before-converter", env.stableRevision());
            env.clickId("unit_converter", AccessibilityNodeInfo.ACTION_CLICK);
            env.awaitTree("actual converter picker page", tree -> tree.hasVisibleUnitCategoryInGrid()
                    && tree.id("btn_equals", false) == null);
            env.requireFocusedActivity("UnitConverterPickerActivity");
            env.refuse("converter", env.stableRevision());
        });
    }

    private void run(CheckedCase test) throws Exception {
        try (PinnedCalculatorPageHarness env = new PinnedCalculatorPageHarness(name.getMethodName())) {
            try { test.run(env); env.assertNoVisualCapability(); env.finish(true); }
            catch (Exception | Error failure) {
                try { env.failureEvidence(failure); } catch (Exception | Error diagnostic) { failure.addSuppressed(diagnostic); }
                throw failure;
            }
        }
    }
    @FunctionalInterface private interface CheckedCase { void run(PinnedCalculatorPageHarness env) throws Exception; }
}
