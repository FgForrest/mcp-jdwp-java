package one.edee.mcp.jdwp.evaluation.fixture;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** Binary fixture for the compiler's private-member binding tests. */
public class PrivateMembers {
    /** Supplies a static compile-time constant for the rewrite. */
    private static final int TOTAL = 12;
    /** Supplies another receiver for a chain of private reads. */
    private final PrivateMembers next = this;
    /** Supplies the instance value read by the wrapper. */
    private final int amount;
    /** Supplies a receiver whose declared type is package-private. */
    private final HiddenMembers hidden = new HiddenMembers();
    /** Supplies the values used by the compiled loop. */
    private final java.util.List<PrivateMembers> entries = java.util.List.of(this);
    /** Records the order of receiver, argument, and method evaluation. */
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

    /** Supplies a target exception for both direct and reflective calls. */
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

    /** Supplies the less specific reference overload in the selection test. */
    private int select(Object argument) {
        return -1;
    }

    /** Supplies the most specific overload for a string argument. */
    private int select(String argument) {
        return argument.length();
    }

    /** Requires the same widening conversion as a direct Java call. */
    private int select(long argument) {
        return (int) argument;
    }

    /** Supplies javac observations for order, null checks, and accesses that the rewrite must refuse. */
    public static final class Semantics {
        /** Records the application events for the direct comparison. */
        private static String trace = "";
        /** Makes a field read depend on an inaccessible static type. */
        private final Hidden hidden = new Hidden();
        /** Supplies an array with an inaccessible component type. */
        private final Hidden[] hiddenArray = {hidden};
        /** Supplies a collection whose element type is inaccessible. */
        private final java.util.List<Hidden> hiddenList = java.util.List.of(hidden);
        /** Provides the member named in null-field diagnostics. */
        private int value = 10;
        /** Provides a null receiver through a public static field. */
        public static Semantics staticNull;
        /** Provides a null receiver through a public instance field. */
        public Semantics instanceNull;
        /** Provides a null receiver through a rewritten static field. */
        private static Semantics privateStaticNull;
        /** Provides a null receiver through a rewritten instance field. */
        private Semantics privateInstanceNull;
        /** Supplies the receiver chain for the guarded assignment cases. */
        private final HiddenChild child = new HiddenChild();
        /** Supplies a control receiver for an accessible assignment. */
        private final VisibleParent visibleChild = new VisibleParent();

        /** Owns the public field that must never substitute for the hidden field. */
        public static class VisibleParent {
            /** Makes accidental selection of the parent field observable. */
            public int shared = 11;
        }

        /** Supplies an inaccessible receiver type with a shadowing field. */
        private static final class HiddenChild extends VisibleParent {
            /** Holds the subclass value that is distinct from the inherited one. */
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
            /** Supplies the ordinary integer constant case. */
            private static final int CONSTANT = 42;
            /** Exercises string literal quoting and preservation of isolated UTF-16 code units. */
            private static final String TEXT = "quotes \"' and line\n\\u000a / \ud800";
            /** Exercises escaping of a character constant. */
            private static final char CHARACTER = '\n';
            /** Exposes whether the literal preserves the sign of zero. */
            private static final double NEGATIVE_ZERO = -0.0;
            /** Exercises emission of an infinite floating-point constant. */
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
            /** Supplies the field read through the receiver’s explicit base type. */
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
            /** Supplies the constant selected by a subclass-qualified expression. */
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
                // An empty implementation provides the receiver for the interface's private method.
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

    /** Exercises static types, discarded results, and receiver descriptions in the javac oracle. */
    public static final class AccessSites {
        /** Ordered application calls observed in one isolated loader. */
        private static String trace = "";
        /** Supplies both an assignment target and an array-typed overload argument. */
        private final int[] array = {11};
        /** Makes a private read part of an array assignment target. */
        private final int index = 0;
        /** Selects the boxed overload in the direct comparison. */
        private final Integer boxed = 4;
        /** Selects the primitive overload in the direct comparison. */
        private int primitive = 3;
        /** Provides the field named by null checks at distinct source sites. */
        private int value = 17;
        /** Supplies the inherited return type in the discarded-result cases. */
        public final GenericChild genericChild = new GenericChild();

