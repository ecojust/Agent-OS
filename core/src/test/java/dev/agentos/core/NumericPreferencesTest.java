package dev.agentos.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

public final class NumericPreferencesTest {
    private static final String[] KEYS = {"format", "schemaVersion", "maxOutputTokens", "maxModelRounds", "maxToolCalls", "maxActiveSeconds"};
    private static final String[] VALUES = {"\"agent-os-numeric-preferences\"", "1", "2048", "30", "100", "600"};
    private static final String CANONICAL = "{\"format\":\"agent-os-numeric-preferences\",\"schemaVersion\":1,"
            + "\"maxOutputTokens\":2048,\"maxModelRounds\":30,\"maxToolCalls\":100,\"maxActiveSeconds\":600}";
    private static final NumericPreferences DEFAULT = new NumericPreferences(2048, 30, 100, 600);

    @Test public void exportHasOnlyTheSixFixedKeysInStableUtf8Order() {
        assertArrayEquals(bytes(CANONICAL), DEFAULT.encode());
        assertEquals(DEFAULT, NumericPreferences.decode(DEFAULT.encode()));
        assertEquals(TaskBudget.DEFAULT, DEFAULT.toTaskBudget());
        assertEquals(DEFAULT, NumericPreferences.fromTaskBudget(ModelSettings.DEFAULT_MAX_OUTPUT_TOKENS, TaskBudget.DEFAULT));
        assertTrue(DEFAULT.encode().length < NumericPreferences.MAX_ENCODED_BYTES);
    }

    @Test public void minimumMaximumAndMixedBoundaryPreferencesRoundTripExactly() {
        for (int tokens : new int[]{128, 2048, 8192}) {
            for (int rounds : new int[]{1, 30, 300}) {
                for (int tools : new int[]{1, 100, 1000}) {
                    for (int seconds : new int[]{1, 600, 3600}) {
                        NumericPreferences original = new NumericPreferences(tokens, rounds, tools, seconds);
                        NumericPreferences decoded = NumericPreferences.decode(original.encode());
                        assertEquals(original, decoded);
                        assertEquals(tokens, decoded.maxOutputTokens);
                        assertEquals(rounds, decoded.maxModelRounds);
                        assertEquals(tools, decoded.maxToolCalls);
                        assertEquals(seconds, decoded.maxActiveSeconds);
                        assertEquals(seconds * 1000L, decoded.toTaskBudget().maxActiveMillis);
                        assertEquals(original, NumericPreferences.fromTaskBudget(tokens, decoded.toTaskBudget()));
                    }
                }
            }
        }
    }

