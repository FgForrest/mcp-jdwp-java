package one.edee.mcp.jdwp.fastlogpoint.fixture;

/** Default-method probe fixture with a local that shares the usual receiver binding name. */
public interface DefaultMethodTarget {

    /** Returns a fixed value so captures can identify the bound receiver. */
    default int receiverValue() {
        return 7;
    }

    /** Returns one plus the low bit of {@code i}, keeping {@code _this} visible at the probe line. */
    default int visitDefault(int i) {
        final int _this = i & 1;
        return _this + 1; // MARK: default probe
    }
}
