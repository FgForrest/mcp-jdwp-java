package one.edee.mcp.jdwp.collector;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CollectorArgumentsTest {
    @Test
    void parsesOverrides() {
        CollectorMain.Arguments args = CollectorMain.Arguments.parse(new String[] {
            "collect", "--plan", "plan.json", "--output", "trace", "--host", "127.0.0.1", "--port", "6006"
        });
        assertEquals(Path.of("plan.json"), args.plan());
        assertEquals(Path.of("trace"), args.outputDirectory());
        assertEquals("127.0.0.1", args.host());
        assertEquals(6006, args.port());
    }

    @Test
    void requiresPlan() {
        assertThrows(IllegalArgumentException.class, () -> CollectorMain.Arguments.parse(new String[] {"collect"}));
    }
}
