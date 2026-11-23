package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Type;
import org.eclipse.jdt.internal.compiler.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static one.edee.mcp.jdwp.evaluation.interpret.JdiTypes.StaticType;

/** Substitutes JVM generic signatures with the arguments of a statically typed receiver. */
final class GenericSignatures {
    /** This class only interprets signature metadata. */
    private GenericSignatures() {}

    /** The parameter and result type signatures of a non-generic method. */
    record MethodTypes(List<String> parameters, String result) {}

    /** Splits a non-generic JVM method signature into its component types. */
    static MethodTypes methodTypes(String signature) {
        final int close = signature.indexOf(')');
        if (!signature.startsWith("(") || close < 0) {
            throw Planner.compile("cannot read generic method signature");
        }
        final List<String> parameters = sequence(signature.substring(1, close), 0);
        final int end = typeEnd(signature, close + 1);
        return new MethodTypes(parameters, signature.substring(close + 1, end));
    }

    /** Returns the erased parameter descriptor after substituting the source receiver's type arguments. */
    static String parameterDescriptor(JdiTypes types, Method method, StaticType receiver)
        throws ClassNotLoadedException {
        final String generic = method.genericSignature();
        if (generic == null || generic.startsWith("<")) {
            final String erased = method.signature();
            return erased.substring(0, erased.indexOf(')') + 1);
        }
        final MethodTypes signature = methodTypes(generic);
        final List<Type> erased = method.argumentTypes();
        final StringBuilder descriptor = new StringBuilder("(");
        for (int index = 0; index < erased.size(); index++) {
            final Type parameter = erased.get(index);
            final StaticType resolved = memberType(types, parameter, signature.parameters().get(index),
                receiver, method.declaringType());
            final ReferenceType reference = resolved.reference();
            descriptor.append(reference == null ? parameter.signature() : reference.signature());
        }
        return descriptor.append(')').toString();
    }

    /** Returns the readable type of a result, applying capture upper bounds to wildcards. */
    static StaticType resultType(JdiTypes types, Type erased, @Nullable String signature,
                                 @Nullable StaticType receiver, ReferenceType owner) {
        return memberType(types, erased, signature, receiver, owner, true);
    }

    /** Returns the input type of a member, keeping unknown wildcard captures non-assignable. */
    static StaticType memberType(JdiTypes types, Type erased, @Nullable String signature,
                                 @Nullable StaticType receiver, ReferenceType owner) {
        return memberType(types, erased, signature, receiver, owner, false);
    }

    /** Returns the static member type after substituting receiver arguments through the inheritance path. */
    private static StaticType memberType(JdiTypes types, Type erased, @Nullable String signature,
                                         @Nullable StaticType receiver, ReferenceType owner, boolean upperBounds) {
        if (signature == null || !(erased instanceof ReferenceType erasedReference)) {
            return JdiTypes.of(erased, null);
        }
        final @Nullable JdiTypes captureTypes = upperBounds ? types : null;
        final Map<String, String> bindings = receiver == null || receiver.reference() == null
            ? Map.of() : bindings(receiver.reference(), receiver.genericSignature(), owner, new HashSet<>(),
                captureTypes);
        final String substituted = substitute(signature, bindings);
        // Keep variance in nested type arguments. Only a value read from a capture has
        // the capture's upper bound as its member-lookup type.
        final boolean captured = upperBounds && substituted.startsWith("+");
        final String resolved = captured ? substituted.substring(1) : substituted;
        if (resolved.startsWith("T") || resolved.startsWith("+") || resolved.startsWith("-") || resolved.equals("*")) {
            return new StaticType(null, erasedReference, true);
        }
        if (resolved.equals(signature) && !resolved.startsWith("[")) {
            return JdiTypes.of(erased, resolved, captured);
        }
        final String binary = binaryName(resolved);
        if (binary.equals(erased.name())) {
            return JdiTypes.of(erased, resolved, captured);
        }
        if (binary.isEmpty()) {
            return new StaticType(null, erasedReference, true);
        }
        final ReferenceType precise = types.signatureType(binary, owner);
        if (precise == null) {
            throw Planner.compile("type is not loaded: " + binary);
        }
        return JdiTypes.of(precise, resolved, captured);
    }

    /** Checks whether widening to a parameterized formal can be proven from declaration signatures. */
    static boolean acceptsArguments(StaticType from, StaticType to) {
        final String target = to.genericSignature();
        if (target == null || !target.contains("<")) {
            return true;
        }
        final ReferenceType sourceType = from.reference();
        final ReferenceType targetType = to.reference();
        if (sourceType == null || targetType == null) {
            return false;
        }
        // Array and parameterized member-class signatures need enclosing arguments as well.
        if (target.startsWith("[") || target.indexOf('.') >= 0) {
            return target.equals(from.genericSignature());
        }
        final List<String> expected = arguments(target);
        boolean unbounded = true;
        for (String argument : expected) {
            unbounded &= argument.equals("*");
        }
        if (unbounded) {
            return true;
        }
        final Map<String, String> actual = bindings(sourceType, from.genericSignature(), targetType, new HashSet<>(), null);
        final List<String> names = formals(targetType.genericSignature());
        if (names.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < names.size(); index++) {
            if (!expected.get(index).equals(actual.get(names.get(index)))) {
                return false;
            }
        }
        return true;
    }

