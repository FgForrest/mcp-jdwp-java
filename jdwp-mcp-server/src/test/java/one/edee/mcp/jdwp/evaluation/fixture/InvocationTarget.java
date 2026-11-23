package one.edee.mcp.jdwp.evaluation.fixture;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;

/** Supplies Java call semantics and a controlled result handoff to the live interpreter tests. */
public final class InvocationTarget extends InvocationParent {
    /** Value tested by the pattern scrutinee. */
    public static Object slot;
    /** Tracks the pattern object after its source is cleared. */
    private static WeakReference<Object> weak;
    /** Tracks a fresh return without retaining it. */
    private static volatile WeakReference<Payload> freshWeak;
    /** Decoy for out-of-scope simple-name lookup. */
    public static Payload shadow = new DecoyPayload();
    /** Explicit upper capture bound. */
    public static Box<? extends Service> source = new Box<Service>(new ServiceImpl());
    /** Capture bound inherited from the formal declaration. */
    public static BoundedBox<?> unbounded = new BoundedBox<Service>(new ServiceImpl());
    /** Lower-bounded wildcard with a narrower declared upper bound than Object. */
    public static BoundedBox<? super ServiceImpl> lower = new BoundedBox<Service>(new ServiceImpl());
    /** Interned String constant used by the identity oracle. */
    public static final String TOKEN = "token";
    /** Final String field initialized at runtime. */
    public static final String DYNAMIC = new String(TOKEN);
    /** Selects the constant branch of a nonconstant conditional. */
    public static boolean yes = true;
    /** Supplies a value beyond its visible static type. */
    public static Box<?> erased = new Box<>(new Payload());
    /** Successive calls substitute nested type arguments. */
    public static Box<Box<Service>> nested = new Box<>(new Box<>(new Service()));
    /** Receiver with a hidden method and an inherited overload case. */
    public static ChoiceChild choice = new ChoiceChild();
    /** Receiver whose Java member comes from an interface default. */
    public static DefaultChild defaultChild = new DefaultChild();
    /** Receiver with a generated generic bridge. */
    public static BridgeChild bridge = new BridgeChild();
    /** Interface-typed receiver for default and Object methods. */
    public static DefaultApi api = new InvocationTarget();
    /** Null boxed input for argument-order checks. */
    public static Integer missing;
    /** Double operand for target-version formatting. */
    public static double decimal = 1e23;
    /** Float operand for target-version formatting. */
    public static float fraction = 0.1f;

    /** Starts the collection worker and enters the event-thread fixture stop. */
    public static void main(String[] args) throws Exception {
        new OnlyConstructor(0);
        new NullString();
        new StringInput("ready");
        final Path result = Path.of(args[0]);
        final Thread collector = new Thread(() -> {
            try {
                System.in.read();
                for (int attempt = 0; attempt < 32; attempt++) {
                    System.gc();
                }
                Files.writeString(result, freshWeak.get() == null ? "collected" : "retained");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }, "fixture-gc");
        collector.setDaemon(true);
        collector.start();
        new InvocationTarget().invocationStop();
        collector.join();
        Thread.sleep(60000);
    }

    /** Provides a breakpoint before any scripted evaluation runs. */
    public void invocationStop() {}

    /** Resets the fixture before an independent oracle or candidate run. */
    public static void reset() {
        slot = new Payload();
        weak = new WeakReference<>(slot);
        trace = "";
        calls = 0;
    }

    /** Records a Java-level operation for differential comparison. */
    private static void record(String method) {
        step(method + ">", 0);
    }

    /** Drops the source field and forces collection while a pattern variable holds its value. */
    public static boolean clearAndCollect() {
        record("clearAndCollect");
        slot = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            System.gc();
        }
        record(weak.get() == null ? "collected" : "retained");
        return true;
    }

    /** Observes a String passed into a constructor. */
    public static final class StringInput {
        /** First UTF-16 code unit received by the constructor. */
        public final int code;
        /** Whether the argument was the target constant. */
        public final boolean same;

        /** Captures the constructor argument as Java sees it. */
        public StringInput(String value) {
            record("StringInput");
            code = value.charAt(0);
            same = value == TOKEN;
        }
    }

