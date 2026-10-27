package dev.agentos.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.*;

public final class OwnedRequestPayloadTest {
    private static ModelRequestContext context(RunControl run) {
        return ModelRequestContext.syntheticRequest(run, run.getExecutionEpoch());
    }

    @Test public void utf8WireBytesMatchOriginalSerializationIncludingSurrogatesAndHtml() throws Exception {
        String[] values = {"plain", "<script a='x'>&=\"\\\n\r\t\u2028\u2029", "中文 café 😀",
                "before\ud800after\udc00end", "x".repeat(8191) + "😀" + "y".repeat(8191) + "\ud800"};
        for (String value : values) {
            JsonObject body = new JsonObject(); body.addProperty("text", value); body.add("null", JsonNull.INSTANCE);
            JsonArray array = new JsonArray(); array.add(true); array.add(1.5); array.add(JsonNull.INSTANCE); body.add("array", array);
            byte[] expected = body.toString().getBytes(StandardCharsets.UTF_8);
            ImageWorkset workset = new ImageWorkset(OwnedRequestPayload.MAX_BYTES);
            ModelRequestContext context = context(new RunControl());
            try (OwnedRequestPayload payload = OwnedRequestPayload.encode(body, workset, context)) {
                assertEquals(expected.length, payload.length()); assertEquals(expected.length, workset.reservedBytes());
                ByteArrayOutputStream sent = new ByteArrayOutputStream(); payload.writeTo(sent, context);
                assertArrayEquals(expected, sent.toByteArray());
            }
            assertEquals(0, workset.reservedBytes());
        }
    }

    @Test public void exactFourMebibyteBoundaryIsAcceptedAndOneExtraByteIsRejected() throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("x", "");
        int overhead = body.toString().getBytes(StandardCharsets.UTF_8).length;
        body.addProperty("x", "a".repeat(OwnedRequestPayload.MAX_BYTES - overhead));
        ImageWorkset workset = new ImageWorkset(OwnedRequestPayload.MAX_BYTES);
        ModelRequestContext context = context(new RunControl());
        try (OwnedRequestPayload payload = OwnedRequestPayload.encode(body, workset, context)) {
            assertEquals(OwnedRequestPayload.MAX_BYTES, payload.length());
            assertEquals(OwnedRequestPayload.MAX_BYTES, workset.reservedBytes());
        }
        body.addProperty("x", "a".repeat(OwnedRequestPayload.MAX_BYTES - overhead + 1));
        ModelException rejected = assertThrows(ModelException.class, () -> OwnedRequestPayload.encode(body, workset, context));
        assertEquals("CONTEXT_LIMIT", rejected.code); assertEquals(0, workset.reservedBytes());
    }

    @Test public void budgetAdmissionUsesUtf8BytesAndLeavesOtherOwnersUntouched() throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("text", "中文😀");
        int bytes = body.toString().getBytes(StandardCharsets.UTF_8).length;
        ImageWorkset workset = new ImageWorkset(bytes + 5);
        try (ImageWorkset.Reservation other = workset.reserve(6)) {
            ModelException rejected = assertThrows(ModelException.class,
                    () -> OwnedRequestPayload.encode(body, workset, context(new RunControl())));
            assertEquals("REQUEST_MEMORY_LIMIT", rejected.code); assertEquals(6, workset.reservedBytes());
        }
        try (OwnedRequestPayload payload = OwnedRequestPayload.encode(body, workset, context(new RunControl()))) {
            assertEquals(bytes, payload.length()); assertEquals(bytes, workset.reservedBytes());
        }
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void mutableNumberCannotChangeBetweenPassesEvenWhenLengthMatches() {
        for (String second : new String[]{"2", "123", "0.1"}) {
            AtomicInteger serializations = new AtomicInteger(); JsonObject body = new JsonObject();
            body.addProperty("value", number(() -> serializations.incrementAndGet() == 1 ? "1" : second));
            ImageWorkset workset = new ImageWorkset(4096);
            ModelException changed = assertThrows(ModelException.class,
                    () -> OwnedRequestPayload.encode(body, workset, context(new RunControl())));
            assertEquals("REQUEST_CHANGED", changed.code); assertEquals(2, serializations.get());
            assertEquals(0, workset.reservedBytes());
        }
        AtomicInteger serializations = new AtomicInteger(); JsonObject shorter = new JsonObject();
        shorter.addProperty("value", number(() -> serializations.incrementAndGet() == 1 ? "123" : "1"));
        ImageWorkset workset = new ImageWorkset(4096);
        assertEquals("REQUEST_CHANGED", assertThrows(ModelException.class,
                () -> OwnedRequestPayload.encode(shorter, workset, context(new RunControl()))).code);
        assertEquals(0, workset.reservedBytes());
    }

    @Test public void cancellationDuringEitherPassReleasesEveryOwnedBuffer() {
        for (int stopOn : new int[]{1, 2}) {
            AtomicInteger serializations = new AtomicInteger(); RunControl run = new RunControl();
            ModelRequestContext context = context(run); ImageWorkset workset = new ImageWorkset(4096);
            JsonObject body = new JsonObject(); body.addProperty("value", number(() -> {
                if (serializations.incrementAndGet() == stopOn) run.cancel(); return "1";
            }));
            assertEquals("TASK_STOPPED", assertThrows(TaskControlException.class,
                    () -> OwnedRequestPayload.encode(body, workset, context)).getCode());
            assertEquals(stopOn, serializations.get()); assertEquals(0, workset.reservedBytes());
        }
    }

    @Test public void encodingErrorAfterAdmissionDoesNotLeakTheReservedPayload() {
        AtomicInteger serializations = new AtomicInteger(); ImageWorkset workset = new ImageWorkset(4096);
        JsonObject body = new JsonObject(); AssertionError injected = new AssertionError("synthetic serializer failure");
        body.addProperty("value", number(() -> {
            if (serializations.incrementAndGet() == 2) {
                assertTrue(workset.reservedBytes() > 0); throw injected;
            }
            return "1";
        }));
        assertSame(injected, assertThrows(AssertionError.class,
                () -> OwnedRequestPayload.encode(body, workset, context(new RunControl()))));
        assertEquals(0, workset.reservedBytes());
    }

    static Number number(java.util.function.Supplier<String> value) {
        return new Number() {
            public int intValue() { return 1; } public long longValue() { return 1; }
            public float floatValue() { return 1; } public double doubleValue() { return 1; }
            @Override public String toString() { return value.get(); }
        };
    }
}
