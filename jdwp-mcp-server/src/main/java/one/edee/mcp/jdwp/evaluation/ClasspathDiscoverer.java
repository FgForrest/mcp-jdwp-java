package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.EvaluationGuard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads the launch classpath and the context and defining loader chains. Only overrides of
 * Thread.getContextClassLoader() and the public URLClassLoader.getURLs() contract may invoke.
 */
public class ClasspathDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(ClasspathDiscoverer.class);
    private static final String THREAD = "java.lang.Thread";
    private static final String LOADER = "java.lang.ClassLoader";
    private static final String URL_LOADER = "java.net.URLClassLoader";
    private static final String BUILTIN_LOADER = "jdk.internal.loader.BuiltinClassLoader";
    private static final String GET_URLS_SIGNATURE = "()[Ljava/net/URL;";

    private final VirtualMachine vm;
    private final EvaluationGuard guard;

    /** Binds discovery and optional invocation admission to the target connection. */
    public ClasspathDiscoverer(VirtualMachine vm, EvaluationGuard guard) {
        this.vm = vm;
        this.guard = guard;
    }

    /**
     * Extends the previous walk with the selected frame's loader and retries unreadable parts.
     * The caller publishes the result only if it still owns the VM and the previous snapshot.
     */
    public DiscoveryResult discoverClasspath(ThreadReference thread, @Nullable ClassLoaderReference definingLoader,
                                             @Nullable DiscoveryResult previous)
        throws JdkDiscoveryService.JdkNotFoundException {
        final String jdkPath;
        final int targetMajor;
        if (previous == null) {
            final JdkDiscoveryService jdkDiscovery = new JdkDiscoveryService(vm);
            jdkPath = jdkDiscovery.discoverMatchingJdk();
            targetMajor = jdkDiscovery.getTargetMajorVersion();
        } else {
            jdkPath = previous.localJdkPath();
            targetMajor = previous.targetMajorVersion();
        }
        final Walk walk = new Walk(previous);
        walk.discover(thread, definingLoader);
        return new DiscoveryResult(jdkPath, targetMajor, walk);
    }

    /** Reads only the declaring class to avoid fetching inherited and interface metadata. */
    private static @Nullable Field declaredField(ReferenceType type, String name) {
        for (final Field field : type.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    /** Resolves a required JDK field on its declaring type, independently of field hiding. */
    private Field requiredField(String owner, String name) {
        final List<ReferenceType> types = vm.classesByName(owner);
        final Field field = types.isEmpty() ? null : declaredField(types.get(0), name);
        if (field == null) {
            throw new IllegalStateException(owner + "." + name + " field is missing");
        }
        return field;
    }

    /** Preserves a genuine null from a successful context or parent read. */
    private static @Nullable ClassLoaderReference asLoader(@Nullable Value value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ClassLoaderReference loader) {
            return loader;
        }
        throw new IllegalStateException("expected a class loader reference");
    }

    /** Finds a method declared below the specified base class, searching closest declarations first. */
    private static @Nullable Method override(ClassType type, String base, String name, String signature) {
        for (ClassType current = type; current != null && !base.equals(current.name()); current = current.superclass()) {
            final Method method = publicAccessor(current, name, signature);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    /** Recognizes only a public instance accessor with the expected JVM descriptor. */
    private static @Nullable Method publicAccessor(ClassType type, String name, String signature) {
        for (final Method method : type.methods()) {
            if (name.equals(method.name()) && signature.equals(method.signature())
                && method.isPublic() && !method.isStatic()) {
                return method;
            }
        }
        return null;
    }

    /** Invokes a recognized accessor if the connection can admit it on this event thread. */
    private @Nullable Value invokeAccessor(ObjectReference receiver, ThreadReference thread, Method accessor)
        throws Exception {
        try (EvaluationGuard.Lease lease = guard.lease(thread)) {
            return receiver.invokeMethod(thread, accessor, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
        }
    }

    /** Names the exception even when JDI gives it no message. */
    private static String describe(Exception failure) {
        final String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }

    /** The known entries from a completed URL read and whether they mirror the launch path. */
    private record LoaderUrls(Set<String> entries, boolean builtin) {}

    /**
     * A single attempt, seeded from successful reads in the previous attempt. The published walk is
     * read-only. Replaying the roots preserves child-to-parent order even when a parent is found later.
     */
    private final class Walk {
        private @Nullable Set<String> launch;
        private @Nullable ClassLoaderReference context;
        private boolean contextComplete;
        private final Set<ClassLoaderReference> definingRoots = new LinkedHashSet<>();
        private final Map<ClassLoaderReference, LoaderUrls> urls = new HashMap<>();
        private final Map<ClassLoaderReference, @Nullable ClassLoaderReference> parents = new HashMap<>();
        private final Set<ClassLoaderReference> visited = new HashSet<>();
        private final Set<String> entries = new LinkedHashSet<>();
        private final List<String> problems = new ArrayList<>();
        private @Nullable Field parentField;

        /** Copies only reads that completed, leaving per-attempt diagnostics and output empty. */
        private Walk(@Nullable DiscoveryResult previous) {
            if (previous != null) {
                final Walk old = previous.walk;
                launch = old.launch;
                context = old.context;
                contextComplete = old.contextComplete;
                definingRoots.addAll(old.definingRoots);
                urls.putAll(old.urls);
                parents.putAll(old.parents);
            }
        }

        /** Visits the initial context chain followed by defining chains in first-seen order. */
        private void discover(ThreadReference thread, @Nullable ClassLoaderReference definingLoader) {
            if (launch == null) {
                readLaunch();
            }
            final Set<String> launchEntries = launch;
            if (launchEntries != null) {
                entries.addAll(launchEntries);
            }
            final boolean hasLaunchEntries = !entries.isEmpty();
            if (!contextComplete) {
                readContext(thread);
            }
            if (definingLoader != null) {
                definingRoots.add(definingLoader);
            }
            visit(context, thread, hasLaunchEntries);
            for (final ClassLoaderReference root : definingRoots) {
                visit(root, thread, hasLaunchEntries);
            }
        }

        /** Reads the launch order and distinguishes an empty classpath from a failed JDWP command. */
        private void readLaunch() {
            try {
                final Set<String> found = new LinkedHashSet<>();
                if (vm instanceof PathSearchingVirtualMachine target) {
                    for (final String path : target.classPath()) {
                        final String trimmed = path.trim();
                        if (!trimmed.isEmpty()) {
                            found.add(trimmed);
                        }
                    }
                } else {
                    incomplete("target VM", "JDWP launch classpath is unavailable");
                    return;
                }
                launch = found;
            } catch (RuntimeException e) {
                incomplete("target VM", "could not read launch classpath: " + describe(e));
            }
        }

        /** Resolves the effective context loader, keeping a field value as provisional on refusal. */
        private void readContext(ThreadReference thread) {
            final ReferenceType type = thread.referenceType();
            final String owner = type.name();
            boolean effective = true;
            try {
                if (type instanceof ClassType threadClass) {
                    final Method getter = override(threadClass, THREAD, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
                    if (getter != null) {
                        context = asLoader(invokeAccessor(thread, thread, getter));
                        contextComplete = true;
                        return;
                    }
                }
            } catch (Exception e) {
                log.debug("[Discoverer] Using {}'s contextClassLoader field after getter invocation or lookup failed", owner);
                incomplete(owner, "could not use getContextClassLoader(): " + describe(e));
                effective = false;
            }
            try {
                context = asLoader(thread.getValue(requiredField(THREAD, "contextClassLoader")));
                contextComplete = effective;
            } catch (RuntimeException e) {
                incomplete(owner, "could not read contextClassLoader: " + describe(e));
            }
        }

        /** Walks a parent chain, retrying failed URL and parent reads independently. */
        private void visit(@Nullable ClassLoaderReference root, ThreadReference thread, boolean hasLaunchEntries) {
            for (ClassLoaderReference loader = root; loader != null && visited.add(loader); ) {
                LoaderUrls paths = urls.get(loader);
                if (paths == null || !parents.containsKey(loader)) {
                    final ClassType type = (ClassType) loader.referenceType();
                    if (paths == null) {
                        final Set<String> found = new LinkedHashSet<>();
                        try {
                            paths = readUrls(loader, type, thread, hasLaunchEntries, found);
                            urls.put(loader, paths);
                        } catch (Exception e) {
                            incomplete(type.name(), "could not read loader URLs: " + describe(e));
                            entries.addAll(found);
                        }
                    }
                    if (!parents.containsKey(loader)) {
                        try {
                            if (parentField == null) {
                                parentField = requiredField(LOADER, "parent");
                            }
                            parents.put(loader, asLoader(loader.getValue(parentField)));
                        } catch (RuntimeException e) {
                            incomplete(type.name(), "could not read parent: " + describe(e));
                        }
                    }
                }
                if (paths != null && (!paths.builtin() || !hasLaunchEntries)) {
                    entries.addAll(paths.entries());
                }
                loader = parents.get(loader);
            }
        }

        /** Records the affected class and precise failure for the compiler and diagnose. */
        private void incomplete(String owner, String reason) {
            final String problem = owner + ": " + reason;
            problems.add(problem);
            log.warn("[Discoverer] Classpath discovery was incomplete: {}", problem);
        }
    }

    /** Reads a loader's URLs, invoking only the standard public URLClassLoader contract. */
    private LoaderUrls readUrls(ClassLoaderReference loader, ClassType type, ThreadReference thread,
                                boolean hasLaunchEntries, Set<String> entries) throws Exception {
        Field ucp = null;
        ClassType urlBase = null;
        boolean builtin = false;
        for (ClassType current = type; current != null && !LOADER.equals(current.name()); current = current.superclass()) {
            if (ucp == null) {
                ucp = declaredField(current, "ucp");
            }
            if (URL_LOADER.equals(current.name())) {
                urlBase = current;
                break;
            }
            if (BUILTIN_LOADER.equals(current.name())) {
                builtin = true;
                break;
            }
        }
        if (builtin && hasLaunchEntries) {
            // Keep JDWP's spelling of relative paths and directory entries.
            return new LoaderUrls(entries, true);
        }
        if (urlBase != null) {
            final Method custom = override(type, URL_LOADER, "getURLs", GET_URLS_SIGNATURE);
            if (custom != null) {
                // Container overrides can expose repositories outside the inherited URLClassPath.
                addInvokedUrls(loader, thread, custom, entries);
                return new LoaderUrls(entries, false);
            }
        }
        if (ucp != null && addFieldUrls(loader, ucp, builtin, entries)) {
            return new LoaderUrls(entries, builtin);
        }
        if (urlBase != null) {
            final Method accessor = publicAccessor(urlBase, "getURLs", GET_URLS_SIGNATURE);
            if (accessor != null) {
                addInvokedUrls(loader, thread, accessor, entries);
                return new LoaderUrls(entries, false);
            }
        }
        throw new IllegalStateException("no readable URLClassPath layout");
    }

    /** Reads URLClassPath.path in insertion order, excluding the ArrayList's spare capacity. */
    private static boolean addFieldUrls(ClassLoaderReference loader, Field ucp, boolean builtin, Set<String> entries) {
        try {
            final Value ucpValue = loader.getValue(ucp);
            if (ucpValue == null) {
                // The platform loader has no URL path on current JDKs.
                return builtin;
            }
            if (!(ucpValue instanceof ObjectReference classpath)) {
                return false;
            }
            final ReferenceType type = classpath.referenceType();
            if (!"jdk.internal.loader.URLClassPath".equals(type.name()) && !"sun.misc.URLClassPath".equals(type.name())) {
                return false;
            }
            final Field path = declaredField(type, "path");
            if (path == null || !(classpath.getValue(path) instanceof ObjectReference list)) {
                return false;
            }
            final ReferenceType listType = list.referenceType();
            final Field size = declaredField(listType, "size");
            final Field elementData = declaredField(listType, "elementData");
            if (size == null || elementData == null) {
                return false;
            }
            final Map<Field, Value> fields = list.getValues(List.of(size, elementData));
            if (!(fields.get(size) instanceof IntegerValue count) || !(fields.get(elementData) instanceof ArrayReference data)) {
                return false;
            }
            final int length = count.value();
            if (length > 0) {
                addUrlPaths(data.getValues(0, length), entries);
            }
            return true;
        } catch (RuntimeException e) {
            log.debug("[Discoverer] Could not read URLClassPath: {}", e.getMessage());
            return false;
        }
    }

    /** Reads the URLs supplied by a guarded call to the public accessor. */
    private void addInvokedUrls(ClassLoaderReference loader, ThreadReference thread, Method getUrls,
                                Set<String> entries) {
        log.debug("[Discoverer] Falling back to getURLs() on {}", loader.referenceType().name());
        final Value result;
        try {
            result = invokeAccessor(loader, thread, getUrls);
        } catch (Exception e) {
            throw new IllegalStateException("getURLs() invocation failed: " + describe(e), e);
        }
        if (!(result instanceof ArrayReference urls)) {
            throw new IllegalStateException("getURLs() did not return a URL array");
        }
        addUrlPaths(urls.getValues(), entries);
    }

    /** Adds decoded paths from either the URLClassPath list or the custom accessor's result. */
    private static void addUrlPaths(List<Value> urls, Set<String> entries) {
        for (final Value value : urls) {
            if (value instanceof ObjectReference url) {
                final String path = extractPathFromUrl(url);
                if (path != null && !path.isEmpty()) {
                    entries.add(path);
                }
            }
        }
    }

    /** Reads the same path exposed by URL.getPath(), then applies the legacy URL decoding. */
    private static @Nullable String extractPathFromUrl(ObjectReference url) {
        final ReferenceType type = url.referenceType();
        final Field path = declaredField(type, "path");
        if (path != null) {
            // URL.getPath() returns only this field, without the URL's protocol or host.
            final Value value = url.getValue(path);
            if (value == null) {
                return null;
            }
            if (value instanceof StringReference string) {
                return decodeUrlPath(string.value());
            }
        } else {
            final Field file = declaredField(type, "file");
            if (file != null && url.getValue(file) instanceof StringReference string) {
                final String fileValue = string.value();
                final int query = fileValue.lastIndexOf('?');
                return decodeUrlPath(query < 0 ? fileValue : fileValue.substring(0, query));
            }
        }
        throw new IllegalStateException("could not read URL.path or URL.file");
    }

    /** Decodes a URL path as UTF-8, leaving the original spelling if decoding fails. */
    private static String decodeUrlPath(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    /** Read-only snapshot of compiler inputs and the reads that produced them. */
    public static final class DiscoveryResult {
        private final String localJdkPath;
        private final int targetMajorVersion;
        private final Walk walk;
        private final @Nullable String problem;

        /** Captures the walk and combines its problems for reporting. */
        private DiscoveryResult(String localJdkPath, int targetMajorVersion, Walk walk) {
            this.localJdkPath = localJdkPath;
            this.targetMajorVersion = targetMajorVersion;
            this.walk = walk;
            problem = walk.problems.isEmpty() ? null
                : "classpath discovery was incomplete: " + String.join("; ", walk.problems);
        }

        /** Returns the local system image used by ECJ. */
        public String localJdkPath() {
            return localJdkPath;
        }

        /** Returns the target major Java version. */
        public int targetMajorVersion() {
            return targetMajorVersion;
        }

        /** Returns de-duplicated entries in launch and loader traversal order. */
        public Set<String> applicationClasspath() {
            return Collections.unmodifiableSet(walk.entries);
        }

        /** Reports the missing parts of the last attempt, if any. */
        public @Nullable String problem() {
            return problem;
        }

        /** Tests whether an input is incomplete or the selected loader has never been walked. */
        public boolean needsWalk(@Nullable ClassLoaderReference loader) {
            return problem != null || (loader != null && !walk.visited.contains(loader));
        }
    }
}