    /** Projects a receiver signature onto a superclass or superinterface. */
    private static Map<String, String> bindings(ReferenceType type, @Nullable String use, ReferenceType owner,
                                                Set<ReferenceType> path, @Nullable JdiTypes captureTypes) {
        if (!path.add(type)) {
            return Map.of();
        }
        final String declaration = type.genericSignature();
        final List<Formal> formals = readFormals(declaration);
        final List<String> values = use == null ? List.of() : arguments(use);
        final Map<String, String> own = new HashMap<>();
        if (formals.size() == values.size()) {
            for (int index = 0; index < formals.size(); index++) {
                own.put(formals.get(index).name(), values.get(index));
            }
            if (captureTypes != null) {
                for (Formal formal : formals) {
                    final String value = own.get(formal.name());
                    if (value != null && (value.equals("*") || value.startsWith("-") || value.startsWith("+"))) {
                        final String upper = captureUpper(captureTypes, type, formal, value, own);
                        own.put(formal.name(), "+" + upper);
                    }
                }
            }
        }
        if (type.equals(owner)) {
            path.remove(type);
            return own;
        }
        final List<String> parents = declaration == null ? List.of() : sequence(declaration, formalEnd(declaration));
        Map<String, String> inherited = null;
        for (ReferenceType parent : JdiTypes.parents(type)) {
            if (!JdiTypes.assignable(parent, owner)) {
                continue;
            }
            String parentUse = null;
            for (String signature : parents) {
                if (binaryName(signature).equals(parent.name()) && (formals.isEmpty() || !own.isEmpty())) {
                    parentUse = substitute(signature, own);
                    break;
                }
            }
            final Map<String, String> candidate = bindings(parent, parentUse, owner, path, captureTypes);
            if (inherited != null && !inherited.equals(candidate)) {
                throw Planner.compile("inconsistent generic receiver types");
            }
            inherited = candidate;
        }
        path.remove(type);
        return inherited == null ? Map.of() : inherited;
    }

    /** Reduces the capture's intersection only when a single most specific bound can be proven. */
    private static String captureUpper(JdiTypes types, ReferenceType owner, Formal formal,
                                       String wildcard, Map<String, String> bindings) {
        String upper = wildcard.startsWith("+") ? wildcard.substring(1) : "Ljava/lang/Object;";
        for (String declared : formal.bounds()) {
            final String bound = substitute(declared, bindings);
            final String declaredUpper = bound.startsWith("+") ? bound.substring(1) : bound;
            upper = narrowerBound(types, owner, upper, declaredUpper);
        }
        return upper;
    }

    /** Picks the narrower of two bounds, comparing parameterized bounds only with proof. */
    private static String narrowerBound(JdiTypes types, ReferenceType owner, String a, String b) {
        if (!binaryName(a).isEmpty() && !binaryName(b).isEmpty()) {
            if (a.equals(b) || b.equals("Ljava/lang/Object;")) {
                return a;
            }
            if (a.equals("Ljava/lang/Object;")) {
                return b;
            }
            // Erased subtype checks cannot prove the order of different parameterized bounds.
            if (a.indexOf('<') < 0 && b.indexOf('<') < 0) {
                final ReferenceType left = types.signatureType(binaryName(a), owner);
                final ReferenceType right = types.signatureType(binaryName(b), owner);
                if (left != null && right != null) {
                    if (JdiTypes.assignable(left, right)) {
                        return a;
                    }
                    if (JdiTypes.assignable(right, left)) {
                        return b;
                    }
                }
            }
        }
        throw Planner.compile("wildcard capture has intersection or unresolved bounds");
    }

    /** Reads consecutive JVM types from a method or class signature. */
    private static List<String> sequence(String signature, int start) {
        final List<String> types = new ArrayList<>();
        for (int index = start; index < signature.length();) {
            final int end = typeEnd(signature, index);
            types.add(signature.substring(index, end));
            index = end;
        }
        return types;
    }

    /** Returns the exclusive end of one JVM type signature. */
    private static int typeEnd(String signature, int start) {
        try {
            return Util.scanTypeSignature(signature.toCharArray(), start) + 1;
        } catch (IllegalArgumentException | IndexOutOfBoundsException | StackOverflowError e) {
            throw Planner.compile("cannot read generic type signature");
        }
    }

    /** Returns the end of the formal list, or zero when the declaration has no formals. */
    private static int formalEnd(String signature) {
        if (!signature.startsWith("<")) {
            return 0;
        }
        int depth = 1;
        for (int index = 1; index < signature.length(); index++) {
            final char token = signature.charAt(index);
            if (token == '<') {
                depth++;
            } else if (token == '>' && --depth == 0) {
                return index + 1;
            }
        }
        throw Planner.compile("cannot read formal type parameters");
    }

