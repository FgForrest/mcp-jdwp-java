package one.edee.mcp.jdwp.evaluation.interpret;

import org.eclipse.jdt.internal.compiler.util.Util;
import org.jspecify.annotations.Nullable;

/** Checks the leading formal parameter list in a JVM method or class signature. */
final class FormalTypeParameters {
    /** This class only inspects signature strings. */
    private FormalTypeParameters() {}

    /** Checks for a formal name and defers lookup if the list cannot be parsed. */
    static boolean contains(@Nullable String signature, String name) {
        if (signature == null || !signature.startsWith("<")) {
            return false;
        }
        final int end = listEnd(signature);
        if (end == 1) {
            throw Planner.compile("cannot read formal type parameters");
        }
        final char[] formals = signature.substring(0, end + 1).toCharArray();
        boolean matches = false;
        int index = 1;
        while (index < end) {
            final int colon = signature.indexOf(':', index);
            if (colon <= index || colon >= end) {
                throw Planner.compile("cannot read formal type parameters");
            }
            final String parameter = signature.substring(index, colon);
            if (!Character.isJavaIdentifierStart(parameter.codePointAt(0))
                || !parameter.codePoints().allMatch(Character::isJavaIdentifierPart)) {
                throw Planner.compile("cannot read formal type parameters");
            }
            matches |= parameter.equals(name);
            index = colon + 1;
            if (startsReferenceBound(formals[index])) {
                index = skipBound(formals, index);
            }
            while (formals[index] == ':') {
                index = skipBound(formals, index + 1);
            }
        }
        return matches;
    }

    /** Finds the closing delimiter of the formal list, allowing generic types inside bounds. */
    private static int listEnd(String signature) {
        int depth = 1;
        for (int index = 1; index < signature.length(); index++) {
            final char token = signature.charAt(index);
            if (token == '<') {
                depth++;
            } else if (token == '>' && --depth == 0) {
                return index;
            }
        }
        throw Planner.compile("cannot read formal type parameters");
    }

    /** Checks for the start of a JVM reference type signature. */
    private static boolean startsReferenceBound(char token) {
        return token == 'L' || token == 'T' || token == '[';
    }

    /** Uses ECJ's type signature scanner to skip a bound without reading past the formal list. */
    private static int skipBound(char[] formals, int start) {
        if (!startsReferenceBound(formals[start]) || formals[start + 1] == ';') {
            throw Planner.compile("cannot read formal type parameters");
        }
        final int end;
        try {
            end = Util.scanTypeSignature(formals, start);
        } catch (IllegalArgumentException | IndexOutOfBoundsException | StackOverflowError failure) {
            throw Planner.compile("cannot read formal type parameters");
        }
        if (end <= start || end >= formals.length - 1) {
            throw Planner.compile("cannot read formal type parameters");
        }
        return end + 1;
    }
}
