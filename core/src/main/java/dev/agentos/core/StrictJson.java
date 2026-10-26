package dev.agentos.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;

final class StrictJson {
    private StrictJson() { }
    static JsonObject object(String text) {
        if (text == null) throw new IllegalArgumentException("JSON 对象缺失。");
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 64) throw new IllegalArgumentException("JSON 层级超过上限。"); }
            else if (c == '}' || c == ']') depth--;
        }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            return value.getAsJsonObject();
        } catch (Exception invalid) { throw new IllegalArgumentException("JSON 对象格式无效。"); }
    }
}
