package cn.companion.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModelEndpointTest {
    @Test public void permitsHttpsBaseAndFullPath() {
        assertNull(ModelClient.validateEndpoint("https://example.com/v1"));
        assertNull(ModelClient.validateEndpoint("https://example.com:8443/v1/chat/completions"));
    }
    @Test public void rejectsLeakyOrInsecureEndpointShapes() {
        for (String endpoint : new String[]{"", "http://example.com", "https://user:password@example.com", "https://example.com?key=secret", "https://example.com#fragment", "file:///tmp/test", "not an address"}) {
            assertNotNull(endpoint, ModelClient.validateEndpoint(endpoint));
        }
    }
}
