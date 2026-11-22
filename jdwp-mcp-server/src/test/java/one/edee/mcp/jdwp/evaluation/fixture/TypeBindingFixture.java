package one.edee.mcp.jdwp.evaluation.fixture;

/** Provides stopped frames where an ordinary type lookup could select a shadowed class. */
public final class TypeBindingFixture {
    /** Keeps the competing classes and the method-local class loaded. */
    static Object sink = new Object();

    /** This class supplies fixture stops only. */
    private TypeBindingFixture() {}

    /** Loads the competing types and visits each frame without loading its missing enclosing scopes. */
    static void run() {
        sink = new Object[]{new Marker(), new EnclosingOuter(), new EnclosingOuter.Shared(),
            new EnclosingBase.Shared(), new HiddenOuter.Scope.Token(), new Scope(), new Scope.Token()};
        HiddenOuter.Frame.inspect();
        EnclosingOuter.Middle.Frame.inspect();
        LocalFrame.inspect();
        GenericFrame.inspect(new GenericActual());
    }

    /** Stops after a local class has shadowed the package's marker. */
    public static final class LocalFrame {
        /** Loads the local class before the final line where the harness stops. */
        public static void inspect() {
            /** This declaration shadows the package's loaded marker. */
            class Marker {}
            final Object local = new Marker();
            sink = local;
        }
    }

    /** The actual bound of the method type parameter. */
    public static final class GenericActual extends Marker {
        /** Distinguishes reads through the correct cast type. */
        public int code = 62;
    }

    /** Stops in a method whose type parameter shadows the package's marker. */
    public static final class GenericFrame {
        /** Reads the field through the method's formal type parameter. */
        public static <Marker extends GenericActual> int inspect(Object value) {
            return ((Marker) value).code;
        }
    }
}

/** The package type shadowed by a local class and a method type parameter. */
class Marker {
    /** The field that would be selected by the incorrect cast type. */
    public int code = 61;
}

/** Provides the member inherited by the unloaded middle scope. */
class EnclosingBase {
    /** The member visible in the middle scope. */
    static class Shared {}
}

/** The loaded outer scope has a competing member of its own. */
class EnclosingOuter {
    /** The member hidden by the middle scope's inherited member. */
    static class Shared {}

    /** This scope remains unloaded when its nested frame stops. */
    static class Middle extends EnclosingBase {
        /** Stops within the lexical scope of its unloaded enclosing type. */
        static class Frame {
            /** Keeps javac's inherited binding in the fixture source. */
            static Class<?> inspect() {
                return Shared.class;
            }
        }
    }
}

/** Remains unloaded along with its Scope member while the nested frame stops. */
class HiddenOuter {
    /** The unloaded qualifier that shadows the package type. */
    static class Scope {
        /** This nested token is loaded independently of its enclosing types. */
        static class Token {}
    }

    /** Stops while the enclosing scope is still unavailable through JDI. */
    static class Frame {
        /** Provides a breakpoint location without accessing either enclosing type. */
        static void inspect() {
            TypeBindingFixture.sink = 0;
        }
    }
}

/** The package qualifier hidden by a member of the unloaded outer scope. */
class Scope {
    /** The incorrect token type is loaded and available to JDI. */
    static class Token {}
}
