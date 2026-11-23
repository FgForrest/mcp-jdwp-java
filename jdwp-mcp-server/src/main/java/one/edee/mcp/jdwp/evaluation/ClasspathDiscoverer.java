package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.InvocationRunner;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads the launch classpath and the context and defining loader chains. Only overrides of
 * Thread.getContextClassLoader() on a tool worker and the public URLClassLoader.getURLs() contract may invoke.
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
    /** Reuses a completed accessor call if this attempt loses a cache-publication race. */
    private @Nullable ContextOutcome contextOutcome;

    /** Binds discovery and optional invocation admission to the target connection. */
    public ClasspathDiscoverer(VirtualMachine vm, EvaluationGuard guard) {
        this.vm = vm;
        this.guard = guard;
    }

    /**
     * Extends the previous walk without invoking a thread's context getter.
     */
    public DiscoveryResult discoverClasspath(ThreadReference thread, @Nullable ClassLoaderReference definingLoader,
                                             @Nullable DiscoveryResult previous)
        throws JdkDiscoveryService.JdkNotFoundException {
        return discoverClasspath(thread, definingLoader, previous, false);
    }

    /**
     * Extends the previous walk with the selected frame's loader and retries transient failures.
     * Only a bounded tool worker can opt into invoking its own pending context getter.
     */
    public DiscoveryResult discoverClasspath(ThreadReference thread, @Nullable ClassLoaderReference definingLoader,
                                             @Nullable DiscoveryResult previous, boolean tool)
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
        walk.discover(thread, definingLoader, tool && InvocationRunner.isWorkerFor(thread));
        return new DiscoveryResult(jdkPath, targetMajor, walk);
    }

    /** Whether this call must still publish a getter outcome after the connection cache changed. */
    public boolean hasContextOutcome() {
        return contextOutcome != null;
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

    /** A deferred getter and its diagnostic, owned by the thread that supplied it. */
    private record PendingContext(ThreadReference thread, Method getter, String owner, String problem) {
        /** A collected mirror or known dead thread can no longer service a pending getter. */
        boolean active() {
            try {
                return thread.status() != ThreadReference.THREAD_STATUS_ZOMBIE;
            } catch (ObjectCollectedException | IllegalThreadStateException e) {
                return false;
            } catch (RuntimeException e) {
                // A failed status query gives no evidence of thread death.
                return true;
            }
        }
    }

    /** The outcome of one getter call, including a successful null result. */
    private record ContextOutcome(PendingContext lookup, @Nullable ClassLoaderReference loader,
                                  @Nullable String failure) {}

    /**
     * A single attempt, seeded from successful reads in the previous attempt. The published walk is
     * read-only. Replaying the roots preserves child-to-parent order even when a parent is found later.
     */
    private final class Walk {
        private @Nullable Set<String> launch;
        private boolean contextComplete;
        private final Map<Long, @Nullable ClassLoaderReference> contexts = new LinkedHashMap<>();
        private final Map<Long, PendingContext> pendingContexts = new LinkedHashMap<>();
        private final Map<Long, String> unsupported = new LinkedHashMap<>();
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
                contextComplete = old.contextComplete;
                contexts.putAll(old.contexts);
                pendingContexts.putAll(old.pendingContexts);
                pendingContexts.values().removeIf(lookup -> !lookup.active());
                unsupported.putAll(old.unsupported);
                definingRoots.addAll(old.definingRoots);
                urls.putAll(old.urls);
                parents.putAll(old.parents);
            }
        }

        /** Visits the initial context chain followed by defining chains in first-seen order. */
        private void discover(ThreadReference thread, @Nullable ClassLoaderReference definingLoader, boolean tool) {
            if (launch == null) {
                readLaunch();
            }
            final Set<String> launchEntries = launch;
            if (launchEntries != null) {
                entries.addAll(launchEntries);
            }
            final boolean hasLaunchEntries = !entries.isEmpty();
            if (!contextComplete || tool) {
                readContext(thread, tool);
            }
            if (definingLoader != null) {
                definingRoots.add(definingLoader);
            }
            for (final ClassLoaderReference context : contexts.values()) {
                visit(context, thread, hasLaunchEntries);
            }
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

        /** Captures the inherited field and retries an effective getter only on its owning tool job. */
        private void readContext(ThreadReference thread, boolean tool) {
            final long id = thread.uniqueID();
            String owner = THREAD;
            boolean knownGetter = true;
            if (!contextComplete && !pendingContexts.containsKey(id)) {
                try {
                    final ReferenceType type = thread.referenceType();
                    owner = type.name();
                    if (type instanceof ClassType threadClass) {
                        final Method getter = override(threadClass, THREAD, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
                        if (getter != null) {
                            final String problem = owner + ": getContextClassLoader() pending for tool evaluation";
                            pendingContexts.put(id, new PendingContext(thread, getter, owner, problem));
                            log.debug("[Discoverer] {}", problem);
                        }
                    }
                } catch (RuntimeException e) {
                    incomplete(owner, "could not inspect getContextClassLoader(): " + describe(e));
                    knownGetter = false;
                }
            }
            if (tool) {
                final PendingContext lookup = pendingContexts.get(id);
                if (contextOutcome == null && lookup != null) {
                    contextOutcome = invokeContextGetter(lookup);
                }
                final ContextOutcome outcome = contextOutcome;
                if (outcome != null) {
                    if (outcome.failure() == null) {
                        contexts.put(id, outcome.loader());
                        pendingContexts.remove(id);
                        contextComplete = true;
                        return;
                    }
                    final PendingContext attempted = outcome.lookup();
                    pendingContexts.put(id, new PendingContext(thread, attempted.getter(), attempted.owner(),
                        outcome.failure()));
                }
            }
            if (!contextComplete) {
                try {
                    contexts.put(id, asLoader(thread.getValue(requiredField(THREAD, "contextClassLoader"))));
                    contextComplete = knownGetter;
                } catch (RuntimeException e) {
                    incomplete(owner, "could not read contextClassLoader: " + describe(e));
                }
            }
        }

        /** Invokes one getter and keeps a refused or failed lookup pending for the same thread. */
        private ContextOutcome invokeContextGetter(PendingContext lookup) {
            final ThreadReference thread = lookup.thread();
            try {
                return new ContextOutcome(lookup, asLoader(invokeAccessor(thread, thread, lookup.getter())), null);
            } catch (Exception e) {
                final String problem = lookup.owner() + ": getContextClassLoader() pending after " + describe(e);
                log.warn("[Discoverer] Classpath discovery was incomplete: {}", problem);
                log.debug("[Discoverer] Using the inherited contextClassLoader after the getter failed");
                return new ContextOutcome(lookup, null, problem);
            }
        }

        /** Walks a parent chain, retrying failed URL and parent reads independently. */
        private void visit(@Nullable ClassLoaderReference root, ThreadReference thread, boolean hasLaunchEntries) {
            for (ClassLoaderReference loader = root; loader != null && visited.add(loader); ) {
                LoaderUrls paths = urls.get(loader);
                final boolean needsUrls = paths == null && !unsupported.containsKey(loader.uniqueID());
                if (needsUrls || !parents.containsKey(loader)) {
                    try {
                        final ClassType type = (ClassType) loader.referenceType();
                        if (needsUrls) {
                            paths = readLoaderUrls(loader, type, thread, hasLaunchEntries);
                        }
                        if (!parents.containsKey(loader)) {
                            readParent(loader, type);
                        }
                    } catch (ObjectCollectedException e) {
                        incomplete("loader #" + loader.uniqueID(), "could not read loader type: " + describe(e));
                    }
                }
                if (paths != null && (!paths.builtin() || !hasLaunchEntries)) {
                    entries.addAll(paths.entries());
                }
                loader = parents.get(loader);
            }
        }

        /** Caches supported URL reads and terminal layouts, leaving failed JDI reads retryable. */
        private @Nullable LoaderUrls readLoaderUrls(ClassLoaderReference loader, ClassType type, ThreadReference thread,
                                                    boolean hasLaunchEntries) {
            final Set<String> found = new LinkedHashSet<>();
            try {
                final LoaderUrls paths = readUrls(loader, type, thread, hasLaunchEntries, found);
                if (paths != null) {
                    urls.put(loader, paths);
                    return paths;
                }
                final String problem = type.name() + ": no readable URLClassPath layout";
                if (!unsupported.containsValue(problem)) {
                    log.warn("[Discoverer] Classpath discovery was incomplete: {}", problem);
                }
                unsupported.put(loader.uniqueID(), problem);
            } catch (Exception e) {
                incomplete(type.name(), "could not read loader URLs: " + describe(e));
                entries.addAll(found);
            }
            return null;
        }

        /** Stores a successful parent read, including a genuine null. */
        private void readParent(ClassLoaderReference loader, ClassType type) {
            try {
                if (parentField == null) {
                    parentField = requiredField(LOADER, "parent");
                }
                parents.put(loader, asLoader(loader.getValue(parentField)));
            } catch (RuntimeException e) {
                incomplete(type.name(), "could not read parent: " + describe(e));
            }
        }

        /** Records the affected class and precise failure for the compiler and diagnose. */
        private void incomplete(String owner, String reason) {
            final String problem = owner + ": " + reason;
            problems.add(problem);
            log.warn("[Discoverer] Classpath discovery was incomplete: {}", problem);
        }
    }

    /** Reads a loader's URLs, returning null for a terminal layout without a public fallback. */
    private @Nullable LoaderUrls readUrls(ClassLoaderReference loader, ClassType type, ThreadReference thread,
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
        return null;
    }

    /** Reads URLClassPath.path in insertion order, excluding the ArrayList's spare capacity. */
    private static boolean addFieldUrls(ClassLoaderReference loader, Field ucp, boolean builtin, Set<String> entries) {
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

        /** Captures the walk and its diagnostics for reporting. */
        private DiscoveryResult(String localJdkPath, int targetMajorVersion, Walk walk) {
            this.localJdkPath = localJdkPath;
            this.targetMajorVersion = targetMajorVersion;
            this.walk = walk;

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

        /** Reports distinct class and reason pairs, including pending getters whose threads are alive. */
        public @Nullable String problem() {
            final Map<String, Integer> counts = new LinkedHashMap<>();
            for (final PendingContext lookup : walk.pendingContexts.values()) {
                if (lookup.active()) {
                    counts.merge(lookup.problem(), 1, Integer::sum);
                }
            }
            for (final String issue : walk.problems) {
                counts.merge(issue, 1, Integer::sum);
            }
            for (final String issue : walk.unsupported.values()) {
                counts.merge(issue, 1, Integer::sum);
            }
            if (counts.isEmpty()) {
                return null;
            }
            final StringJoiner details = new StringJoiner("; ", "classpath discovery was incomplete: ", "");
            int shown = 0;
            for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (shown == 4) {
                    details.add((counts.size() - shown) + " more issues");
                    break;
                }
                final int count = entry.getValue();
                details.add(count == 1 ? entry.getKey() : entry.getKey() + " (" + count + ")");
                shown++;
            }
            return details.toString();
        }

        /** Tests whether a transient read failed or the selected loader has never been walked. */
        public boolean needsWalk(@Nullable ClassLoaderReference loader) {
            return !walk.problems.isEmpty() || (loader != null && !walk.visited.contains(loader));
        }

        /** Tests whether this thread has a pending effective-context lookup. */
        public boolean hasPendingContext(ThreadReference thread) {
            return walk.pendingContexts.containsKey(thread.uniqueID());
        }
    }
}
