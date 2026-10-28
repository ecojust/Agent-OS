package dev.agentos.core;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Four numeric editor preferences only; this value conveys no configuration or execution authority. */
public final class NumericPreferences {
    public static final String FORMAT = "agent-os-numeric-preferences";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ENCODED_BYTES = 4096;

    public final int maxOutputTokens;
    public final int maxModelRounds;
    public final int maxToolCalls;
    public final int maxActiveSeconds;

    public NumericPreferences(int maxOutputTokens, int maxModelRounds, int maxToolCalls, int maxActiveSeconds) {
        if (maxOutputTokens < ModelSettings.MIN_MAX_OUTPUT_TOKENS
                || maxOutputTokens > ModelSettings.MAX_MAX_OUTPUT_TOKENS || maxActiveSeconds < 1) throw invalid();
        // The UI preference is whole seconds. TaskBudget deliberately retains its
        // separate millisecond-precision contract; do not change it for this format.
        new TaskBudget(maxModelRounds, maxToolCalls, maxActiveSeconds * 1000L);
        this.maxOutputTokens = maxOutputTokens;
        this.maxModelRounds = maxModelRounds;
        this.maxToolCalls = maxToolCalls;
        this.maxActiveSeconds = maxActiveSeconds;
    }

    public static NumericPreferences fromTaskBudget(int maxOutputTokens, TaskBudget budget) {
        if (budget == null || budget.maxActiveMillis < 1000 || budget.maxActiveMillis % 1000 != 0
                || budget.maxActiveMillis / 1000 > Integer.MAX_VALUE) throw invalid();
        return new NumericPreferences(maxOutputTokens, budget.maxModelRounds, budget.maxToolCalls,
                (int) (budget.maxActiveMillis / 1000));
    }

    public TaskBudget toTaskBudget() {
        return new TaskBudget(maxModelRounds, maxToolCalls, maxActiveSeconds * 1000L);
    }

    /** Stable six-key UTF-8 JSON, freshly allocated; no model settings object is accepted. */
    public byte[] encode() {
        return ("{\"format\":\"" + FORMAT + "\",\"schemaVersion\":" + SCHEMA_VERSION
                + ",\"maxOutputTokens\":" + maxOutputTokens + ",\"maxModelRounds\":" + maxModelRounds
                + ",\"maxToolCalls\":" + maxToolCalls + ",\"maxActiveSeconds\":" + maxActiveSeconds + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Parses a bounded in-memory document. Neither the source bytes nor source text are retained. */
    public static NumericPreferences decode(byte[] utf8) {
        if (utf8 == null || utf8.length == 0 || utf8.length > MAX_ENCODED_BYTES) throw invalid();
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException malformed) { throw invalid(); }
        // JsonReader otherwise accepts a leading BOM even in strict mode.
        if (text.isEmpty() || text.charAt(0) == '\ufeff') throw invalid();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            int seen = 0;
            int[] values = new int[4];
            while (reader.hasNext()) {
                String name = reader.nextName();
                int index;
                switch (name) {
                    case "format": index = 0; break;
                    case "schemaVersion": index = 1; break;
                    case "maxOutputTokens": index = 2; break;
                    case "maxModelRounds": index = 3; break;
                    case "maxToolCalls": index = 4; break;
                    case "maxActiveSeconds": index = 5; break;
                    default: throw invalid();
                }
                int bit = 1 << index;
                if ((seen & bit) != 0) throw invalid();
                seen |= bit;
                if (index == 0) {
                    if (reader.peek() != JsonToken.STRING || !FORMAT.equals(reader.nextString())) throw invalid();
                } else if (index == 1) {
                    if (decimalInteger(reader) != SCHEMA_VERSION) throw invalid();
                } else values[index - 2] = decimalInteger(reader);
            }
            reader.endObject();
            if (seen != 63 || reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return new NumericPreferences(values[0], values[1], values[2], values[3]);
        } catch (IOException | RuntimeException malformed) {
            // Parser exceptions may contain source excerpts or JSON paths. Do not
            // expose their messages or retain them as causes/suppressed errors.
            throw invalid();
        }
    }

    private static int decimalInteger(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw invalid();
        String token = reader.nextString();
        if (token.isEmpty() || token.length() > 10 || token.length() > 1 && token.charAt(0) == '0') throw invalid();
        for (int index = 0; index < token.length(); index++) {
            char digit = token.charAt(index);
            if (digit < '0' || digit > '9') throw invalid();
        }
        return Integer.parseInt(token);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("数值偏好文件格式或范围无效。");
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NumericPreferences)) return false;
        NumericPreferences that = (NumericPreferences) other;
        return maxOutputTokens == that.maxOutputTokens && maxModelRounds == that.maxModelRounds
                && maxToolCalls == that.maxToolCalls && maxActiveSeconds == that.maxActiveSeconds;
    }

    @Override public int hashCode() {
        int hash = 31 * maxOutputTokens + maxModelRounds;
        hash = 31 * hash + maxToolCalls;
        return 31 * hash + maxActiveSeconds;
    }
}
