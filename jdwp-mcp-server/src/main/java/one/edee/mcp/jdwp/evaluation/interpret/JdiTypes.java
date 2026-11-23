package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;

import static one.edee.mcp.jdwp.evaluation.interpret.PrimitiveOps.Kind;

/**
 * Static types and type lookup in the target VM for the interpreter, resolved relative to the declaring type of the stack frame.
 */
final class JdiTypes {
    /**
     * Compile-time type of a node, including any unresolved erasure or intersection bounds.
     * A capture keeps its upper bound for member lookup and retains its identity as a captured type.
     */
    record StaticType(@Nullable Kind kind, @Nullable ReferenceType reference, boolean imprecise,
                      List<ReferenceType> bounds, @Nullable String genericSignature, boolean captured) {
        /** Creates a type whose source type was not reduced to a capture bound. */
        StaticType(@Nullable Kind kind, @Nullable ReferenceType reference, boolean imprecise,
                   List<ReferenceType> bounds, @Nullable String genericSignature) {
            this(kind, reference, imprecise, bounds, genericSignature, false);
        }

        /** Creates a type without generic arguments. */
        StaticType(@Nullable Kind kind, @Nullable ReferenceType reference, boolean imprecise,
                   List<ReferenceType> bounds) {
            this(kind, reference, imprecise, bounds, null);
        }

        /** Creates a type without intersection bounds. */
        StaticType(@Nullable Kind kind, @Nullable ReferenceType reference, boolean imprecise) {
            this(kind, reference, imprecise, List.of());
        }

        /** Whether this is the type of the {@code null} literal. */
        boolean isNull() {
            return kind == null && reference == null && !imprecise;
        }

        /** Whether the type is a type variable that could not be resolved to a class. */
        boolean deferred() {
            return reference == null && imprecise;
        }

        /** Whether this is {@code java.lang.String}. */
        boolean isString() {
            return isBootstrap(reference, "java.lang.String");
        }
    }

    /** Type of the {@code null} literal. */
    static final StaticType NULL = new StaticType(null, null, false);
    /** Type that is only known at run time. */
    static final StaticType DEFERRED = new StaticType(null, null, true);
    /** Simple names of the {@code java.lang} box classes by primitive kind. */
    private static final Map<Kind, String> BOXES = Map.of(
        Kind.BOOLEAN, "Boolean", Kind.BYTE, "Byte", Kind.SHORT, "Short", Kind.CHAR, "Character",
        Kind.INT, "Integer", Kind.LONG, "Long", Kind.FLOAT, "Float", Kind.DOUBLE, "Double", Kind.VOID, "Void");

    /** Target VM that types are looked up in. */
    private final VirtualMachine vm;
    /** Type declaring the method of the frame, or null without a frame; it defines the visible scope. */
    private final @Nullable ReferenceType declaring;
    /** Method of the frame, when available, for its formal type parameters. */
    private final @Nullable Method method;
    /** Lexical scopes, from the declaring type outward, including unloaded enclosing types. */
    private @Nullable List<LexicalScope> scopes;
    /** Lazily loaded classes visible to the declaring type's class loader. */
    private @Nullable Set<ReferenceType> visible;
    /** Lazily read names of the classes each inspected type's constant pool refers to. */
    private final Map<ReferenceType, Set<String>> constantPoolClasses = new HashMap<>();

    /** Creates type lookup for a declaring type without method metadata. */
    JdiTypes(VirtualMachine vm, @Nullable ReferenceType declaring) {
        this(vm, declaring, null);
    }

    /** Creates type lookup scoped to the declaring type and the frame's method. */
    JdiTypes(VirtualMachine vm, @Nullable ReferenceType declaring, @Nullable Method method) {
        this.vm = vm;
        this.declaring = declaring;
        this.method = method;
    }

    /** Returns the static type of a primitive kind. */
    static StaticType primitive(Kind kind) {
        return new StaticType(kind, null, false);
    }

    /**
     * Returns the static type of a JDI type; a generic signature starting with {@code T} marks an erased type variable.
     */
    static StaticType of(Type type, @Nullable String genericSignature) {
        return of(type, genericSignature, false);
    }

