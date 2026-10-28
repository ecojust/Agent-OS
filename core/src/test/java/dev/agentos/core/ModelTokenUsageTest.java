package dev.agentos.core;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.agentos.core.ModelTokenUsage.*;

public class ModelTokenUsageTest {
    private static Report usage(String fields) {
        return ModelTokenUsage.parseReport("{\"usage\":{" + fields + "}}");
    }

    @Test public void completeAndPartialReportsPreserveRealZeroWithoutDerivingMissingFields() {
        Report zero = usage("\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0");
        assertEquals(Long.valueOf(0), zero.prompt);
        assertEquals(Long.valueOf(0), zero.completion);
        assertEquals(Long.valueOf(0), zero.total);
        Report partial = usage("\"prompt_tokens\":2,\"completion_tokens\":3");
        assertEquals(Long.valueOf(2), partial.prompt);
        assertEquals(Long.valueOf(3), partial.completion);
        assertNull("A total must be reported, never synthesized", partial.total);
        Report total = usage("\"total_tokens\":9223372036854775807,\"details\":{\"private-marker\":[1,true,null]}");
        assertNull(total.prompt); assertNull(total.completion);
        assertEquals(Long.valueOf(Long.MAX_VALUE), total.total);
    }

    @Test public void malformedFieldsDoNotDiscardIndependentValidFieldsOrCoerceNumericValues() {
        for (String bad : new String[]{"\"1\"", "-1", "-0", "1.0", "1e1", "0e0", "1e999999",
                "true", "null", "{}", "[]", "9223372036854775808"}) {
            Report report = usage("\"prompt_tokens\":" + bad + ",\"completion_tokens\":7");
            assertNull("Rejected token representation: " + bad, report.prompt);
            assertEquals(Long.valueOf(7), report.completion);
            assertNull(report.total);
        }
    }