    /** Value used for call-result and pattern tests. */
    public static class Payload {
        /** Field read in a generic result chain. */
        public boolean flag = true;
        /** Records a call on the correct payload. */
        public boolean mark() {
            record("Payload.mark");
            return true;
        }
    }

    /** Returns a payload whose only application reference is weak. */
    public static Payload fresh() {
        record("fresh");
        final Payload payload = new Payload();
        freshWeak = new WeakReference<>(payload);
        return payload;
    }

    /** Provides an observable wrong receiver for a shadowed name. */
    public static final class DecoyPayload extends Payload {
        /** Records dispatch to this implementation. */
        @Override
        public boolean mark() {
            record("DecoyPayload.mark");
            return false;
        }
    }

    /** Tests the identity observed by Java code receiving a String. */
    public static boolean sameToken(String value) {
        record("sameToken");
        return value == "token";
    }

    /** Container with a readable generic result. */
    public static class Box<T> {
        /** Stored value returned by the getter. */
        private final T content;

        /** Captures the initial content. */
        public Box(T content) {
            this.content = content;
        }

        /** Returns a generic value and records the getter call. */
        public T get() {
            record("Box.get");
            return content;
        }
    }

    /** Inherits its getter through a type parameter with a tighter bound. */
    public static final class BoundedBox<T extends Service> extends Box<T> {
        /** Forwards the value to the generic superclass. */
        public BoundedBox(T content) {
            super(content);
        }
    }

    /** Declares the only method on the wildcard upper bound. */
    public static class Service {
        /** Records use of the base implementation. */
        public String ping() {
            record("Service.ping");
            return "service";
        }
    }

    /** Adds a runtime overload outside the wildcard upper bound. */
    public static final class ServiceImpl extends Service {
        /** Records dispatch to this implementation. */
        @Override
        public String ping() {
            record("ServiceImpl.ping");
            return "impl";
        }

        /** Overload visible only on the implementation class. */
        public String ping(int value) {
            record("ServiceImpl.ping(int)");
            return "overload";
        }
    }

    /** Supplies a private superclass declaration. */
    public static class ChoiceParent {
        /** Would win erroneously if private methods were included with Java members. */
        private String pick(int first) {
            record("ChoiceParent.pick(int)");
            return "private fixed arity";
        }
    }

    /** Declares a normal member with the same name as the hidden superclass method. */
    public static final class ChoiceChild extends ChoiceParent {
        /** Records dispatch through the public varargs member. */
        public String pick(int first, int... rest) {
            record("ChoiceChild.pick(int,int...)");
            return "public varargs";
        }
    }

    /** Contributes a private name that the child does not inherit. */
    public static class PrivateDefaultParent {
        /** Records the wrong implementation if private access displaces the default. */
        private String sample() {
            record("PrivateDefaultParent.sample");
            return "private";
        }
    }

    /** Declares the default method inherited by the child. */
    public interface SampleApi {
        /** Records the Java member selected on the child receiver. */
        default String sample() {
            record("SampleApi.sample");
            return "default";
        }
    }

    /** Has an inherited default and an inaccessible superclass method of the same name. */
    public static final class DefaultChild extends PrivateDefaultParent implements SampleApi {}

    /** Declares a method whose erased parameter needs a bridge after specialization. */
    public static class BridgeBase<T> {
        /** Records invocation of the unspecialized implementation. */
        public String choose(T value) {
            record("BridgeBase.choose");
            return "bridge base";
        }
    }

    /** Specializes the inherited parameter and adds a distinct arity. */
    public static final class BridgeChild extends BridgeBase<String> {
        /** Records dispatch to this implementation. */
        @Override
        public String choose(String value) {
            record("BridgeChild.choose");
            return "bridge child";
        }

        /** Records an unrelated overload that must not obscure the bridge. */
        public String choose(String value, int extra) {
            record("BridgeChild.choose(String,int)");
            return "other arity";
        }
    }

    /** Provides a toString result with an unpaired surrogate. */
    public static class IsolatedText {
        /** Records dispatch to this implementation. */
        @Override
        public String toString() {
            record("IsolatedText.toString");
            return "\uD800";
        }
    }

