package one.edee.mcp.jdwp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdwpEndpointTest {
    @Test
    void validatesAndNormalizesAddress() {
        JdwpEndpoint endpoint = new JdwpEndpoint(" localhost ", 5005);
        assertEquals("localhost", endpoint.host());
        assertEquals(5005, endpoint.port());
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> new JdwpEndpoint("localhost", 0));
    }
}