    /** A formal parameter and its declared upper bounds. */
    private record Formal(String name, List<String> bounds) {}

    /** Extracts formal names for comparisons of parameterized argument types. */
    private static List<String> formals(@Nullable String declaration) {
        final List<String> names = new ArrayList<>();
        for (Formal formal : readFormals(declaration)) {
            names.add(formal.name());
        }
        return names;
    }

    /** Extracts the leading formal parameter declarations and each of their bounds. */
    private static List<Formal> readFormals(@Nullable String declaration) {
        final List<Formal> result = new ArrayList<>();
        if (declaration != null) {
            final int end = formalEnd(declaration);
            int index = 1;
            while (index < end - 1) {
                final int colon = declaration.indexOf(':', index);
                if (colon <= index || colon >= end) {
                    throw Planner.compile("cannot read formal type parameters");
                }
                final String name = declaration.substring(index, colon);
                index = colon + 1;
                if (declaration.charAt(index) == ':') {
                    // An empty class bound is followed by the first interface bound.
                    index++;
                }
                final List<String> bounds = new ArrayList<>();
                do {
                    final int boundEnd = typeEnd(declaration, index);
                    bounds.add(declaration.substring(index, boundEnd));
                    index = boundEnd;
                    if (declaration.charAt(index) != ':') {
                        break;
                    }
                    index++;
                } while (index < end);
                result.add(new Formal(name, bounds));
            }
        }
        return result;
    }

    /** Extracts the arguments of the innermost parameterized class. */
    private static List<String> arguments(String signature) {
        int open = signature.indexOf('<');
        List<String> result = List.of();
        while (open >= 0) {
            final List<String> current = new ArrayList<>();
            int index = open + 1;
            while (index < signature.length() && signature.charAt(index) != '>') {
                final int end = argumentEnd(signature, index);
                current.add(signature.substring(index, end));
                index = end;
            }
            result = current;
            open = signature.indexOf('<', index);
        }
        return result;
    }

    /** Returns the end of one wildcard or type argument. */
    private static int argumentEnd(String signature, int index) {
        return switch (signature.charAt(index)) {
            case '*' -> index + 1;
            case '+', '-' -> typeEnd(signature, index + 1);
            default -> typeEnd(signature, index);
        };
    }

    /** Replaces only type-variable tokens, including those in arrays and nested arguments. */
    private static String substitute(String signature, Map<String, String> bindings) {
        return substitute(signature, bindings, 0);
    }

    /** Bounds metadata recursion even for signatures with deeply nested types. */
    private static String substitute(String signature, Map<String, String> bindings, int depth) {
        if (bindings.isEmpty()) {
            return signature;
        }
        if (depth == 256) {
            throw Planner.compile("generic type signature is too deep");
        }
        final char first = signature.charAt(0);
        if (first == 'T') {
            return bindings.getOrDefault(signature.substring(1, signature.length() - 1), signature);
        }
        if (first == '[' || first == '+' || first == '-') {
            return first + substitute(signature.substring(1), bindings, depth + 1);
        }
        int open = signature.indexOf('<');
        if (open < 0) {
            return signature;
        }
        final StringBuilder result = new StringBuilder(signature.length());
        int start = 0;
        while (open >= 0) {
            result.append(signature, start, open + 1);
            int index = open + 1;
            while (signature.charAt(index) != '>') {
                final int end = argumentEnd(signature, index);
                result.append(substitute(signature.substring(index, end), bindings, depth + 1));
                index = end;
            }
            start = index;
            open = signature.indexOf('<', start);
        }
        return result.append(signature, start, signature.length()).toString();
    }

    /** Converts a reference signature to the name used by JDI, omitting generic arguments. */
    private static String binaryName(String signature) {
        int dimensions = 0;
        while (dimensions < signature.length() && signature.charAt(dimensions) == '[') {
            dimensions++;
        }
        final String suffix = "[]".repeat(dimensions);
        if (dimensions == signature.length()) {
            return "";
        }
        if (signature.charAt(dimensions) != 'L') {
            return switch (signature.charAt(dimensions)) {
                case 'B' -> "byte" + suffix;
                case 'C' -> "char" + suffix;
                case 'D' -> "double" + suffix;
                case 'F' -> "float" + suffix;
                case 'I' -> "int" + suffix;
                case 'J' -> "long" + suffix;
                case 'S' -> "short" + suffix;
                case 'Z' -> "boolean" + suffix;
                default -> "";
            };
        }
        final StringBuilder binary = new StringBuilder();
        int depth = 0;
        for (int index = dimensions + 1; index < signature.length(); index++) {
            final char token = signature.charAt(index);
            if (token == '<') {
                depth++;
            } else if (token == '>') {
                depth--;
            } else if (depth == 0 && token != ';') {
                binary.append(switch (token) {
                    case '/' -> '.';
                    case '.' -> '$';
                    default -> token;
                });
            }
        }
        return binary.append(suffix).toString();
    }
}