    /** Supplies an object operand whose toString is observable. */
    public static Object isolatedText() {
        record("isolatedText");
        return new IsolatedText();
    }

    /** Returns malformed UTF-16 as a target String for invocation-time intrinsic checks. */
    public static String isolatedString() {
        record("isolatedString");
        return "\uD800";
    }

    /** Returns a surrogate as a primitive so the target can check the concatenation. */
    public static char isolatedChar() {
        record("isolatedChar");
        return '\uD800';
    }

    /** Measures the code unit that reached target code. */
    public static int codeUnit(String value) {
        record("codeUnit");
        return value.charAt(0);
    }

    /** Records the body if argument conversion completes. */
    public static int take(long first, int second) {
        record("take");
        return (int) first + second;
    }

    /** Records an argument evaluated after another argument. */
    public static int later() {
        record("later");
        return 3;
    }

    /** Throws a target exception after recording its invocation. */
    public static int explode() {
        record("explode");
        throw new IllegalStateException("fixture target exception");
    }

    /** Records an unqualified static call. */
    public static String ownString() {
        record("ownString");
        return "invocation target";
    }

    /** Records dispatch to this implementation. */
    @Override
    public String toString() {
        record("InvocationTarget.toString");
        return "this target";
    }

    /** Evaluates each equivalent expression as ordinary javac-compiled code. */
    public Object invocationGolden(String id) {
        return switch (id) {
            case "result-gc" -> fresh().mark();
            case "pattern-pin" -> slot instanceof Payload p && clearAndCollect() && p.mark();
            case "pattern-shadow" -> !(slot instanceof Payload shadow) || shadow.mark();
            case "literal-identity" -> sameToken("token");
            case "constant-concat-identity" -> sameToken("to" + "ken");
            case "wildcard-overload" -> source.get().ping();
            case "wildcard-unbounded" -> unbounded.get().ping();
            case "wildcard-lower" -> lower.get().ping();
            case "constant-field" -> sameToken(TOKEN);
            case "dynamic-final-field" -> sameToken(DYNAMIC);
            case "conditional-token" -> sameToken(yes ? (String) "token" : DYNAMIC);
            case "safe-integer-concat" -> codeUnit("" + later());
            case "constant-receiver" -> sameToken(("to" + "ken").toString());
            case "pattern-false" -> slot instanceof Payload shadow ? true : InvocationTarget.shadow.mark();
            case "surrogate-constructor" -> new StringInput("" + isolatedChar()).code;
            case "constant-constructor" -> new StringInput("to" + "ken").same;
            case "erased-field" -> ((Payload) erased.get()).flag;
            case "erased-call" -> ((Payload) erased.get()).mark();
            case "private-varargs" -> choice.pick(1);
            case "nested-generics" -> nested.get().get().ping();
            case "private-default" -> ((SampleApi) defaultChild).sample();
            case "unqualified-static" -> ownString();
            case "generic-bridge" -> bridge.choose("arg");
            case "super-default" -> super.inheritedDefault();
            case "interface-default" -> api.inheritedDefault();
            case "interface-object" -> api.toString();
            case "surrogate-object" -> codeUnit(isolatedText() + "");
            case "surrogate-char" -> codeUnit("" + isolatedChar());
            case "surrogate-string-intrinsic" -> isolatedString().length();
            case "surrogate-folded-char" -> codeUnit("" + (char) 0xD800);
            case "float-double" -> "" + fraction + "/" + decimal;
            case "constructor" -> new OnlyConstructor(later()).number;
            case "unboxing-null" -> take(missing, later());
            case "target-exception" -> explode();
            case "cold-null-object" -> new NullString() + ownString();
            case "boxing-cache" -> choose(127) == choose(127) && choose(128) != choose(128);
            default -> throw new IllegalArgumentException(id);
        };
    }
}

/** Inherits an interface default through the superclass path. */
class InvocationParent extends InterpreterTarget implements DefaultApi {}

/** Defines the default called through interface and super receivers. */
interface DefaultApi {
    /** Records entry into the inherited default. */
    default String inheritedDefault() {
        InterpreterTarget.step("DefaultApi.inheritedDefault>", 0);
        return "default";
    }
}