    @Test public void duplicateKnownKeysAndContradictoryTotalsInvalidateTheEntireReport() {
        String escapedUsage = "us" + '\\' + "u0061ge";
        String escapedPrompt = "prompt_" + '\\' + "u0074okens";
        for (String raw : new String[]{
                "{\"usage\":{\"prompt_tokens\":1},\"usage\":{\"total_tokens\":2}}",
                "{\"usage\":{\"prompt_tokens\":1},\"" + escapedUsage + "\":{\"total_tokens\":2}}",
                "{\"usage\":null,\"usage\":{\"total_tokens\":2}}",
                "{\"usage\":{\"prompt_tokens\":1,\"prompt_tokens\":1,\"total_tokens\":1}}",
                "{\"usage\":{\"prompt_tokens\":1,\"" + escapedPrompt + "\":1,\"total_tokens\":1}}",
                "{\"usage\":{\"completion_tokens\":1,\"completion_tokens\":null}}",
                "{\"usage\":{\"total_tokens\":1,\"total_tokens\":1}}",
                "{\"usage\":{\"prompt_tokens\":3,\"total_tokens\":2}}",
                "{\"usage\":{\"completion_tokens\":3,\"total_tokens\":2}}",
                "{\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":4}}",
                "{\"usage\":{\"prompt_tokens\":9223372036854775807,\"completion_tokens\":1,\"total_tokens\":9223372036854775807}}"}) {
            assertSame(Report.UNKNOWN, ModelTokenUsage.parseReport(raw));
        }
        Report extras = usage("\"details\":1,\"details\":2,\"total_tokens\":4");
        assertEquals("Unknown fields carry no counting authority", Long.valueOf(4), extras.total);
        Report nested = ModelTokenUsage.parseReport("{\"choices\":[{\"usage\":{\"prompt_tokens\":99}}],"
                + "\"usage\":{\"completion_tokens\":3,\"details\":{\"usage\":{\"total_tokens\":100}}}}");
        assertNull(nested.prompt); assertNull(nested.total);
        assertEquals(Long.valueOf(3), nested.completion);
    }

    @Test public void absentMalformedAndOversizedMetadataRemainUnknownWithoutRetainingContent() {
        for (String raw : new String[]{null, "", "not-json", "[]", "{}", "{\"usage\":{}}",
                "{\"usage\":null}", "{\"usage\":[]}", "{\"usage\":true}", "{\"usage\":\"private-marker\"}",
                "{\"usage\":{\"total_tokens\":1}} trailing", "{\"usage\":{\"total_tokens\":01}}",
                "{\"usage\":{\"total_tokens\":1}", "{\"ignored\":\"" + "x".repeat(HttpModelClient.MAX_RESPONSE_BYTES) + "\"}"}) {
            assertSame(Report.UNKNOWN, ModelTokenUsage.parseReport(raw));
        }
        Accumulator accumulator = new Accumulator();
        accumulator.beginCall();
        accumulator.accept(ModelTokenUsage.parseReport("{\"choices\":[{\"content\":\"private-marker\"}],\"usage\":{\"total_tokens\":2}}"));
        assertFalse(new Gson().toJson(accumulator.snapshot()).contains("private-marker"));
    }

    @Test public void snapshotsKeepCoverageAndMissingCallsSeparateFromReportedZero() {
        Accumulator accumulator = new Accumulator();
        ModelTokenUsage empty = accumulator.snapshot();
        assertEquals(0, empty.calls); assertEquals(Completeness.UNKNOWN, empty.completeness);
        assertNull(empty.prompt.subtotal); assertEquals(0, empty.prompt.reportedCalls);
        accumulator.beginCall();
        accumulator.accept(usage("\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0"));
        ModelTokenUsage complete = accumulator.snapshot();
        assertEquals(Completeness.COMPLETE, complete.completeness);
        accumulator.beginCall(); accumulator.accept(Report.UNKNOWN);
        accumulator.beginCall(); accumulator.accept(null);
        accumulator.beginCall(); accumulator.accept(usage("\"prompt_tokens\":5,\"total_tokens\":12"));
        ModelTokenUsage partial = accumulator.snapshot();
        assertEquals(4, partial.calls); assertEquals(Completeness.PARTIAL, partial.completeness);
        assertEquals(Long.valueOf(5), partial.prompt.subtotal); assertEquals(2, partial.prompt.reportedCalls);
        assertEquals(Long.valueOf(0), partial.completion.subtotal); assertEquals(1, partial.completion.reportedCalls);
        assertEquals(Long.valueOf(12), partial.total.subtotal); assertEquals(2, partial.total.reportedCalls);
        assertEquals(1, complete.calls); assertEquals(Completeness.COMPLETE, complete.completeness);
        assertEquals(Long.valueOf(0), complete.prompt.subtotal); assertEquals(1, complete.prompt.reportedCalls);
        assertEquals(0, empty.calls); assertNull(empty.total.subtotal);
    }

    @Test public void overflowIsPermanentPerFieldWhileOtherSumsAndCoverageKeepAdvancing() {
        Accumulator accumulator = new Accumulator();
        accumulator.beginCall();
        accumulator.accept(usage("\"prompt_tokens\":9223372036854775807,\"completion_tokens\":0,\"total_tokens\":9223372036854775807"));
        ModelTokenUsage largest = accumulator.snapshot();
        assertEquals(Completeness.COMPLETE, largest.completeness);
        accumulator.beginCall(); accumulator.accept(usage("\"prompt_tokens\":1,\"completion_tokens\":2"));
        accumulator.beginCall(); accumulator.accept(usage("\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0"));
        ModelTokenUsage overflow = accumulator.snapshot();
        assertNull(overflow.prompt.subtotal); assertTrue(overflow.prompt.overflowed);
        assertEquals(3, overflow.prompt.reportedCalls);
        assertEquals(Long.valueOf(2), overflow.completion.subtotal); assertFalse(overflow.completion.overflowed);
        assertEquals(3, overflow.completion.reportedCalls);
        assertEquals(Long.valueOf(Long.MAX_VALUE), overflow.total.subtotal); assertFalse(overflow.total.overflowed);
        assertEquals(2, overflow.total.reportedCalls); assertEquals(Completeness.PARTIAL, overflow.completeness);
        accumulator.beginCall(); accumulator.accept(usage("\"prompt_tokens\":5,\"completion_tokens\":3"));
        ModelTokenUsage later = accumulator.snapshot();
        assertNull(later.prompt.subtotal); assertTrue(later.prompt.overflowed); assertEquals(4, later.prompt.reportedCalls);
        assertEquals(Long.valueOf(5), later.completion.subtotal);
        assertEquals(Long.valueOf(Long.MAX_VALUE), largest.prompt.subtotal); assertFalse(largest.prompt.overflowed);
        assertEquals(3, overflow.prompt.reportedCalls); assertEquals(Long.valueOf(2), overflow.completion.subtotal);
    }
}
