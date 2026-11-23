package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static one.edee.mcp.jdwp.evaluation.interpret.JdiTypes.StaticType;

/** Finds declarations for the single-candidate invocation rule. */
final class CallCandidates {
    /** Public instance methods that Java exposes on an interface receiver. */
    private static final Set<String> OBJECT_METHODS = Set.of(
        "equals", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");

    /** This class only resolves method metadata. */
    private CallCandidates() {}

    /** Returns methods visible on the static receiver, grouping overrides by erased parameter descriptor. */
    static Collection<Method> named(JdiTypes types, ReferenceType type, @Nullable StaticType receiver, String name) {
        final Map<String, Method> methods = new LinkedHashMap<>();
        final List<Method> bridges = new ArrayList<>();
        collect(type, type, name, methods, bridges, new HashSet<>(), true, false);
        if ((type instanceof InterfaceType || type instanceof ArrayType) && OBJECT_METHODS.contains(name)) {
            final ReferenceType object = types.bootstrap("java.lang.Object");
            for (Method method : object.methodsByName(name)) {
                if (method.isPublic() && !method.isStatic()) {
                    methods.putIfAbsent(parameters(method), method);
                }
            }
        }
        // Access relaxation is a separate lookup. An existing Java member controls the name.
        if (methods.isEmpty() && bridges.isEmpty() && !name.equals("<init>")) {
            collect(type, type, name, methods, bridges, new HashSet<>(), true, true);
        }
        for (Method bridge : bridges) {
            collapseBridge(types, receiver, methods, bridge);
        }
        return methods.values();
    }

    /** Collects methods inherited through Java membership, or through the debugger's access extension. */
    private static void collect(ReferenceType type, ReferenceType root, String name, Map<String, Method> methods,
                                List<Method> bridges, Set<ReferenceType> visited,
                                boolean inheritsPackage, boolean relaxedAccess) {
        if (!visited.add(type)) {
            return;
        }
        for (Method method : type.methods()) {
            if (method.name().equals(name)
                && (name.equals("<init>") || (!method.isConstructor() && !method.isStaticInitializer()))
                && (!method.isSynthetic() || method.isBridge())
                && (!method.isStatic() || !(type instanceof InterfaceType) || type.equals(root))
                && (type.equals(root) || relaxedAccess
                    || (!method.isPrivate() && (!method.isPackagePrivate() || inheritsPackage)))) {
                if (method.isBridge()) {
                    bridges.add(method);
                    continue;
                }
                final String signature = parameters(method);
                final Method prior = methods.get(signature);
                if (prior == null || prefer(method, prior)) {
                    methods.put(signature, method);
                }
            }
        }
        if (!name.equals("<init>")) {
            for (ReferenceType parent : JdiTypes.parents(type)) {
                // Package-private membership requires an unbroken inheritance path in the receiver's package.
                final boolean samePackage = inheritsPackage && packageName(parent).equals(packageName(root));
                collect(parent, root, name, methods, bridges, visited, samePackage, relaxedAccess);
            }
        }
    }

    /** Returns the declared package from a binary class name. */
    private static String packageName(ReferenceType type) {
        final String name = type.name();
        return name == null ? "" : name.substring(0, Math.max(0, name.lastIndexOf('.')));
    }

    /** Picks the most specific declaration of a signature, keeping class members ahead of interface members. */
    private static boolean prefer(Method candidate, Method prior) {
        final ReferenceType next = candidate.declaringType();
        final ReferenceType previous = prior.declaringType();
        if (next.equals(previous)) {
            return false;
        }
        if ((next instanceof InterfaceType) != (previous instanceof InterfaceType)) {
            return !(next instanceof InterfaceType);
        }
        if (JdiTypes.assignable(next, previous)) {
            return true;
        }
        // Unrelated declarations are interchangeable only when their source types are known to agree.
        if (!JdiTypes.assignable(previous, next)
            && (!candidate.signature().equals(prior.signature())
                || candidate.genericSignature() != null || prior.genericSignature() != null)) {
            throw Planner.compile("inherited method declarations have different types: " + candidate.name());
        }
        return false;
    }

    /** Uses a bridge to identify a proven generic override with a different erased descriptor. */
    private static void collapseBridge(JdiTypes types, @Nullable StaticType receiver, Map<String, Method> methods,
                                       Method bridge) {
        final String erased = parameters(bridge);
        final Method inherited = methods.get(erased);
        final ReferenceType owner = bridge.declaringType();
        if (inherited != null && owner.equals(inherited.declaringType())) {
            // A covariant return can generate a bridge with identical parameter types.
            return;
        }
        if (inherited != null && receiver != null && JdiTypes.assignable(owner, inherited.declaringType())) {
            try {
                final String substituted = GenericSignatures.parameterDescriptor(types, inherited, receiver);
                final Method override = methods.get(substituted);
                if (!erased.equals(substituted) && override != null && owner.equals(override.declaringType())) {
                    methods.remove(erased);
                    return;
                }
            } catch (ClassNotLoadedException | Planner.Refusal ignored) {
                // Keep the erased candidate until its source signature can be established.
            }
        }
        if (inherited == null) {
            methods.put(erased, bridge);
        }
    }

    /** Returns the argument portion of a method's JVM descriptor. */
    private static String parameters(Method method) {
        final String signature = method.signature();
        return signature.substring(0, signature.indexOf(')') + 1);
    }

    /** Counts formal parameters without resolving types or allocating a descriptor list. */
    private static int parameterCount(String descriptor) {
        int count = 0;
        for (int index = 1; descriptor.charAt(index) != ')'; index++) {
            final char token = descriptor.charAt(index);
            if (token != '[') {
                count++;
                if (token == 'L') {
                    index = descriptor.indexOf(';', index);
                }
            }
        }
        return count;
    }

    /** Finds the only candidate with matching arity and checks phase-2 signature restrictions. */
    static Method single(Collection<Method> candidates, int arity, String name) {
        @Nullable Method chosen = null;
        for (Method method : candidates) {
            if (method.isVarArgs()) {
                throw Planner.compile("varargs method: " + name);
            }
        }
        for (Method method : candidates) {
            // Count from the descriptor. Matching arity must not depend on whether a formal type is loaded.
            if (parameterCount(method.signature()) == arity) {
                if (chosen != null) {
                    throw Planner.compile("multiple candidates of the same arity: " + name);
                }
                chosen = method;
            }
        }
        if (chosen == null) {
            throw Planner.compile("no candidate with matching arity: " + name);
        }
        final String generic = chosen.genericSignature();
        if (generic != null && generic.startsWith("<")) {
            throw Planner.compile("generic method: " + name);
        }
        if (chosen.isBridge()) {
            throw Planner.compile("bridge method requires source signature: " + name);
        }
        if (JdiTypes.isBootstrap(chosen.declaringType(), "java.lang.invoke.MethodHandle")
            || JdiTypes.isBootstrap(chosen.declaringType(), "java.lang.invoke.VarHandle")) {
            if (chosen.isNative()) {
                throw Planner.compile("signature-polymorphic method: " + name);
            }
        }
        return chosen;
    }
}