    /** Preserves capture provenance when a generic result uses its upper bound for lookup. */
    static StaticType of(Type type, @Nullable String genericSignature, boolean captured) {
        if (type instanceof ReferenceType reference) {
            return new StaticType(null, reference, genericSignature != null && genericSignature.startsWith("T"),
                List.of(), genericSignature, captured);
        }
        return primitive(Kind.valueOf(type.name().toUpperCase(Locale.ROOT)));
    }

    /** Whether the target language version permits an unconditional binding pattern in instanceof. */
    boolean supportsUnconditionalPatterns() {
        final @Nullable String version = vm.version();
        if (version == null) {
            return false;
        }
        try {
            return Runtime.Version.parse(version).feature() >= 21;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Whether the source type of a pattern operand is known without capture, erasure or inference. */
    boolean plainPatternType(StaticType type) throws ClassNotLoadedException {
        if (type.imprecise() || type.captured() || !type.bounds().isEmpty()) {
            return false;
        }
        final ReferenceType reference = type.reference();
        if (reference == null) {
            return type.isNull();
        }
        final String signature = type.genericSignature();
        return (signature == null || signature.equals(reference.signature())) && plainPatternReference(reference);
    }

    /** Checks for generic declarations, including array components and non-static member class owners. */
    boolean plainPatternReference(ReferenceType reference) throws ClassNotLoadedException {
        if (reference instanceof ArrayType array) {
            final Type component = array.componentType();
            return !(component instanceof ReferenceType ref) || plainPatternReference(ref);
        }
        final String declaration = reference.genericSignature();
        if (declaration != null && declaration.startsWith("<")) {
            return false;
        }
        final String name = reference.name();
        final int nested = name.lastIndexOf('$');
        if (nested < 0 || reference.isStatic()) {
            return true;
        }
        final ReferenceType owner = signatureType(name.substring(0, nested), reference);
        return owner != null && plainPatternReference(owner);
    }

    /** Returns the primitive kind of a primitive or box type, or null for any other type. */
    static @Nullable Kind unboxed(StaticType type) {
        if (type.kind() != null) {
            return type.kind();
        }
        final ReferenceType reference = type.reference();
        if (reference != null && reference.name().startsWith("java.lang.") && reference.classLoader() == null) {
            for (Map.Entry<Kind, String> box : BOXES.entrySet()) {
                if (box.getKey() != Kind.VOID && reference.name().equals("java.lang." + box.getValue())) {
                    return box.getKey();
                }
            }
        }
        return null;
    }

    /** Whether the type is the named class loaded by the bootstrap loader. */
    static boolean isBootstrap(@Nullable ReferenceType type, String name) {
        return type != null && name.equals(type.name()) && type.classLoader() == null;
    }

    /** Finds a type by its JVM name in the context of a member's declaration. */
    @Nullable ReferenceType signatureType(String name, ReferenceType owner) {
        final ReferenceType local = findExact(name);
        if (local != null || (declaring != null && Objects.equals(owner.classLoader(), declaring.classLoader()))) {
            return local;
        }
        return new JdiTypes(vm, owner).findExact(name);
    }

    /** Finds a loaded bootstrap class, or refuses the plan when it is not loaded. */
    ReferenceType bootstrap(String name) {
        return vm.classesByName(name).stream().filter(type -> type.classLoader() == null).findFirst()
            .orElseThrow(() -> Planner.compile("type is not loaded: " + name));
    }

    /** Returns the box class of a primitive kind. */
    ReferenceType box(Kind kind) {
        return bootstrap("java.lang." + BOXES.get(kind));
    }

    /** Resolves a complete type name, binding its segments from left to right. */
    @Nullable ReferenceType resolve(String name) {
        return resolve(name, false);
    }

    /** Resolves a name that may be followed by a field in a dotted expression. */
    @Nullable ReferenceType resolve(String name, boolean followedByField) {
        final int dot = name.indexOf('.');
        final boolean packagePrefix = (dot >= 0 || followedByField) && Character.isLowerCase(name.codePointAt(0));
        if (dot < 0) {
            return resolveSimple(name, packagePrefix);
        }
        ReferenceType qualifier = resolveSimple(name.substring(0, dot), packagePrefix);
        if (qualifier == null) {
            return findQualified(name);
        }
        for (String member : name.substring(dot + 1).split("\\.")) {
            final Set<ReferenceType> candidates = memberTypes(qualifier, "$" + member, new HashMap<>());
            if (candidates.isEmpty()) {
                throw Planner.compile("member type is unavailable: " + qualifier.name() + "." + member);
            }
            qualifier = unique(member, candidates);
        }
        return qualifier;
    }

    /** Binds a simple name lexically while respecting unavailable enclosing types and methods. */
    private @Nullable ReferenceType resolveSimple(String name, boolean packagePrefix) {
        final ReferenceType owner = declaring;
        if (owner == null) {
            return resolveNonLexicalType(name);
        }
        if (method != null && FormalTypeParameters.contains(method.genericSignature(), name)) {
            throw Planner.compile("type parameter may shadow type: " + name);
        }
        final List<LexicalScope> lexicalScopes = lexicalScopes(owner);
        for (LexicalScope scope : lexicalScopes) {
            final ReferenceType type = scope.type();
            if (type != null) {
                checkContextualBinding(type, name);
            }
        }
        final String suffix = "$" + name;
        final Map<ReferenceType, Set<ReferenceType>> known = new HashMap<>();
        for (int index = 0; index < lexicalScopes.size(); index++) {
            final LexicalScope scope = lexicalScopes.get(index);
            final ReferenceType type = scope.type();
            if (type == null) {
                // The declaring type is loaded, so an unloaded scope always has a scope just inside it.
                if (crossesEnclosingMethod(lexicalScopes.get(index - 1).binary(), scope.binary())) {
                    if (packagePrefix) {
                        return null;
                    }
                    throw Planner.compile("enclosing method is not visible: " + scope.binary());
                }
                final ReferenceType declared = findExact(scope.binary() + suffix);
                if (declared != null || packagePrefix) {
                    return declared;
                }
                throw Planner.compile("enclosing type is not loaded: " + scope.binary());
            }
            final Set<ReferenceType> candidates = memberTypes(type, suffix, known);
            if (!candidates.isEmpty()) {
                return unique(name, candidates);
            }
            if (simpleName(scope.binary()).equals(name)) {
                return type;
            }
        }
        return resolveNonLexicalType(name);
    }

    /** Detects the digit that starts a local or anonymous class's binary suffix. */
    private static boolean crossesEnclosingMethod(String inner, String enclosing) {
        final int start = enclosing.length() + 1;
        return start < inner.length() && inner.charAt(start) >= '0' && inner.charAt(start) <= '9';
    }

    /** Checks for an uncontested java.lang binding when lexical lookup found no type. */
    private @Nullable ReferenceType resolveNonLexicalType(String name) {
        final ReferenceType owner = declaring;
        if (owner != null) {
            final String binary = owner.name();
            final String packagePrefix = binary.substring(0, binary.lastIndexOf('.') + 1);
            if (!vm.classesByName(packagePrefix + name).isEmpty()) {
                throw needsQualification(name);
            }
        }
        final String standardName = "java.lang." + name;
        final ReferenceType standard = findExact(standardName);
        for (Set<String> pool : constantPoolClasses.values()) {
            for (String candidate : pool) {
                if (!simpleName(candidate).equals(name)) {
                    continue;
                }
                if (standard == null) {
                    throw needsQualification(name);
                }
                if (!standardName.equals(candidate)) {
                    throw Planner.compile("ambiguous type name: " + name);
                }
            }
        }
        if (standard == null && !vm.classesByName(name).isEmpty()) {
            throw needsQualification(name);
        }
        return standard;
    }

    /** Reports a simple name that the interpreter cannot bind without qualification. */
    private static Planner.Refusal needsQualification(String name) {
        return Planner.compile("type name needs an import or the frame's package: " + name);
    }

    /** A lexical scope's binary name and its mirror if visible to the frame loader. */
    private record LexicalScope(String binary, @Nullable ReferenceType type) {}

    /** Caches the lexical chain for all type names in a plan. */
    private List<LexicalScope> lexicalScopes(ReferenceType owner) {
        if (scopes == null) {
            final String binary = owner.name();
            final List<LexicalScope> collected = new ArrayList<>();
            for (String enclosing = binary; ; enclosing = enclosing.substring(0, enclosing.lastIndexOf('$'))) {
                final ReferenceType type = enclosing.equals(binary) ? owner : findExact(enclosing);
                collected.add(new LexicalScope(enclosing, type));
                if (enclosing.lastIndexOf('$') <= enclosing.lastIndexOf('.')) {
                    break;
                }
            }
            scopes = collected;
        }
        return scopes;
    }

    /** Checks a loaded lexical scope and its declared methods for possible shadowing bindings. */
    private void checkContextualBinding(ReferenceType scope, String name) {
        if (FormalTypeParameters.contains(scope.genericSignature(), name)) {
            throw Planner.compile("type parameter may shadow type: " + name);
        }
        for (Method declared : scope.methods()) {
            if (FormalTypeParameters.contains(declared.genericSignature(), name)) {
                throw Planner.compile("type parameter may shadow type: " + name);
            }
        }
        for (String candidate : classNames(scope)) {
            if (isLocalClassName(candidate, name)) {
                throw Planner.compile("local class may shadow type: " + name);
            }
        }
    }

    /** Matches a local class binary name ending in a dollar sign, digits and the requested simple name. */
    private static boolean isLocalClassName(String binary, String name) {
        if (!binary.endsWith(name)) {
            return false;
        }
        final int nameStart = binary.length() - name.length();
        int digit = nameStart;
        while (digit > 0 && binary.charAt(digit - 1) >= '0' && binary.charAt(digit - 1) <= '9') {
            digit--;
        }
        return digit < nameStart && digit > 0 && binary.charAt(digit - 1) == '$';
    }

    /** Resolves declared or inherited members, refusing lookup past an unloaded hiding declaration. */
    private Set<ReferenceType> memberTypes(ReferenceType scope, String suffix,
                                           Map<ReferenceType, Set<ReferenceType>> known) {
        final Set<ReferenceType> cached = known.get(scope);
        if (cached != null) {
            return cached;
        }
        final Set<ReferenceType> result = new LinkedHashSet<>();
        final String binary = scope.name() + suffix;
        final ReferenceType declared = findExact(binary);
        if (declared != null) {
            result.add(declared);
        } else {
            if (classNames(scope).contains(binary)) {
                throw Planner.compile("type is not loaded: " + binary);
            }
            for (ReferenceType parent : parents(scope)) {
                for (ReferenceType inherited : memberTypes(parent, suffix, known)) {
                    if (!inherited.isPrivate() && (!inherited.isPackagePrivate()
                            || packageName(inherited).equals(packageName(scope)))) {
                        result.add(inherited);
                    }
                }
            }
        }
        known.put(scope, result);
        return result;
    }

    /** Returns the package portion of a reference type's binary name. */
    private static String packageName(ReferenceType type) {
        final String binary = type.name();
        final int separator = binary.lastIndexOf('.');
        return separator < 0 ? "" : binary.substring(0, separator);
    }

    /** Returns the simple name of a loaded type or a binary name from the constant pool. */
    private static String simpleName(String binary) {
        return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
    }

    /** Finds a class by binary name that is visible from the declaring type's class loader, or null. */
    @Nullable ReferenceType findExact(String name) {
        final ClassLoaderReference loader = declaring == null ? null : declaring.classLoader();
        final Set<ReferenceType> candidates = new LinkedHashSet<>();
        for (ReferenceType type : vm.classesByName(name)) {
            if (Objects.equals(type.classLoader(), loader) || (loader != null && visible(loader).contains(type))) {
                candidates.add(type);
            }
        }
        return candidates.isEmpty() ? null : unique(name, candidates);
    }

    /** Returns the classes visible to the loader, loading them once. */
    private Set<ReferenceType> visible(ClassLoaderReference loader) {
        if (visible == null) {
            visible = new HashSet<>(loader.visibleClasses());
        }
        return visible;
    }

    /** Finds a class by canonical name, trying {@code $} for each dot from the right to reach nested classes. */
    private @Nullable ReferenceType findQualified(String name) {
        // On the wire, nested classes have '$' in their binary name.
        String candidate = name;
        for (int dot = candidate.lastIndexOf('.'); ; dot = candidate.lastIndexOf('.', dot - 1)) {
            final ReferenceType type = findExact(candidate);
            if (type != null || dot <= 0) {
                return type;
            }
            candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1);
        }
    }

    /** Returns the only candidate, or refuses the plan when the name is ambiguous. */
    private static ReferenceType unique(String name, Set<ReferenceType> candidates) {
        if (candidates.size() != 1) {
            throw Planner.compile("ambiguous type name: " + name);
        }
        return candidates.iterator().next();
    }

    /** Returns the class names in the specified type's constant pool, reading each pool once. */
    private Set<String> classNames(ReferenceType type) {
        return constantPoolClasses.computeIfAbsent(type, this::readClassNames);
    }

    /** Collects class names and reference array component names from the target's constant pool. */
    private Set<String> readClassNames(ReferenceType type) {
        if (!vm.canGetConstantPool()) {
            return Set.of();
        }
        final byte[] bytes = type.constantPool();
        if (bytes == null) {
            return Set.of();
        }
        final int count = type.constantPoolCount();
        final String[] utf8 = new String[count];
        final List<Integer> classes = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            for (int i = 1; i < count; i++) {
                switch (in.readUnsignedByte()) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classes.add(in.readUnsignedShort());
                    case 8, 16, 19, 20 -> in.skipNBytes(2);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> {
                        in.skipNBytes(8);
                        i++;
                    }
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("unknown tag");
                }
            }
            final Set<String> names = new LinkedHashSet<>();
            for (int index : classes) {
                final String name = utf8[index];
                if (name == null) {
                    continue;
                }
                if (!name.startsWith("[")) {
                    names.add(name.replace('/', '.'));
                } else {
                    final int component = name.lastIndexOf('[') + 1;
                    if (name.startsWith("L", component) && name.endsWith(";")) {
                        names.add(name.substring(component + 1, name.length() - 1).replace('/', '.'));
                    }
                }
            }
            return names;
        } catch (IOException | IndexOutOfBoundsException e) {
            throw Planner.compile("cannot read the declaring type's constant pool");
        }
    }

    /**
     * Finds the field with the given name in the type or its supertypes; an erased type refuses the plan when the name is hidden more than once.
     */
    static @Nullable Field field(ReferenceType type, String name, boolean imprecise) {
        if (imprecise) {
            final List<Field> matches = type.allFields().stream().filter(f -> f.name().equals(name)).distinct().toList();
            if (matches.size() > 1) {
                throw Planner.compile("field hiding on an erased type: " + name);
            }
            return matches.isEmpty() ? null : matches.get(0);
        }
        final Set<Field> matches = new LinkedHashSet<>();
        collectFields(type, name, matches, new HashSet<>());
        if (matches.size() > 1) {
            throw Planner.compile("ambiguous field: " + name);
        }
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    /** Collects the nearest field of that name along each supertype path. */
    private static void collectFields(ReferenceType type, String name, Set<Field> matches,
                                      Set<ReferenceType> visited) {
        if (!visited.add(type)) {
            return;
        }
        for (Field field : type.fields()) {
            if (field.name().equals(name)) {
                matches.add(field);
                return;
            }
        }
        for (ReferenceType parent : parents(type)) {
            collectFields(parent, name, matches, visited);
        }
    }

    /** Returns the direct superclass and interfaces of a type. */
    static List<ReferenceType> parents(ReferenceType type) {
        if (type instanceof ClassType cls) {
            final List<ReferenceType> parents = new ArrayList<>();
            final ClassType superclass = cls.superclass();
            if (superclass != null) {
                parents.add(superclass);
            }
            parents.addAll(cls.interfaces());
            return parents;
        }
        if (type instanceof InterfaceType itf) {
            return new ArrayList<>(itf.superinterfaces());
        }
        return List.of();
    }

    /** Whether a value of type {@code from} can be assigned to {@code to}. */
    static boolean assignable(ReferenceType from, ReferenceType to) {
        if (from.equals(to) || isBootstrap(to, "java.lang.Object")) {
            return true;
        }
        if (from instanceof ArrayType source) {
            if (to instanceof ArrayType target) {
                try {
                    final Type sourceComponent = source.componentType();
                    final Type targetComponent = target.componentType();
                    return sourceComponent.equals(targetComponent)
                        || (sourceComponent instanceof ReferenceType a && targetComponent instanceof ReferenceType b
                            && assignable(a, b));
                } catch (ClassNotLoadedException e) {
                    throw Planner.compile("array component type is not loaded");
                }
            }
            return isBootstrap(to, "java.lang.Cloneable") || isBootstrap(to, "java.io.Serializable");
        }
        return allSupertypes(from).contains(to);
    }

    /** Returns the intersection bounds of a type, or just its reference type. */
    static List<ReferenceType> bounds(StaticType type) {
        if (!type.bounds().isEmpty()) {
            return type.bounds();
        }
        return type.reference() == null ? List.of() : List.of(type.reference());
    }

    /** Finds the field with the given name across all bounds, refusing the plan when it is ambiguous. */
    static @Nullable Field field(List<ReferenceType> bounds, String name) {
        final Set<Field> matches = new LinkedHashSet<>();
        final Set<ReferenceType> visited = new HashSet<>();
        for (ReferenceType bound : bounds) {
            collectFields(bound, name, matches, visited);
        }
        if (matches.size() > 1) {
            throw Planner.compile("ambiguous field: " + name);
        }
        return matches.isEmpty() ? null : matches.iterator().next();
    }

    /**
     * Computes the type of a conditional with the given branch types: the more general one, or their most specific common supertypes.
     */
    StaticType commonType(StaticType left, StaticType right) {
        final List<ReferenceType> a = bounds(left);
        final List<ReferenceType> b = bounds(right);
        if (implies(a, b)) {
            return right;
        }
        if (implies(b, a)) {
            return left;
        }
        final Set<ReferenceType> common = supertypes(a);
        common.retainAll(supertypes(b));
        final List<ReferenceType> mostSpecific = common.stream()
            .filter(type -> common.stream().noneMatch(other -> !other.equals(type) && assignable(other, type)))
            .toList();
        if (mostSpecific.size() == 1) {
            return of(mostSpecific.get(0), null);
        }
        final ReferenceType classBound = mostSpecific.stream().filter(t -> !(t instanceof InterfaceType))
            .findFirst().orElseGet(() -> bootstrap("java.lang.Object"));
        return new StaticType(null, classBound, false, mostSpecific);
    }

    /** Whether every target bound is satisfied by some source bound. */
    private static boolean implies(List<ReferenceType> source, List<ReferenceType> target) {
        return target.stream().allMatch(required -> source.stream().anyMatch(bound -> assignable(bound, required)));
    }

    /** Returns {@code Object} and all supertypes of the bounds, including covariant array types. */
    private Set<ReferenceType> supertypes(List<ReferenceType> bounds) {
        final Set<ReferenceType> result = new LinkedHashSet<>();
        result.add(bootstrap("java.lang.Object"));
        for (ReferenceType type : bounds) {
            result.addAll(allSupertypes(type));
            if (type instanceof ArrayType array) {
                addArraySupertypes(array, result);
            }
        }
        return result;
    }

    /** Adds {@code Cloneable}, {@code Serializable} and the covariant array types of the component's supertypes. */
    private void addArraySupertypes(ArrayType array, Set<ReferenceType> result) {
        for (String interfaceName : List.of("java.lang.Cloneable", "java.io.Serializable")) {
            result.addAll(vm.classesByName(interfaceName).stream().filter(t -> t.classLoader() == null).toList());
        }
        try {
            if (array.componentType() instanceof ReferenceType component) {
                for (ReferenceType supertype : supertypes(List.of(component))) {
                    final ReferenceType covariant = findExact(supertype.name() + "[]");
                    if (covariant != null) {
                        result.add(covariant);
                    }
                }
            }
        } catch (ClassNotLoadedException e) {
            throw Planner.compile("array component type is not loaded");
        }
    }

    /** Returns the type and all its superclasses and superinterfaces. */
    private static Set<ReferenceType> allSupertypes(ReferenceType type) {
        final Set<ReferenceType> result = new LinkedHashSet<>();
        final Deque<ReferenceType> work = new ArrayDeque<>();
        work.add(type);
        while (!work.isEmpty()) {
            final ReferenceType next = work.removeFirst();
            if (result.add(next)) {
                work.addAll(parents(next));
            }
        }
        return result;
    }
}
