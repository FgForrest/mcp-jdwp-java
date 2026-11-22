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

    /** A member type that is already loaded when the target stops for inspection. */
    public static final class HiddenBadge {
        /** Distinguishes this member from the subclass's member. */
        public static String tag = "base";
    }

    /** Loads only the base declaration during fixture startup. */
    private static final Object LOADED_HIDDEN_BADGE = new HiddenBadge();
}