        /** Records an application call in order. */
        public static void mark(String name) {
            trace += (trace.isEmpty() ? "" : " -> ") + name;
        }

        /** Returns the calls made by this isolated fixture. */
        public static String trace() {
            return trace;
        }

        /** Records the guard for the branch with the compile-only superclass. */
        public static boolean companionGuard() {
            CompanionTrackingLoader.record("guard");
            return false;
        }

        /** Records lambda execution after the skipped branch. */
        public static String companionLambda() {
            CompanionTrackingLoader.record("lambda");
            return "ok";
        }

        /** Supplies a fixed void target for a lambda argument. */
        public static void consume(Runnable call) {
            call.run();
        }

        /** An overload with a different arity cannot supply the target for a one-argument call. */
        public static String consume(Supplier<String> call, String suffix) {
            return call.get() + suffix;
        }

        /** Supplies the void-compatible overload. */
        public static String choose(Runnable call) {
            call.run();
            return "void";
        }

        /** Supplies the value-compatible overload. */
        public static String choose(Supplier<String> call) {
            return call.get();
        }

        /** Selects the first source site when two sites resolve to an inherited member. */
        public static boolean takeFirst() {
            mark("takeFirst");
            return true;
        }

        /** Records the overload selected by javac or ECJ. */
        public static String pick(Collection<String> value) {
            mark("pick(Collection)");
            return "collection";
        }

        /** Records the overload selected by javac or ECJ. */
        public static String pick(List<String> value) {
            mark("pick(List)");
            return value.get(0);
        }

        /** Records the overload selected by javac or ECJ. */
        public static String pick(int[] value) {
            mark("pick(int[])");
            return "array:" + value[0];
        }

        /** Records the overload selected by javac or ECJ. */
        public static String pick(Object value) {
            mark("pick(Object)");
            return "object";
        }

        /** Records the overload selected by javac or ECJ. */
        public static String pick(int value) {
            mark("pick(int)");
            return "int:" + value;
        }

        /** Records the overload selected by javac or ECJ. */
        public static String pick(Integer value) {
            mark("pick(Integer)");
            return "Integer:" + value;
        }

        /** Returns a parameterized type that the rewrite must preserve. */
        private List<String> generic() {
            mark("generic");
            return List.of("list");
        }

        /** Provides a generic declaration with a deliberately polluted erased return. */
        public static class GenericBase<T> {
            /** Returns an Integer through the erased generic declaration. */
            @SuppressWarnings("unchecked")
            protected T retrieve() {
                mark("retrieve");
                return (T) Integer.valueOf(2);
            }
        }

        /** Binds the inherited result type to String without introducing a bridge. */
        public static final class GenericChild extends GenericBase<String> {}

        /** Declares members inherited under two distinct source qualifiers. */
        public static class Base {
            /** Provides the null receiver shared by the two static field qualifiers. */
            protected static AccessSites empty;
            /** Returns a null result for an inherited static method. */
            protected static AccessSites noReceiver() {
                mark("noReceiver");
                return null;
            }
        }

        /** Provides the first symbolic owner of the inherited static members. */
        public static final class A extends Base {}
        /** Provides the second symbolic owner of the inherited static members. */
        public static final class B extends Base {}

        /** Supplies folded constants and records any class initialization. */
        public static class Cold {
            /** Supplies an integer folded inside a larger constant expression. */
            private static final int CONSTANT = 7 * 6;
            /** Supplies a string folded before the identity check. */
            private static final String STRING = "str" + "ing";
            static {
                mark("Cold.<clinit>");
            }
        }

        /** Supplies a constant whose enclosing type cannot be named. */
        private static final class HiddenCold {
            /** Supplies a type-qualified constant through an inaccessible owner. */
            public static final int CONSTANT = 8 * 6;
            static {
                mark("HiddenCold.<clinit>");
            }
        }

