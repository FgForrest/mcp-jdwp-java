package one.edee.mcp.jdwp.evaluation.fixture;

/** Provides stopped frames where an ordinary type lookup could select a shadowed class. */
public final class TypeBindingFixture {
    /** Keeps the competing classes and the method-local class loaded. */
    static Object sink = new Object();

    /** This class supplies fixture stops only. */
    private TypeBindingFixture() {}

    /** Loads the competing types and visits the frames, including local classes opened reflectively. */
    static void run() throws ReflectiveOperationException {
        sink = new Object[]{new Marker(), new EnclosingOuter(), new EnclosingOuter.Shared(),
            new EnclosingBase.Shared(), new HiddenOuter.Scope.Token(), new Scope(), new Scope.Token()};
        HiddenOuter.Frame.inspect("fixture");
        EnclosingOuter.Middle.Frame.inspect();
        LocalFrame.inspect();
        GenericFrame.inspect(new GenericActual());
        sink = new Object[]{new java.lang.Integer[][]{{1}},
            new one.edee.mcp.jdwp.evaluation.fixture.shadow.Integer[][]{
                {new one.edee.mcp.jdwp.evaluation.fixture.shadow.Integer()}}, new SelfNameBase.SelfNameFrame()};
        TypeBindingArrayFrame.inspect();
        GenericOuter.enter();
        SelfNameFrame.inspect();
        final String detached = TypeBindingFixture.class.getPackageName() + ".DetachedOuter$";
        inspectDetachedFrame(detached + "1Frame", new DetachedOuter.Actual());
        final Object siblingMarker = Class.forName(detached + "1Marker").getDeclaredConstructor().newInstance();
        inspectDetachedFrame(detached + "1SiblingFrame", siblingMarker);
    }

    /** Instantiates the local class without calling the method in its enclosing class. */
    private static void inspectDetachedFrame(String binary, Object value) throws ReflectiveOperationException {
        final Class<?> frame = Class.forName(binary);
        frame.getDeclaredMethod("inspect", Object.class).invoke(frame.getDeclaredConstructor().newInstance(), value);
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
        static void inspect(Object x) {
            TypeBindingFixture.sink = x;
        }

        /** Computes javac's values for the fully qualified expressions in the unloaded scope. */
        static Object golden(String source, Object x) {
            return switch (source) {
                case "java.lang.String.class" -> java.lang.String.class;
                case "java.lang.Integer.MAX_VALUE" -> java.lang.Integer.MAX_VALUE;
                case "(java.lang.String) x" -> (java.lang.String) x;
                default -> throw new IllegalArgumentException(source);
            };
        }
    }
}

/** The package qualifier hidden by a member of the unloaded outer scope. */
class Scope {
    /** The incorrect token type is loaded and available to JDI. */
    static class Token {}
}

/** Supplies a local class whose enclosing method declares the type parameter. */
final class GenericOuter {
    /** The member type shadowed by the enclosing method's parameter. */
    static class Marker {
        /** Distinguishes the member's field from the field of the bound type. */
        public int code = 61;
    }

    /** The bound of the enclosing method's parameter. */
    static final class Actual extends Marker {
        /** The field selected through the enclosing method's parameter. */
        public int code = 62;
    }

    /** Enters the local frame with a value compatible with both casts. */
    static <Marker extends Actual> void enter() {
        /** Stops in a method with no formal type parameters of its own. */
        class Frame {
            /** Reads through the enclosing method's formal parameter. */
            int inspect(Object value) {
                return ((Marker) value).code;
            }
        }
        TypeBindingFixture.sink = new Frame().inspect(new Actual());
    }
}

/** Declares a member type with the same simple name as its subclass. */
class SelfNameBase {
    /** The type javac selects in the subclass's scope. */
    static final class SelfNameFrame {
        /** Makes the two bindings distinguishable by a static field read. */
        static final int CONSTANT = 92;
    }
}

/** Stops where an inherited member type shadows the frame's own name. */
final class SelfNameFrame extends SelfNameBase {
    /** The value a wrongly selected binding would read. */
    static final int CONSTANT = 91;

    /** Provides a breakpoint in the subclass's lexical scope. */
    static void inspect() {
        TypeBindingFixture.sink = SelfNameFrame.class;
    }

    /** Computes javac's values for expressions using the shadowed class name. */
    static Object golden(String source, Object unused) {
        return switch (source) {
            case "SelfNameFrame.class" -> SelfNameFrame.class;
            case "SelfNameFrame.CONSTANT" -> SelfNameFrame.CONSTANT;
            default -> throw new IllegalArgumentException(source);
        };
    }
}

/** Supplies local classes that can run before the class declaring their methods is loaded. */
final class DetachedOuter {
    /** The loaded member whose name is shadowed in the local frames. */
    static class Marker {
        /** The instance value an incorrect member binding would read. */
        public int code = 61;
        /** The static value an incorrect member binding would read. */
        public static final int CONSTANT = 71;
    }

    /** The bound of the enclosing method's type parameter. */
    static final class Actual extends Marker {
        /** The instance value selected through the type parameter. */
        public int code = 62;
        /** The static value selected through the type parameter. */
        public static final int CONSTANT = 72;
    }

    /** Declares a local frame where a formal type parameter shadows the loaded member. */
    static <Marker extends Actual> Object makeFrame() {
        /** Stops in the scope of the enclosing method's type parameter. */
        class Frame {
            /** Records the bindings javac gives to the casts in this frame. */
            int inspect(Object value) {
                return ((Marker) value).code + ((Marker) null).CONSTANT;
            }

            /** Supplies the javac value of the package-qualified control. */
            static Object golden(String source, Object unused) {
                if (source.equals("java.lang.Integer.MAX_VALUE")) {
                    return java.lang.Integer.MAX_VALUE;
                }
                throw new IllegalArgumentException(source);
            }
        }
        return new Frame();
    }

    /** Declares a local type that shadows the loaded member in its sibling frame. */
    static Object makeSiblingFrame() {
        /** The locally declared type visible to the sibling class. */
        class Marker extends DetachedOuter.Marker {
            /** Distinguishes a sibling-local read from a read through the loaded member. */
            public int code = 63;
        }
        /** Stops in the lexical scope shared with the local marker. */
        class SiblingFrame {
            /** Keeps the local marker out of this frame's constant pool. */
            void inspect(Object value) {
                TypeBindingFixture.sink = value;
            }
        }
        return new SiblingFrame();
    }
}
