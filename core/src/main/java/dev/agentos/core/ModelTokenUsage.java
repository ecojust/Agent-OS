package dev.agentos.core;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;

/** Immutable service-reported token counts, not a cost estimate or an execution budget. */
public final class ModelTokenUsage {
    public enum Completeness { UNKNOWN, PARTIAL, COMPLETE }

    public static final class Field {
        /** Null means no report or an overflow; a reported zero remains zero. */
        public final Long subtotal;
        public final long reportedCalls;
        public final boolean overflowed;

        private Field(Long subtotal, long reportedCalls, boolean overflowed) {
            this.subtotal = subtotal; this.reportedCalls = reportedCalls; this.overflowed = overflowed;
        }
    }

    public final long calls;
    public final Field prompt, completion, total;
    public final Completeness completeness;

    private ModelTokenUsage(long calls, Field prompt, Field completion, Field total) {
        this.calls = calls; this.prompt = prompt; this.completion = completion; this.total = total;
        if (calls > 0 && prompt.reportedCalls == calls && completion.reportedCalls == calls
                && total.reportedCalls == calls && !prompt.overflowed && !completion.overflowed && !total.overflowed) {
            completeness = Completeness.COMPLETE;
        } else if (prompt.subtotal != null || completion.subtotal != null || total.subtotal != null) {
            completeness = Completeness.PARTIAL;
        } else completeness = Completeness.UNKNOWN;
    }

    /** Only known counts survive parsing; no response text, identifiers or unknown fields are retained. */
    static final class Report {
        static final Report UNKNOWN = new Report(null, null, null);
        final Long prompt, completion, total;
        private Report(Long prompt, Long completion, Long total) {
            this.prompt = prompt; this.completion = completion; this.total = total;
        }
    }

    /**
     * Called after the gateway's existing whole-response validation and byte limit.
     * A second bounded scan observes duplicate keys lost by a JSON tree, without
     * making optional usage metadata a condition for accepting the model response.
     */
    static Report parseReport(String alreadyValidatedJson) {
        if (alreadyValidatedJson == null || alreadyValidatedJson.length() > HttpModelClient.MAX_RESPONSE_BYTES)
            return Report.UNKNOWN;
        try (JsonReader reader = new JsonReader(new StringReader(alreadyValidatedJson))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return Report.UNKNOWN;
            reader.beginObject();
            boolean found = false;
            Report report = Report.UNKNOWN;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("usage".equals(name)) {
                    if (found) return Report.UNKNOWN;
                    found = true;
                    report = readUsage(reader);
                } else reader.skipValue();
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT ? report : Report.UNKNOWN;
        } catch (IOException | RuntimeException malformedMetadata) {
            return Report.UNKNOWN;
        }
    }

    private static Report readUsage(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return Report.UNKNOWN; }
        reader.beginObject();
        int seen = 0;
        boolean duplicate = false;
        Long prompt = null, completion = null, total = null;
        while (reader.hasNext()) {
            String name = reader.nextName();
            int field = "prompt_tokens".equals(name) ? 1 : "completion_tokens".equals(name) ? 2
                    : "total_tokens".equals(name) ? 4 : 0;
            if (field == 0) { reader.skipValue(); continue; }
            duplicate |= (seen & field) != 0;
            seen |= field;
            Long value = readCount(reader);
            if (field == 1) prompt = value;
            else if (field == 2) completion = value;
            else total = value;
        }
        reader.endObject();
        if (duplicate || prompt == null && completion == null && total == null) return Report.UNKNOWN;
        if (total != null && (prompt != null && total < prompt || completion != null && total < completion))
            return Report.UNKNOWN;
        if (prompt != null && completion != null && total != null
                && (prompt > Long.MAX_VALUE - completion || prompt + completion != total)) return Report.UNKNOWN;
        return new Report(prompt, completion, total);
    }

    private static Long readCount(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) { reader.skipValue(); return null; }
        String token = reader.nextString();
        if (token.isEmpty() || token.length() > 19 || token.length() > 1 && token.charAt(0) == '0') return null;
        for (int index = 0; index < token.length(); index++) {
            char digit = token.charAt(index);
            if (digit < '0' || digit > '9') return null;
        }
        try { return Long.parseLong(token); }
        catch (NumberFormatException outsideRange) { return null; }
    }

    /** The owning RunControl serializes calls and supplies one accepted report at most per call. */
    static final class Accumulator {
        private long calls;
        private final Sum prompt = new Sum(), completion = new Sum(), total = new Sum();

        void beginCall() { calls++; }
        void accept(Report report) {
            if (report == null) return;
            prompt.add(report.prompt); completion.add(report.completion); total.add(report.total);
        }
        ModelTokenUsage snapshot() {
            return new ModelTokenUsage(calls, prompt.snapshot(), completion.snapshot(), total.snapshot());
        }
    }

    private static final class Sum {
        private long subtotal, reportedCalls;
        private boolean overflowed;

        void add(Long value) {
            if (value == null) return;
            reportedCalls++;
            if (overflowed) return;
            if (subtotal > Long.MAX_VALUE - value) overflowed = true;
            else subtotal += value;
        }
        Field snapshot() {
            return new Field(reportedCalls == 0 || overflowed ? null : subtotal, reportedCalls, overflowed);
        }
    }
}
