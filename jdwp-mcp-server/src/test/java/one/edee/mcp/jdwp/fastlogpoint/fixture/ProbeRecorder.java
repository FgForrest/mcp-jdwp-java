package one.edee.mcp.jdwp.fastlogpoint.fixture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stand-in probe for {@code ProbeInstrumenterTest}: the instrumented fixture calls these static
 * methods, which record their arguments. Loaded by the test's own class loader, so the recorded
 * calls are visible to the test even though the fixture is loaded in an isolated one.
 */
public final class ProbeRecorder {
    public static final List<List<Object>> CALLS = new ArrayList<>();

    private ProbeRecorder() {
    }

    public static void result(Object self, int count, long weight, String tag, long total, String described) {
        CALLS.add(List.of("result", self, count, weight, tag, total, described));
    }

    public static void loop(int count, long total, int i) {
        CALLS.add(List.of("loop", count, total, i));
    }

    public static void second(long total) {
        CALLS.add(List.of("second", total));
    }

    public static void scale(double factor, int[] values, double sum) {
        CALLS.add(Arrays.asList("static", factor, values.length, sum));
    }
}
