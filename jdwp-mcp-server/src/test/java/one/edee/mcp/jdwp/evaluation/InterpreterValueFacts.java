package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** Stable result facts read directly through JDI without invoking target methods. */
record InterpreterValueFacts(String javaClass, @Nullable String scalar, @Nullable String reflectedType,
                             @Nullable ArrayContents array, List<String> fixtureIdentities) {
    private static final int MAX_ARRAY_ELEMENTS = 16;
    private static final int MAX_ARRAY_DEPTH = 2;
    private static final Map<String, String> PRIMITIVES = Map.of(
        "java.lang.Boolean", "boolean", "java.lang.Byte", "byte", "java.lang.Character", "char",
        "java.lang.Short", "short", "java.lang.Integer", "int", "java.lang.Long", "long",
        "java.lang.Float", "float", "java.lang.Double", "double");

    record ArrayContents(int length, List<InterpreterValueFacts> elements) {
    }

    static InterpreterValueFacts capture(@Nullable Value value, Map<String, ObjectReference> fixtureReferences,
                                         boolean normalizeCharArrayString) {
        return capture(value, fixtureReferences, normalizeCharArrayString, MAX_ARRAY_DEPTH);
    }

    private static InterpreterValueFacts capture(@Nullable Value value, Map<String, ObjectReference> fixtureReferences,
                                                 boolean normalizeCharArrayString, int remainingDepth) {
        final String type = value == null ? "null" : value.type().name();
        if (value instanceof StringReference string) {
            final String contents = string.value();
            return new InterpreterValueFacts(type, normalizeCharArrayString
                ? contents.replaceFirst("^\\[C@[0-9a-f]+$", "[C@<hash>") : contents, null, null, List.of());
        }
        if (value instanceof PrimitiveValue primitive) {
            return new InterpreterValueFacts(type, primitive.toString(), null, null, List.of());
        }
        if (value instanceof ObjectReference object) {
            final String primitiveType = PRIMITIVES.get(type);
            if (primitiveType != null) {
                final Value unboxed = object.getValue(object.referenceType().fieldByName("value"));
                return new InterpreterValueFacts(primitiveType, unboxed.toString(), null, null, List.of());
            }
            final List<String> identities = fixtureReferences.entrySet().stream()
                .filter(entry -> object.equals(entry.getValue())).map(Map.Entry::getKey).sorted().toList();
            if (object instanceof ClassObjectReference classObject) {
                return new InterpreterValueFacts(type, null, classObject.reflectedType().name(), null, identities);
            }
            if (object instanceof ArrayReference array) {
                final int length = array.length();
                final List<InterpreterValueFacts> elements = remainingDepth == 0 ? List.of()
                    : array.getValues(0, Math.min(length, MAX_ARRAY_ELEMENTS)).stream()
                        .map(element -> capture(element, fixtureReferences, false, remainingDepth - 1)).toList();
                return new InterpreterValueFacts(type, null, null, new ArrayContents(length, elements), identities);
            }
            return new InterpreterValueFacts(type, null, null, null, identities);
        }
        return new InterpreterValueFacts(type, null, null, null, List.of());
    }
}
