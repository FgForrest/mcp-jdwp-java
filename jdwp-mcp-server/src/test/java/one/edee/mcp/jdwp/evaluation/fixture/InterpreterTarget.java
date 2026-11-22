package one.edee.mcp.jdwp.evaluation.fixture;

import one.edee.mcp.jdwp.evaluation.fixture.shadow.ProcessBuilder;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/** Supplies stopped frames and javac-compiled observations for the live harness. */
public class InterpreterTarget extends InterpreterTargetBase {
    /** Keeps the explicitly imported type loaded and referenced in this class's constant pool. */
    private static final ProcessBuilder IMPORTED_PROCESS_BUILDER = new ProcessBuilder();
    /** Makes the competing bootstrap type visible from this fixture's loader. */
    private static final Object BOOTSTRAP_PROCESS_BUILDER = new java.lang.ProcessBuilder();
    /** Loads the inherited member type before inspection. */
    private static final StringBuilder INHERITED_STRING_BUILDER = new StringBuilder();
    /** Makes the competing bootstrap StringBuilder visible from this fixture's loader. */
    private static final Object BOOTSTRAP_STRING_BUILDER = new java.lang.StringBuilder();
    /** Loads the qualifier and its inherited member without touching the unloaded qualifier. */
    private static final Object[] LOADED_SCOPE = {new LoadedScope(), new LoadedScope.Badge()};
    /** Links the imported Map's member type through this fixture's class loader. */
    private static final String LOADED_MAP_ENTRY_KEY = Map.entry("key", "value").getKey();
    /** A target string whose isolated UTF-16 surrogate must survive intrinsic evaluation. */
    public final String malformed = "\uD800";

    public static int total = 9;
    public static int calls;
    public static String trace = "";
    public static int initializations;

    public int hidden = 20;
    private final Store store = new Store();
    private final Holder<InterpreterTargetBase> holder = new Holder<>(new InterpreterTargetBase());
    private final int secret = 21;

    public static void main(String[] args) throws Exception {
        final var target = new InterpreterTarget();
        final var packageStop = new PackageStop();
        final var inner = target.new Inner();
        final String text = "abc";
        final int n = 2;
        final IntSupplier lambda = () -> n + 3;
        final IntSupplier anonymous = new IntSupplier() {
            @Override
            public int getAsInt() {
                return 6;
            }
        };
        // Prepare the class without initializing it. Resolve its field signatures while it is cold.
        final Class<?> prepared = Class.forName(Cold.class.getName(), false, InterpreterTarget.class.getClassLoader());
        prepared.getDeclaredFields();
        target.inspect(n, Integer.MAX_VALUE, Integer.valueOf(1000), Integer.valueOf(1000), null, text, text,
            new int[]{8, 13, 21}, new char[]{'a', 'b'}, (byte) 2, 1e23,
            target, State.READY, Map.of("key", new Session()), lambda, anonymous, new NullString());
        packageStop.inspect(new PackageStop());
        inner.inspect();
        target.throwAtStop();
        target.changeAtStop();
        // Keep the connection available until the debugger has read the last event.
        System.in.read();
    }

    void inspect(int n, int max, Integer boxed, Integer otherBoxed, @Nullable Integer boxedNull, String text,
                 Object value, int[] items, char[] chars, byte small, double decimal, InterpreterTargetBase base,
                 State state, Map<String, Session> sessions, IntSupplier lambda, IntSupplier anonymous,
                 NullString nullString) {
        // The breakpoint is the method's first instruction, with all parameters in scope.
    }

    void throwAtStop() {
        try {
            throw new IllegalArgumentException("fixture exception");
        } catch (IllegalArgumentException expected) {
            // There is one caught exception event at this stop.
        }
    }

    void changeAtStop() {
        hidden = 25;
    }

    private int privateMethod() {
        return secret + 1;
    }

    public Integer choose(Integer value) {
        return value;
    }

    public String dispatch() {
        return "child";
    }

    public static String overload(Object value) {
        return "object";
    }

