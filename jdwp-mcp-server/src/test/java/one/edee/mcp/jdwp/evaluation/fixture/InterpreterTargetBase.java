package one.edee.mcp.jdwp.evaluation.fixture;

/** Supplies inherited fields and a member type for navigation tests. */
public class InterpreterTargetBase {
    public int hidden = 10;
    public int inherited = 11;

    public String dispatch() {
        return "base";
    }
    /** A member type that shadows the bootstrap type in the subclass. */
    public static final class StringBuilder {}
}
