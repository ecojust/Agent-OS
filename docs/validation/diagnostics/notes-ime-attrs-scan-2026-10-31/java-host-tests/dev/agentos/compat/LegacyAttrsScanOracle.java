package dev.agentos.compat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Exact previous 35519e9fb scanner, visibility-only method edit. Result projection
 * retains only original type/reason; ignored constructor metadata cannot affect decisions. */
final class LegacyAttrsScanOracle {
    enum AttrsReason {
        PARSED, ATTRS_ANCHOR_MISSING, ATTRS_ANCHOR_DUPLICATE, DEPTH_LIMIT,
        TYPE_VALUE_UNRECOGNIZED, TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE,
        TOP_LEVEL_TYPE_MISSING, TOP_LEVEL_TYPE_DUPLICATE, ATTRS_SIZE_LIMIT, ATTRS_UNCLOSED
    }
    static final class WindowTypeResult {
        final String type;
        final AttrsReason reason;
        WindowTypeResult(String type, AttrsReason reason, int anchorCount, int firstAnchor, int secondAnchor,
                         int scanChars, int maxDepth, int typeFields, String invalidValue, Boolean completeValue) {
            this.type = type; this.reason = reason;
        }
    }
    static WindowTypeResult windowType(String record) {
        Matcher attributes = Pattern.compile("(?m)^[\\t ]*mAttrs=\\{").matcher(record);
        int anchors = 0, start = -1, second = -1;
        while (attributes.find()) {
            if (anchors == 0) start = attributes.end() - 1;
            else if (anchors == 1) second = attributes.end() - 1;
            anchors++;
        }
        if (anchors == 0 || anchors > 1) return new WindowTypeResult(null,
                anchors == 0 ? AttrsReason.ATTRS_ANCHOR_MISSING : AttrsReason.ATTRS_ANCHOR_DUPLICATE,
                anchors, start, second, 0, 0, 0, null, null);
        int depth = 0, typeFields = 0, observedTypeFields = 0, scanChars = 0, maxDepth = 0;
        String type = null;
        for (int index = start; index < record.length() && index - start < 8192; index++) {
            scanChars = index - start + 1;
            char current = record.charAt(index);
            if (current == '{') {
                depth++; maxDepth = Math.max(maxDepth, depth);
                if (depth > 8) return new WindowTypeResult(null, AttrsReason.DEPTH_LIMIT,
                        anchors, start, second, scanChars, maxDepth, observedTypeFields, null, null);
            } else if (current == '}') {
                if (--depth == 0) return new WindowTypeResult(typeFields == 1 ? type : null,
                        typeFields == 1 ? AttrsReason.PARSED : typeFields == 0 ? AttrsReason.TOP_LEVEL_TYPE_MISSING : AttrsReason.TOP_LEVEL_TYPE_DUPLICATE,
                        anchors, start, second, scanChars, maxDepth, observedTypeFields, null, null);
            } else if (depth == 1 && (index == start + 1 || Character.isWhitespace(record.charAt(index - 1)))
                    && record.startsWith("ty=", index)) {
                observedTypeFields++;
                int end = index + 3;
                while (end < record.length() && end - index <= 67
                        && !Character.isWhitespace(record.charAt(end)) && record.charAt(end) != '}' && record.charAt(end) != '{') end++;
                String value = record.substring(index + 3, end);
                boolean completeValue = end == record.length() || Character.isWhitespace(record.charAt(end))
                        || record.charAt(end) == '}' || record.charAt(end) == '{';
                if (!value.matches("[A-Z_0-9]{1,64}") || end < record.length() && record.charAt(end) == '{')
                    return new WindowTypeResult(null, !value.matches("[A-Z_0-9]{1,64}")
                            ? AttrsReason.TYPE_VALUE_UNRECOGNIZED : AttrsReason.TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE,
                            anchors, start, second, scanChars, maxDepth, observedTypeFields, value, completeValue);
                type = value; typeFields++;
                index = end - 1;
            }
        }
        return new WindowTypeResult(null, record.length() - start >= 8192 ? AttrsReason.ATTRS_SIZE_LIMIT : AttrsReason.ATTRS_UNCLOSED,
                anchors, start, second, scanChars, maxDepth, observedTypeFields, null, null);
    }

}