        /** Records superclass initialization and construction. */
        public static class Parent {
            static {
                mark("Parent.<clinit>");
            }

            /** Records the superclass constructor call. */
            public Parent() {
                mark("Parent.<init>");
            }
        }

        /** Records initialization of the class used as a constructor argument. */
        public static final class Inner {
            static {
                mark("Inner.<clinit>");
            }

            /** Records construction during argument evaluation. */
            private Inner(int n) {
                mark("Inner.<init>");
            }
        }

        /** Records initialization of the class being constructed first. */
        public static final class Outer extends Parent {
            static {
                mark("Outer.<clinit>");
            }

            /** Records the call after superclass construction and argument evaluation. */
            private Outer(Inner inner) {
                mark("Outer.<init>");
            }
        }

        /** Records argument evaluation and supplies a value. */
        public int argument() {
            mark("argument");
            return 5;
        }

        /** Records a private call that returns the current receiver. */
        private AccessSites chain() {
            mark("chain");
            return this;
        }

        /** Records a private call that returns null. */
        private AccessSites absent() {
            mark("absent");
            return null;
        }

        /** Returns an array containing a null receiver. */
        public AccessSites[] elements() {
            mark("elements");
            return new AccessSites[1];
        }

        /** Observes the identity of strings folded at compile time. */
        public static boolean constantIdentity(String a, String b) {
            mark("constantIdentity");
            return a == b;
        }

        /** Executes each supported expression directly with javac. */
        public static Object golden(String id, AccessSites x) {
            return switch (id) {
                case "array-target" -> {
                    x.array[0] = x.argument();
                    yield x.array[0];
                }
                case "index-target" -> {
                    int[] a = {7};
                    a[x.index] = x.argument();
                    yield a[0];
                }
                case "nested-target" -> {
                    ((x.array[0]))++;
                    yield x.array[0];
                }
                case "generic" -> pick(x.generic());
                case "array" -> pick(x.array);
                case "primitive" -> pick(x.primitive);
                case "boxed" -> pick(x.boxed);
                case "cold-hidden" -> HiddenCold.CONSTANT;
                case "constant-expression" -> x.pick(Cold.CONSTANT + 1);
                case "string" -> constantIdentity(Cold.STRING + "!", "string!");
                case "constructor" -> {
                    new Outer(new Inner(x.argument()));
                    yield "ok";
                }
                case "chained-null" -> x.chain().absent().value;
                case "array-null" -> x.elements()[0].value;
                case "discard-statement" -> {
                    x.genericChild.retrieve();
                    yield "ok";
                }
                case "discard-lambda" -> {
                    Runnable call = () -> x.genericChild.retrieve();
                    call.run();
                    yield "ok";
                }
                case "discard-argument" -> {
                    consume(() -> x.genericChild.retrieve());
                    yield "ok";
                }
                case "discard-cast" -> {
                    ((Runnable) () -> x.genericChild.retrieve()).run();
                    yield "ok";
                }
                // Both branches resolve to the same declaration but use distinct source qualifiers.
                case "method-description" -> {
                    try {
                        if (takeFirst()) yield A.noReceiver().value;
                        yield B.noReceiver().value;
                    } catch (NullPointerException e) {
                        mark("getMessage");
                        yield e.getMessage();
                    }
                }
                case "field-description" -> {
                    try {
                        if (takeFirst()) yield A.empty.value;
                        yield B.empty.value;
                    } catch (NullPointerException e) {
                        mark("getMessage");
                        yield e.getMessage();
                    }
                }
                default -> throw new IllegalArgumentException(id);
            };
        }
    }
}

/** A receiver whose type cannot be named from the generated wrapper's package. */
class HiddenMembers {
    /** Supplies a private value on a package-private receiver. */
    private final int hiddenAmount;

    /** Sets a nonconstant private field so the test exercises the reflective receiver binding. */
    HiddenMembers() {
        hiddenAmount = 41;
    }
}
