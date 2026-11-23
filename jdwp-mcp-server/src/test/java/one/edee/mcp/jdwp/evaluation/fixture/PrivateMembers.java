package one.edee.mcp.jdwp.evaluation.fixture;

import java.io.IOException;

/** Binary fixture for the compiler's private-member binding tests. */
public class PrivateMembers {
    private static final int TOTAL = 12;
    private final PrivateMembers next = this;
    private final int amount;
    private final HiddenMembers hidden = new HiddenMembers();
    private final java.util.List<PrivateMembers> entries = java.util.List.of(this);
    private static String trace = "";

    /** Public path used by the wrapper to construct a receiver. */
    public PrivateMembers() {
        this(7);
    }

    /** Private constructor used by the wrapper. */
    private PrivateMembers(int amount) {
        this.amount = amount;
    }

    /** Calls the same throwing method through an accessible entry. */
    public void throwDirectly() {
        fail();
    }

    /** Throws a target exception through reflection. */
    private void fail() {
        throw new IllegalStateException("fixture failure");
    }

    /** Throws the same exception type that reflection uses to report a closed module. */
    private void failInaccessible() {
        throw new java.lang.reflect.InaccessibleObjectException("application failure");
    }

    /** Supplies an instance whose declared type is package-private. */
    public static HiddenMembers hidden() {
        return new HiddenMembers();
    }

    /** Clears the evaluation trace and supplies the receiver. */
    public static PrivateMembers receiver() {
        trace = "R";
        return new PrivateMembers();
    }

    /** Records evaluation of a qualifier or argument. */
    public static int argument(String tag) {
        trace += tag;
        return 1;
    }

    /** Supplies the current trace so order can be asserted on the target. */
    public static String trace() {
        return trace;
    }

    /** Records invocation after receiver and arguments have run. */
    private int ordered(long first, long second) {
        trace += "M";
        return (int) (first + second);
    }

    /** Throws a checked exception declared by the selected method. */
    private void checked() throws IOException {
        throw new IOException("checked fixture failure");
    }

    /** An applicable but less specific overload for a String argument. */
    private int select(Object argument) {
        return -1;
    }

    /** Distinguishes this overload from the primitive overload. */
    private int select(String argument) {
        return argument.length();
    }

    /** Requires the same widening conversion as a direct Java call. */
    private int select(long argument) {
        return (int) argument;
    }
}

/** A receiver whose type cannot be named from the generated wrapper's package. */
class HiddenMembers {
    private final int hiddenAmount = 41;
}