    public static String overload(String value) {
        return "string";
    }

    public static String widen(long value) {
        return "long";
    }

    public static String widen(Integer value) {
        return "Integer";
    }

    public static String arity(int... values) {
        return "varargs:" + values.length;
    }

    public static int apply(IntUnaryOperator operator, int value) {
        return operator.applyAsInt(value);
    }

    public static int step(String tag, int value) {
        calls++;
        trace += tag;
        return value;
    }

    public static InterpreterTarget receiver() {
        step("R", 0);
        return new InterpreterTarget();
    }

    public int add(int first, int second) {
        return step("M", first + second);
    }

    public int inspectInner() {
        new Inner().inspect();
        return step("I", 1);
    }

    /** javac oracle for expressions the separately compiled evaluation wrapper cannot see. */
    public Object golden(String id) {
        return switch (id) {
            case "private-field" -> this.secret;
            case "classpath-import-shadow" -> ProcessBuilder.class;
            case "inherited-type-shadow" -> StringBuilder.class;
            case "unloaded-hiding-member" -> HiddenBadge.tag;
            case "loaded-qualified-member" -> LoadedScope.Badge.class;
            case "qualified-member-cast" -> (LoadedScope.Badge) LOADED_SCOPE[1];
            case "qualified-member-field" -> LoadedScope.Badge.CODE;
            case "imported-qualified-member" -> Map.Entry.class;
            case "super-field" -> super.hidden;
            case "erased-field-navigation" -> holder.element.inherited;
            case "nested-class-literal" -> State.class;
            case "private-map" -> store.map;
            case "private-map-call" -> store.map.size();
            case "private-session" -> store.map.get("key").role;
            case "private-method" -> privateMethod();
            case "super-call" -> super.dispatch();
            default -> throw new IllegalArgumentException(id);
        };
    }

    /** Hides the base class's member and stays unloaded until a debugger expression reads it. */
    public static final class HiddenBadge {
        /** Distinguishes this member from the loaded base class's member. */
        public static String tag = "sub";
    }

    /** Declares the member inherited by the unloaded qualifier. */
    public static class AlternateScope {
        /** The member reached through the target's unloaded qualifier. */
        public static final class Badge {}
    }

    /** Hides the base qualifier and stays unloaded during the test. */
    public static final class Scope extends AlternateScope {}

    /** Declares the member inherited by the loaded qualifier. */
    public static class LoadedAlternateScope {
        /** The member reached through the target's loaded qualifier. */
        public static final class Badge {
            /** Exercises a static field reached through a qualified type name. */
            public static final int CODE = 42;
        }
    }

    /** Hides the base qualifier while inheriting its badge from a different type. */
    public static final class LoadedScope extends LoadedAlternateScope {}

    public static String genericGolden(Map<String, Session> sessions) {
        return sessions.get("key").getRole();
    }

    public static String exceptionGolden(Throwable exception) {
        // This fixture throws IllegalArgumentException, which inherits this accessor unchanged.
        return exception.getMessage();
    }

    private static final class Holder<T> {
        private final T element;

        Holder(T element) {
            this.element = element;
        }
    }

    private static final class Store {
        private final Map<String, Session> map = new HashMap<>(Map.of("key", new Session()));
    }

    public static final class Session {
        private final String role = "reader";

        public String getRole() {
            return role;
        }
    }

    public final class Inner {
        void inspect() {
        }

        public int goldenOuter() {
            return InterpreterTarget.this.secret;
        }
    }

    public enum State {
        READY, DONE
    }

    public interface StaticMethods {
        static int twice(int value) {
            return value * 2;
        }
    }

    public static final class Cold {
        public static final int CONSTANT = 7;
        public static int initialized = ++initializations;
    }

    public static final class NullString {
        @Override
        public @Nullable String toString() {
            return null;
        }
    }
}

final class PackageStop {
    final int code = 33;

    void inspect(PackageStop peer) {
    }

    int getCode() {
        return code;
    }
}
