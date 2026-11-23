package one.edee.mcp.jdwp.evaluation.fixture;

/** Supplies inherited fields and a member type for navigation tests. */
public class InterpreterTargetBase {
    public int hidden = 10;
    public int inherited = 11;

    /** Records explicit dispatch to the superclass implementation. */
    public String dispatch() {
        InterpreterTarget.step("D", 0);
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

    /** The hidden qualifier used by the unloaded-qualifier case. */
    public static final class Scope {
        /** The member the interpreter must avoid when the target's qualifier is unloaded. */
        public static final class Badge {}
    }

    /** The hidden qualifier used by the loaded-qualifier case. */
    public static final class LoadedScope {
        /** The member hidden by the target qualifier's inherited badge. */
        public static final class Badge {}
    }

    /** Loads the hidden members without loading either of the target's qualifiers. */
    private static final Object[] LOADED_SCOPE_BADGES = {new Scope.Badge(), new LoadedScope.Badge()};
}
