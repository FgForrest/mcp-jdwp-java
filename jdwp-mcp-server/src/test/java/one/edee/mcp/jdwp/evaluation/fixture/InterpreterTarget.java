package one.edee.mcp.jdwp.evaluation.fixture;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

public class InterpreterTarget extends InterpreterTargetBase {
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
