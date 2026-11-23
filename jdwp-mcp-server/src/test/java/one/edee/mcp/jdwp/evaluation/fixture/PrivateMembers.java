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

    /** Supplies javac observations for order, null checks, and accesses that the rewrite must refuse. */
    public static final class Semantics {
        private static String trace = "";
        private final Hidden hidden = new Hidden();
        private final Hidden[] hiddenArray = {hidden};
        private final java.util.List<Hidden> hiddenList = java.util.List.of(hidden);
        private int value = 10;
        public static Semantics staticNull;
        public Semantics instanceNull;
        private static Semantics privateStaticNull;
        private Semantics privateInstanceNull;
        private final HiddenChild child = new HiddenChild();
        private final VisibleParent visibleChild = new VisibleParent();

        /** Owns the public field that must never substitute for the hidden field. */
        public static class VisibleParent {
            public int shared = 11;
        }

        private static final class HiddenChild extends VisibleParent {
            private int shared = 22;
        }

        /** Returns separately observable values for the two shadowing fields. */
        public String written() {
            mark("written");
            return child.shared + "/" + ((VisibleParent) child).shared;
        }

        /** Records when initialization and construction happen. */
        public static final class ColdConstruct {
            static {
                mark("ColdConstruct.initialize");
            }
            /** Records when initialization and construction happen. */
            /** Records construction after the class and the argument are ready. */
            private ColdConstruct(Integer ignored) {
                mark("ColdConstruct.constructor");
            }
        }

        /** Clears the application trace for the next evaluation. */
        public static void reset() {
            trace = "";
        }
        /** Returns the application calls in order. */
        public static String trace() {
            return trace;
        }
        /** Records the evaluation of a branch that skips the optional class. */
        public static boolean guard() {
            mark("guard");
            return false;
        }
        /** Adds an application event to the trace. */
        public static void mark(String tag) {
            trace += tag + " ";
        }

        /** Makes the static type of a field or return value inaccessible to the wrapper. */
        private static final class Hidden {
            /** Records a method on a receiver whose type is hidden. */
            private int method() {
                mark("Hidden.method");
                return 7;
            }
        }

        /** Distinguishes the overload selected by the argument’s static type. */
        public static String choose(Object value) {
            mark("choose(Object)");
            return "object";
        }
        /** Distinguishes the overload selected by the argument’s static type. */
        public static String choose(Hidden value) {
            mark("choose(Hidden)");
            return "hidden";
        }

        /** Distinguishes overload resolution for primitive and reference arguments. */
        private int select(Object value) {
            mark("select(Object)");
            return 1;
        }
        /** Distinguishes overload resolution for primitive and reference arguments. */
        private int select(String value) {
            mark("select(String)");
            return 2;
        }
        /** Distinguishes overload resolution for primitive and reference arguments. */
        private int select(int value) {
            mark("select(int)");
            return 3;
        }
        /** Distinguishes overload resolution for primitive and reference arguments. */
        private int select(long value) {
            mark("select(long)");
            return 4;
        }

        /** Returns an inaccessible type. */
        private Hidden privateHidden() {
            mark("privateHidden");
            return hidden;
        }
        /** Exposes a value of an inaccessible type without requiring a reflective read. */
        public Hidden publicHidden() {
            mark("publicHidden");
            return hidden;
        }
        /** Distinguishes overloads that take accessible and inaccessible parameter types. */
        private int both(Object value) {
            mark("both(Object)");
            return 5;
        }
        /** Distinguishes overloads that take accessible and inaccessible parameter types. */
        private int both(Hidden value) {
            mark("both(Hidden)");
            return 6;
        }
        /** Returns the argument with its inferred static type. */
        private <T> T generic(T value) {
            mark("generic(Object)");
            return value;
        }
        /** Reports the runtime type of the array created for a generic varargs call. */
        private <T> String varargs(T... values) {
            mark("varargs(Object[])");
            return values.getClass().getName();
        }
        /** Distinguishes the static and instance overloads. */
        private static int kind(long value) {
            mark("kind(static long)");
            return 13;
        }
        /** Distinguishes the static and instance overloads. */
        private int kind(String value) {
            mark("kind(instance String)");
            return 17;
        }
        /** Records invocation after the receiver and both arguments have been evaluated. */
        private int order(int first, int second) {
            mark("order");
            return first + second;
        }
        /** Throws a target exception with a recognizable stack frame. */
        private int explode() {
            mark("explode");
            throw new IllegalArgumentException("deliberate failure");
        }
        /** Provides a side-effecting expression qualifier for a constant variable. */
        public Cold coldReceiver() {
            mark("qualifier");
            return null;
        }
        /** Observes javac’s helpful message for a null parameter. */
        public static int readNullParameter(Semantics x) {
            return x.value;
        }
        /** Records and returns a non-null receiver. */
        public Semantics get(String tag) {
            mark(tag);
            return this;
        }
        /** Records and returns a null receiver. */
        public Semantics nullReceiver() {
            mark("nullReceiver");
            return null;
        }
        /** Supplies a null receiver from a method that must itself be rewritten. */
        private Semantics privateNullReceiver() {
            mark("privateNullReceiver");
            return null;
        }

        /** Throws while evaluating the receiver. */
        public Semantics badReceiver() {
            mark("badReceiver");
            throw new IllegalStateException("receiver");
        }
        /** Records argument evaluation. */
        public Integer argument(String tag) {
            mark(tag);
            return 1;
        }
        /** Throws while evaluating the first argument. */
        public Integer badArgument() {
            mark("badArgument");
            throw new IllegalStateException("argument");
        }

        /** Provides a constant without requiring class initialization. */
        public static final class Cold {
            private static final int CONSTANT = 42;
            private static final String TEXT = "quotes \"' and line\n\\u000a / \ud800";
            private static final char CHARACTER = '\n';
            private static final double NEGATIVE_ZERO = -0.0;
            private static final float INFINITY = -1.0f / 0.0f;
            static {
                initialize();
            }
            /** Records initialization of the constant’s declaring class. */
            private static void initialize() {
                mark("Cold.initialize");
            }
        }

        /** Declares a private field hidden by a subclass declaration. */
        public static class Base {
            private final int shadowed;

            /** Initializes a nonconstant field to exercise a reflective read of the base declaration. */
            public Base() {
                shadowed = 12;
            }

            /** Supplies the base slot for a covariant override. */
            protected Number bridge() {
                mark("Base.bridge");
                return 12;
            }
        }

        /** Shadows the base field and emits a bridge for its covariant override. */
        public static final class Child extends Base {
            private final int shadowed = 30;
            /** Returns the covariant value even though javac also emits a bridge. */
            @Override protected Integer bridge() { mark("Child.bridge"); return 30; }
        }

        /** Declares a private interface method. */
        public interface Face {
            /** Exercises invocation of a private interface method. */
            private int own() {
                mark("Face.own");
                return 3;
            }
        }

        /** Executes the corresponding access directly in the nest that owns the private members. */
        public static Object golden(String id, Semantics x) {
            return switch (id) {
                case "widened-field" -> choose(x.hidden);
                case "widened-method" -> choose(x.privateHidden());
                case "widened-array" -> choose(x.hiddenArray[0]);
                case "widened-generic-field" -> choose(x.hiddenList.get(0));
                case "hidden-method" -> x.hidden.method();
                case "both-hidden" -> x.both(x.privateHidden());
                case "cold-constant" -> Cold.CONSTANT;
                case "string-constant" -> Cold.TEXT;
                case "char-constant" -> Cold.CHARACTER;
                case "zero-constant" -> Double.doubleToRawLongBits(Cold.NEGATIVE_ZERO);
                case "infinite-constant" -> Cold.INFINITY;
                case "instance-constant" -> new Child().shadowed;
                case "hidden-parameter" -> x.both(x.publicHidden());
                case "constructor-order" -> { new ColdConstruct(x.argument("argument")); yield 9; }
                case "write" -> { (x.child).shared = 99; yield x.written(); }
                case "compound-write" -> { (x.child).shared += 5; yield x.written(); }
                case "increment" -> { ++(x.child).shared; yield x.written(); }
                case "decrement" -> { (x.child).shared--; yield x.written(); }
                case "accessible-write" -> { (x.visibleChild).shared = 99; yield x.visibleChild.shared; }
                case "array-write" -> { x.hiddenArray[0] = null; yield 0; }
                case "expression-constant" -> x.coldReceiver().CONSTANT;
                case "null-local" -> { final Semantics receiver = x.nullReceiver(); yield receiver.value; }
                case "null-static-field" -> Semantics.staticNull.value;
                case "null-private-static-field" -> Semantics.privateStaticNull.value;
                case "null-private-instance-field" -> x.privateInstanceNull.value;
                case "null-private-method-result" -> x.privateNullReceiver().value;
                case "null-instance-field" -> x.instanceNull.value;
                case "null-parameter" -> readNullParameter(null);
                case "qualified-write" -> { x.child.shared = 99; yield x.written(); }
                case "non-private" -> x.get("visible").argument("after");
                case "null-method" -> x.nullReceiver().order(x.argument("first"), x.argument("second"));
                case "null-field" -> x.nullReceiver().value;
                case "null-caught" -> {
                    try {
                        yield x.nullReceiver().order(x.argument("first"), x.argument("second"));
                    } catch (NullPointerException expected) {
                        mark("catch(NullPointerException)");
                        yield expected.getMessage();
                    }
                }
                case "bad-receiver" -> x.badReceiver().order(x.argument("first"), x.argument("second"));
                case "bad-argument" -> x.get("receiver").order(x.badArgument(), x.argument("second"));
                case "throws" -> x.explode();
                case "box-reference" -> x.select(Integer.valueOf(4));
                case "box-primitive" -> x.select(4);
                case "specific" -> x.select("s");
                case "static-type" -> Semantics.kind(x.argument("argument"));
                case "static-null-qualifier" -> x.nullReceiver().kind(x.argument("argument"));
                case "instance-overload" -> x.get("receiver").kind("string");
                case "varargs" -> x.varargs("a", "b");
                case "generic" -> x.generic("g").length();
                case "shadow" -> ((Base) new Child()).shadowed;
                case "bridge" -> new Child().bridge();
                case "interface" -> ((Face) new Face() {}).own();
                case "lazy" -> false && x.get("receiver").value > 0;
                case "nested-lambda" -> ((java.util.function.Supplier<java.util.function.Supplier<Integer>>) () -> () -> x.get("receiver").select("s")).get().get();
                case "comments-unicode-text-block" -> x /* preserve */ . s\u0065lect("""
                    s
                    """);
                default -> throw new AssertionError(id);
            };
        }
    }
}

/** A receiver whose type cannot be named from the generated wrapper's package. */
class HiddenMembers {
    private final int hiddenAmount;

    /** Sets a nonconstant private field so the test exercises the reflective receiver binding. */
    HiddenMembers() {
        hiddenAmount = 41;
    }
}
