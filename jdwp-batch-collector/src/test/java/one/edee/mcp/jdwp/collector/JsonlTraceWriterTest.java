package one.edee.mcp.jdwp.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonlTraceWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesExactlyOneCompactJsonObjectPerLineEvenWhenMapperIsIndented() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path trace = temporaryDirectory.resolve("trace.jsonl");
        try (JsonlTraceWriter writer = new JsonlTraceWriter(mapper, trace)) {
            writer.write(Map.of("eventType", "first", "details", Map.of("wafer", "W1")));
            writer.write(Map.of("eventType", "second", "items", List.of(1, 2, 3)));
        }

        List<String> lines = Files.readAllLines(trace);
        assertEquals(2, lines.size());
        assertEquals("first", mapper.readTree(lines.get(0)).get("eventType").asText());
        assertEquals("second", mapper.readTree(lines.get(1)).get("eventType").asText());
    }
}
