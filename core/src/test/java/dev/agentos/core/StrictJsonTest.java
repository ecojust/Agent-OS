package dev.agentos.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class StrictJsonTest {
    @Test public void rejectsNonStandardOrIncompletePayloads() {
        for(String text:new String[]{"[]", "null", "{broken", "{name:'value'}", "{\"a\":1} {\"b\":2}", "{\"n\":NaN}", "// comment\n{\"a\":1}"}) {
            assertThrows(text,IllegalArgumentException.class,()->StrictJson.object(text));
        }
        assertEquals("value",StrictJson.object(" {\"name\":\"value\"} \n").get("name").getAsString());
    }

    @Test public void nestedResponsesAreBoundedButQuotedBracesAreNotCounted() {
        String deep="{\"value\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}";
        assertThrows(IllegalArgumentException.class,()->StrictJson.object(deep));
        String braces="[".repeat(200);
        assertEquals(braces,StrictJson.object("{\"text\":\""+braces+"\"}").get("text").getAsString());
    }
}
