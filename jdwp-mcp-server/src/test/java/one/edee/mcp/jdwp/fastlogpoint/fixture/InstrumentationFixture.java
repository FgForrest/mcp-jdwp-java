package one.edee.mcp.jdwp.fastlogpoint.fixture;

/**
 * Bytecode fixture for {@code ProbeInstrumenterTest}. The test finds lines by their
 * {@code // MARK:} comments and instruments them with calls to {@link ProbeRecorder}.
 */
public class InstrumentationFixture {
    private final String name;

    public InstrumentationFixture(String name) {
        this.name = name;
    }

    public long mix(int count, long weight, String tag) {
        long total = weight;
        for (int i = 0; i < count; i++) {
            total += i; // MARK: loop
        }
        final String described = tag + '/' + name;
        return total + described.length(); // MARK: result
    }

    public static double scale(double factor, int[] values) {
        double sum = 0;
        for (int value : values) {
            sum += value * factor;
        }
        return sum; // MARK: static
    }
}
