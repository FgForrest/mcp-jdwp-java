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
     * Compile-time type of a plan node: a primitive kind, a reference type, the null type, or a deferred type whose erasure is imprecise. {@code bounds} lists the intersection bounds when there are several.
     */
    record StaticType(@Nullable Kind kind, @Nullable ReferenceType reference, boolean imprecise,
                      List<ReferenceType> bounds) {
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
    /** Lazily loaded classes visible to the declaring type's class loader. */
    private @Nullable Set<ReferenceType> visible;
    /** Lazily read names of the classes each inspected type's constant pool refers to. */
    private final Map<ReferenceType, Set<String>> constantPoolClasses = new HashMap<>();

    /** Creates type lookup scoped to the declaring type. */
    JdiTypes(VirtualMachine vm, @Nullable ReferenceType declaring) {
        this.vm = vm;
        this.declaring = declaring;
    }

    /** Returns the static type of a primitive kind. */
    static StaticType primitive(Kind kind) {
        return new StaticType(kind, null, false);
    }

    /**
     * Returns the static type of a JDI type; a generic signature starting with {@code T} marks an erased type variable.
     */
    static StaticType of(Type type, @Nullable String genericSignature) {
        if (type instanceof ReferenceType reference) {
            return new StaticType(null, reference, genericSignature != null && genericSignature.startsWith("T"));
        }
        return primitive(Kind.valueOf(type.name().toUpperCase(Locale.ROOT)));
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

    /** Finds a loaded bootstrap class, or refuses the plan when it is not loaded. */
    ReferenceType bootstrap(String name) {
        return vm.classesByName(name).stream().filter(type -> type.classLoader() == null).findFirst()
            .orElseThrow(() -> Planner.compile("type is not loaded: " + name));
    }

    /** Returns the box class of a primitive kind. */
    ReferenceType box(Kind kind) {
        return bootstrap("java.lang." + BOXES.get(kind));
    }

    /** Resolves a type name left to right, using package lookup if the first segment has no type binding. */
    @Nullable ReferenceType resolve(String name) {
        final int dot = name.indexOf('.');
        if (dot < 0) {
            return resolveSimple(name);
        }
        ReferenceType qualifier = resolveSimple(name.substring(0, dot));
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

    /**
     * Resolves a simple name in lexical scope first, checking package and java.lang defaults
     * against the declaring type's constant pool.
     */
    private @Nullable ReferenceType resolveSimple(String name) {
        final ReferenceType owner = declaring;
        ReferenceType fallback = null;
        if (owner != null) {
            final ReferenceType lexical = lexicalType(owner, name);
            if (lexical != null) {
                return lexical;
            }
            final String binary = owner.name();
            final int packageEnd = binary.lastIndexOf('.');
            fallback = findQualified(binary.substring(0, packageEnd + 1) + name);
        }
        if (fallback == null) {
            fallback = findQualified("java.lang." + name);
        }
        if (owner != null) {
            final Set<ReferenceType> candidates = new LinkedHashSet<>();
            for (String candidate : classNames(owner)) {
                if (!simpleName(candidate).equals(name)) {
                    continue;
                }
                if (fallback != null && !fallback.name().equals(candidate)) {
                    throw Planner.compile("ambiguous type name: " + name);
                }
                if (fallback == null) {
                    final ReferenceType type = findExact(candidate);
                    if (type != null) {
                        candidates.add(type);
                    }
                }
            }
            if (!candidates.isEmpty()) {
                return unique(name, candidates);
            }
        }
        return fallback == null ? findQualified(name) : fallback;
    }

    /** Finds a simple name in the declaring type's lexical scopes, including inherited member types. */
    private @Nullable ReferenceType lexicalType(ReferenceType owner, String name) {
        final String binary = owner.name();
        final String suffix = "$" + name;
        final Map<ReferenceType, Set<ReferenceType>> known = new HashMap<>();
        for (String enclosing = binary; ; enclosing = enclosing.substring(0, enclosing.lastIndexOf('$'))) {
            final ReferenceType scope = enclosing.equals(binary) ? owner : findExact(enclosing);
            if (scope != null) {
                if (simpleName(enclosing).equals(name)) {
                    return scope;
                }
                final Set<ReferenceType> candidates = memberTypes(scope, suffix, known);
                if (!candidates.isEmpty()) {
                    return unique(name, candidates);
                }
            } else {
                final ReferenceType declared = findExact(enclosing + suffix);
                if (declared != null) {
                    return declared;
                }
            }
            if (enclosing.lastIndexOf('$') <= enclosing.lastIndexOf('.')) {
                return null;
            }
        }
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

    /**
     * Parses the constant pool bytes and collects the names of the referenced classes, or none when the VM cannot supply it.
     */
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
                if (name != null && !name.startsWith("[")) {
                    names.add(name.replace('/', '.'));
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