    @Test public void everyConstructorDimensionRejectsOutOfRangeValuesWithoutOverflow() {
        for (int value : new int[]{Integer.MIN_VALUE, 0, 127, 8193, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new NumericPreferences(value, 30, 100, 600));
        for (int value : new int[]{Integer.MIN_VALUE, 0, 301, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new NumericPreferences(2048, value, 100, 600));
        for (int value : new int[]{Integer.MIN_VALUE, 0, 1001, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new NumericPreferences(2048, 30, value, 600));
        // 4,294,968 * 1000 would wrap to a small valid millisecond value if multiplied as int.
        for (int value : new int[]{Integer.MIN_VALUE, 0, 3601, 4_294_968, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new NumericPreferences(2048, 30, 100, value));
    }

    @Test public void millisecondBudgetsMustMapToPositiveWholeSecondsWithoutRounding() {
        for (long millis : new long[]{1, 75, 999, 1001, 600_001, 3_599_999}) {
            TaskBudget precise = new TaskBudget(30, 100, millis);
            assertThrows(IllegalArgumentException.class, () -> NumericPreferences.fromTaskBudget(2048, precise));
        }
        assertEquals(1, NumericPreferences.fromTaskBudget(128, new TaskBudget(1, 1, 1000)).maxActiveSeconds);
        assertEquals(3600, NumericPreferences.fromTaskBudget(8192, new TaskBudget(300, 1000, 3_600_000)).maxActiveSeconds);
        assertThrows(IllegalArgumentException.class, () -> NumericPreferences.fromTaskBudget(2048, null));
    }

    @Test public void validWhitespaceOrderAndJsonEscapesDoNotChangeTheValue() {
        String reordered = " \t\r\n{\n\"maxActiveSeconds\":600,\"maxToolCalls\":100,\"maxModelRounds\":30,"
                + "\"max\\u004futputTokens\":2048,\"schemaVersion\":1,\"format\":\"agent\\u002dos-numeric-preferences\"}\r\n";
        assertEquals(DEFAULT, NumericPreferences.decode(bytes(reordered)));
        assertArrayEquals(bytes(CANONICAL), NumericPreferences.decode(bytes(reordered)).encode());
    }

    @Test public void eachRequiredFieldMustBePresentRatherThanDefaulted() {
        for (int missing = 0; missing < KEYS.length; missing++) {
            StringBuilder source = new StringBuilder("{");
            for (int index = 0; index < KEYS.length; index++) {
                if (index == missing) continue;
                if (source.length() > 1) source.append(',');
                source.append('"').append(KEYS[index]).append("\":").append(VALUES[index]);
            }
            reject(source.append('}').toString());
        }
        reject("{}");
    }

    @Test public void duplicateKeysIncludingEscapedSpellingsAreRejectedEvenWhenValuesAgree() {
        for (int index = 0; index < KEYS.length; index++) {
            reject(extra(KEYS[index], VALUES[index]));
            reject(extra(KEYS[index], "null"));
        }
        reject(extra("max\\u004futputTokens", "2048"));
        reject(extra("\\u0066ormat", "\"agent-os-numeric-preferences\""));
    }

    @Test public void unknownAndAuthorityBearingFieldsCannotBeImported() {
        for (String key : new String[]{"key", "apiKey", "endpoint", "model", "grants", "mode", "executionMode",
                "task", "taskId", "profileId", "allowedPackages", "confirmEachAction", "budget", "unknown", "maxOutputTokens "}) {
            reject(extra(key, "\"synthetic-secret-not-a-numeric-preference\""));
        }
        reject(extra("unknown", "{\"nested\":[{\"apiKey\":\"synthetic-secret\"}]}"));
    }

    @Test public void numericFieldsRequireNumbersRatherThanStringsBooleansNullOrContainers() {
        for (int field = 1; field < KEYS.length; field++) {
            reject(replace(field, "\"" + VALUES[field] + "\""));
            for (String value : new String[]{"true", "false", "null", "[]", "{}", "[1]", "{\"value\":1}"})
                reject(replace(field, value));
        }
    }

    @Test public void numericLiteralsRejectDecimalExponentSignsLeadingZerosAndNonJsonForms() {
        for (int field = 1; field < KEYS.length; field++) {
            String valid = VALUES[field];
            // Each alternate decimal/exponent spelling has the same in-range
            // mathematical value; coercion cannot accidentally satisfy this test.
            for (String value : new String[]{valid + ".0", valid + "e0", valid + "E+0", "+" + valid, "0" + valid,
                    "-" + valid, "0x10", "NaN", "Infinity", "-Infinity", "1_000", "１２８", "\\u0031"})
                reject(replace(field, value));
        }
    }

    @Test public void integerOverflowAndEveryImportedRangeBoundaryAreRejected() {
        for (int field = 1; field < KEYS.length; field++) {
            for (String value : new String[]{"2147483648", "4294967296", "9223372036854775808", "9".repeat(1000)})
                reject(replace(field, value));
        }
        String[][] bounds = {{"0", "2"}, {"127", "8193"}, {"0", "301"}, {"0", "1001"}, {"0", "3601", "4294968"}};
        for (int field = 1; field < KEYS.length; field++)
            for (String invalid : bounds[field - 1]) reject(replace(field, invalid));
    }

    @Test public void formatAndVersionAreExactAndCannotBeCoercedOrGuessed() {
        for (String value : new String[]{"null", "1", "true", "{}", "[]", "\"\"", "\"agent-os-numeric-preferences-v2\"",
                "\"AGENT-OS-NUMERIC-PREFERENCES\"", "\"agent-os-numeric-preferences \""}) reject(replace(0, value));
        for (String value : new String[]{"0", "2", "-1", "-0", "\"1\"", "1.0", "1e0"}) reject(replace(1, value));
    }

    @Test public void strictJsonRejectsCommentsSingleQuotesUnquotedKeysAndTrailingCommas() {
        for (String source : new String[]{"/*comment*/" + CANONICAL, CANONICAL + "//comment", "#comment\n" + CANONICAL,
                CANONICAL.replace("\"schemaVersion\":", "schemaVersion:"), CANONICAL.replace('"', '\''),
                CANONICAL.substring(0, CANONICAL.length() - 1) + ",}",
                CANONICAL.replace(",\"schemaVersion\"", ";\"schemaVersion\""),
                CANONICAL.replace(":1,", "=1,"), CANONICAL.replace(":1,", ":1 /*comment*/,"),
                CANONICAL.replace("agent-os-numeric", "agent-os-\nnumeric")}) reject(source);
    }

    @Test public void documentMustBeOneCompleteObjectWithNoTrailingData() {
        for (String source : new String[]{"", " ", "null", "true", "123", "\"text\"", "[]", "[" + CANONICAL + "]",
                CANONICAL.substring(1), CANONICAL.substring(0, CANONICAL.length() - 1), CANONICAL + CANONICAL,
                CANONICAL + "null", CANONICAL + "0", CANONICAL + "garbage", "{}" + CANONICAL}) reject(source);
    }

    @Test public void byteLimitIsInclusiveAndAppliesBeforeParsing() {
        byte[] exact = new byte[NumericPreferences.MAX_ENCODED_BYTES];
        Arrays.fill(exact, (byte) ' ');
        byte[] document = bytes(CANONICAL);
        System.arraycopy(document, 0, exact, 0, document.length);
        assertEquals(DEFAULT, NumericPreferences.decode(exact));
        byte[] tooLong = Arrays.copyOf(exact, exact.length + 1);
        tooLong[tooLong.length - 1] = ' ';
        assertThrows(IllegalArgumentException.class, () -> NumericPreferences.decode(tooLong));
        assertThrows(IllegalArgumentException.class, () -> NumericPreferences.decode(null));
    }

    @Test public void malformedUtf8AndBomAreRejectedWithoutReplacementDecoding() {
        reject("\ufeff" + CANONICAL);
        reject(" \ufeff" + CANONICAL);
        for (byte[] invalid : new byte[][]{{(byte) 0x80}, {(byte) 0xc0, (byte) 0xaf}, {(byte) 0xc2},
                {(byte) 0xe2, 0x28, (byte) 0xa1}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xff}}) {
            byte[] document = bytes(CANONICAL);
            byte[] source = Arrays.copyOf(document, document.length + invalid.length);
            System.arraycopy(invalid, 0, source, document.length, invalid.length);
            assertThrows(IllegalArgumentException.class, () -> NumericPreferences.decode(source));
        }
        byte[] utf16 = CANONICAL.getBytes(StandardCharsets.UTF_16);
        assertThrows(IllegalArgumentException.class, () -> NumericPreferences.decode(utf16));
    }

    @Test public void errorsCannotEchoOrRetainUntrustedInputThroughTheirThrowableGraph() {
        String marker = "synthetic-private-marker-never-include-in-errors";
        for (String source : new String[]{extra(marker, "true"), replace(0, "\"" + marker + "\""),
                replace(2, "\"" + marker + "\""), CANONICAL + marker}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NumericPreferences.decode(bytes(source)));
            assertEquals("数值偏好文件格式或范围无效。", failure.getMessage());
            assertFalse(failure.toString().contains(marker));
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    @Test public void valuesDoNotRetainInputBytesOrShareMutableEncodedBuffers() {
        byte[] input = bytes(CANONICAL);
        NumericPreferences decoded = NumericPreferences.decode(input);
        Arrays.fill(input, (byte) 0);
        byte[] first = decoded.encode();
        Arrays.fill(first, (byte) 0);
        assertEquals(DEFAULT, decoded);
        assertArrayEquals(bytes(CANONICAL), decoded.encode());
    }

    @Test public void equalityIncludesAllFourPreferencesAndIgnoresObjectIdentity() {
        NumericPreferences same = new NumericPreferences(2048, 30, 100, 600);
        assertNotSame(DEFAULT, same);
        assertEquals(DEFAULT, same);
        assertEquals(DEFAULT.hashCode(), same.hashCode());
        HashSet<NumericPreferences> values = new HashSet<>();
        values.add(DEFAULT); values.add(same);
        for (NumericPreferences other : new NumericPreferences[]{new NumericPreferences(2049, 30, 100, 600),
                new NumericPreferences(2048, 31, 100, 600), new NumericPreferences(2048, 30, 101, 600),
                new NumericPreferences(2048, 30, 100, 601)}) {
            assertNotEquals(DEFAULT, other); values.add(other);
        }
        assertEquals(5, values.size());
        assertNotEquals(DEFAULT, null);
        assertNotEquals(DEFAULT, CANONICAL);
    }

    private static byte[] bytes(String source) { return source.getBytes(StandardCharsets.UTF_8); }
    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class, () -> NumericPreferences.decode(bytes(source)));
    }
    private static String extra(String name, String value) {
        return CANONICAL.substring(0, CANONICAL.length() - 1) + ",\"" + name + "\":" + value + "}";
    }
    private static String replace(int field, String value) {
        StringBuilder source = new StringBuilder("{");
        for (int index = 0; index < KEYS.length; index++) {
            if (index > 0) source.append(',');
            source.append('"').append(KEYS[index]).append("\":").append(index == field ? value : VALUES[index]);
        }
        return source.append('}').toString();
    }
}
