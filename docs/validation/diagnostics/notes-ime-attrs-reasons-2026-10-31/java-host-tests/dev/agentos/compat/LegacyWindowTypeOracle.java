package dev.agentos.compat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** Frozen 9b86b2a842f9e837e20e2df183893da697faaf40 oracle; helper SHA256
 * 273104d38ca9ec7e4d0fbca2aecdc00c3e24467dc66f6029776ccd861bd172e9.
 * Only method visibility changes here; this golden oracle is not a new implementation. */
final class LegacyWindowTypeOracle {
    static String windowType(String record) {
        Matcher attributes = Pattern.compile("(?m)^[\\t ]*mAttrs=\\{").matcher(record);
        if (!attributes.find()) return null;
        int start = attributes.end() - 1;
        if (attributes.find()) return null;
        int depth = 0, typeFields = 0;
        String type = null;
        for (int index = start; index < record.length() && index - start < 8192; index++) {
            char current = record.charAt(index);
            if (current == '{') {
                if (++depth > 8) return null;
            } else if (current == '}') {
                if (--depth == 0) return typeFields == 1 ? type : null;
            } else if (depth == 1 && (index == start + 1 || Character.isWhitespace(record.charAt(index - 1)))
                    && record.startsWith("ty=", index)) {
                int end = index + 3;
                while (end < record.length() && end - index <= 67
                        && !Character.isWhitespace(record.charAt(end)) && record.charAt(end) != '}' && record.charAt(end) != '{') end++;
                String value = record.substring(index + 3, end);
                if (!value.matches("[A-Z_0-9]{1,64}") || end < record.length() && record.charAt(end) == '{') return null;
                type = value; typeFields++;
                index = end - 1;
            }
        }
        return null; // Missing closing brace or excessive size/depth is an unavailable format.
    }

}
