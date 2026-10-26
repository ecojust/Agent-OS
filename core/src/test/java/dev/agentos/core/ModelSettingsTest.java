package dev.agentos.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModelSettingsTest {
    @Test public void acceptsBaseOrCompleteEndpointWithoutPuttingKeyIntoUrl() {
        for(String endpoint:new String[]{"https://models.example/v1", "https://models.example/v1/", "https://models.example/v1/chat/completions"}) {
            ModelSettings settings=new ModelSettings(endpoint,"private-key","chosen-model");
            settings.validate();
            assertEquals("https://models.example/v1/chat/completions",settings.completionUrl().toString());
            assertFalse(settings.completionUrl().toString().contains("private-key"));
        }
        assertEquals("https://models.example/v1/chat/completions",new ModelSettings("https://models.example","","m").completionUrl().toString());
    }

    @Test public void rejectsEmbeddedCredentialsQueryFragmentsAndPublicPlaintext() {
        for(String endpoint:new String[]{"https://key@models.example/v1", "https://models.example/v1?api_key=private", "https://models.example/v1#private", "http://models.example/v1", "file:///private/key", "https://models.example:0/v1", "https://models.example:70000/v1"}) {
            IllegalArgumentException error=assertThrows(endpoint,IllegalArgumentException.class,()->new ModelSettings(endpoint,"private-key","m").validate());
            assertFalse(error.getMessage().contains("private-key"));
        }
    }

    @Test public void permitsExplicitLocalDevelopmentEndpoints() {
        for(String endpoint:new String[]{"http://127.0.0.1:8000/v1", "http://localhost:8000/v1", "http://192.168.1.10:8000/v1", "http://10.0.0.20/v1", "http://[::1]:8000/v1"}) {
            new ModelSettings(endpoint,"","m").validate();
        }
    }

    @Test public void rejectsHeaderInjectionAndEmptyModel() {
        assertThrows(IllegalArgumentException.class,()->new ModelSettings("https://models.example/v1","secret\r\nX-Evil: yes","m").validate());
        assertThrows(IllegalArgumentException.class,()->new ModelSettings("https://models.example/v1","secret","").validate());
    }
}
