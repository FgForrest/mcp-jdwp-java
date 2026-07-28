package one.edee.mcp.jdwp.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebugPlanTest {
    @Test
    void readsAndValidatesPlan() throws Exception {
        String json = """
            {
              "sessionId": "wafer-order",
              "target": {"host": "localhost", "port": 5005},
              "tracepoints": [{
                "id": "candidate-selected",
                "className": "org.example.SimpleScheduler",
                "line": 42
              }]
            }
            """;
        DebugPlan plan = new ObjectMapper().readValue(json, DebugPlan.class);
        plan.validate();
        assertEquals("wafer-order", plan.sessionId);
        assertEquals(42, plan.tracepoints.get(0).line);
    }

    @Test
    void rejectsDuplicateIds() {
        DebugPlan plan = new DebugPlan();
        DebugPlan.Tracepoint first = tracepoint("same", 10);
        DebugPlan.Tracepoint second = tracepoint("same", 20);
        plan.tracepoints.add(first);
        plan.tracepoints.add(second);
        assertThrows(IllegalArgumentException.class, plan::validate);
    }

    private static DebugPlan.Tracepoint tracepoint(String id, int line) {
        DebugPlan.Tracepoint tracepoint = new DebugPlan.Tracepoint();
        tracepoint.id = id;
        tracepoint.className = "example.Target";
        tracepoint.line = line;
        return tracepoint;
    }
}
