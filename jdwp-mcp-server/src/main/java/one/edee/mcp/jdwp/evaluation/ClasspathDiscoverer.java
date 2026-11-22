package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.JDIConnectionService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Discovers the target's launch classpath and additional repositories from the stopped thread's
 * context classloader chain. {@link JDIConnectionService} caches the result per connection.
 * Standard JDK loaders are read through JDI. A loader with a custom or unreadable URL layout can
 * fall back to invoking its {@code getURLs()} on the compilation thread.
 */
public class ClasspathDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(ClasspathDiscoverer.class);

    /** Declaring class of the standard URL loader's path and accessor. */
    private static final String URL_LOADER = "java.net.URLClassLoader";
    /** Declaring class of the application and platform loaders' path on JDK 9+. */
    private static final String BUILTIN_LOADER = "jdk.internal.loader.BuiltinClassLoader";
    /** JVM signature shared by the URL accessor and its overrides. */
    private static final String GET_URLS_SIGNATURE = "()[Ljava/net/URL;";

    private final VirtualMachine vm;

    /** Binds discovery to the target VM. */
    public ClasspathDiscoverer(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Locates a matching local JDK, then collects the launch classpath and loader URLs in order.
     *
     * @param suspendedThread compilation thread, suspended at an event that permits the custom loader fallback
     * @return discovery result with the local JDK and ordered application classpath
     * @throws JdkDiscoveryService.JdkNotFoundException if no matching local JDK can be found
     */
    public DiscoveryResult discoverFullClasspath(ThreadReference suspendedThread)
        throws JdkDiscoveryService.JdkNotFoundException {
        final long startTime = System.currentTimeMillis();

        try {
            final JdkDiscoveryService jdkDiscovery = new JdkDiscoveryService(vm);
            final String localJdkPath = jdkDiscovery.discoverMatchingJdk();
            log.info("[Discoverer] Local JDK found: {}", localJdkPath);

            final Set<String> classpathEntries = new LinkedHashSet<>();
            addInitialClasspath(classpathEntries);
            final boolean hasLaunchClasspath = !classpathEntries.isEmpty();
            final Set<ClassLoaderReference> visited = new HashSet<>();

            @Nullable ClassLoaderReference loader = readLoaderField(suspendedThread,
                declaredField("java.lang.Thread", "contextClassLoader"));
            final @Nullable Field parent = loader == null ? null : declaredField("java.lang.ClassLoader", "parent");
            while (loader != null && visited.add(loader)) {
                addLoaderClasspath(loader, suspendedThread, hasLaunchClasspath, classpathEntries);
                // Use ClassLoader.parent even when the concrete loader declares a field with the same name.
                loader = readLoaderField(loader, parent);
            }

            final long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Discoverer] Application classpath discovered in {}ms ({} entries)", elapsed, classpathEntries.size());
            return new DiscoveryResult(localJdkPath, classpathEntries, jdkDiscovery.getTargetMajorVersion());
        } catch (JdkDiscoveryService.JdkNotFoundException e) {
            throw e;
        } catch (Exception e) {
            final long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Discoverer] Error discovering full classpath after {}ms", elapsed, e);
            throw new RuntimeException("Classpath discovery failed: " + e.getMessage(), e);
        }
    }

    /** Adds JDWP ClassPaths entries in launch order, keeping the legacy whitespace handling. */
    private void addInitialClasspath(Set<String> entries) {
        if (!(vm instanceof PathSearchingVirtualMachine pathSearchingVm)) {
            return;
        }
        try {
            for (final String path : pathSearchingVm.classPath()) {
                final String trimmed = path.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
        } catch (Exception e) {
            log.warn("[Discoverer] Could not retrieve target launch classpath: {}", e.getMessage());
        }
    }

    /** Locates a field on its JDK declaring type, independently of subclass field hiding. */
    private @Nullable Field declaredField(String className, String fieldName) {
        final List<ReferenceType> types = vm.classesByName(className);
        return types.isEmpty() ? null : declaredField(types.get(0), fieldName);
    }

    /** Scans just the declaring class to avoid fetching inherited and interface metadata over JDWP. */
    private static @Nullable Field declaredField(ReferenceType type, String name) {
        for (final Field field : type.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    /** Finds the closest candidate URLClassPath field in the loader's superclass chain. */
    private static @Nullable Field urlClassPathField(ClassType type) {
        for (ClassType current = type; current != null && !"java.lang.ClassLoader".equals(current.name());
             current = current.superclass()) {
            final Field field = declaredField(current, "ucp");
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    /** Finds a virtual getURLs() override before reaching its standard JDK implementation. */
    private static @Nullable Method customUrlAccessor(ClassType type, ReferenceType jdkLoader) {
        for (ClassType current = type; current != null && !current.equals(jdkLoader); current = current.superclass()) {
            for (final Method method : current.methods()) {
                if (method.name().equals("getURLs") && method.signature().equals(GET_URLS_SIGNATURE)) {
                    return method;
                }
            }
        }
        return null;
    }

    /** Reads a context or parent loader, returning null when the chain ends or a read fails. */
    private static @Nullable ClassLoaderReference readLoaderField(ObjectReference object, @Nullable Field field) {
        if (field == null) {
            return null;
        }
        try {
            if (object.getValue(field) instanceof ClassLoaderReference loader) {
                return loader;
            }
        } catch (Exception e) {
            log.debug("[Discoverer] Could not read loader field {}: {}", field.name(), e.getMessage());
        }
        return null;
    }

    /** Uses the JDK URL layout where it describes the loader's effective URL list. */
    private static void addLoaderClasspath(ClassLoaderReference loader, ThreadReference suspendedThread,
                                           boolean hasLaunchClasspath, Set<String> entries) {
        try {
            final ClassType type = (ClassType) loader.referenceType();
            final Field ucp = urlClassPathField(type);
            if (ucp != null) {
                final ReferenceType owner = ucp.declaringType();
                final boolean builtin = BUILTIN_LOADER.equals(owner.name());
                if (builtin && hasLaunchClasspath) {
                    // The application loader converts launch entries to file URLs, changing relative
                    // paths and directory suffixes. Preserve the classpath spellings JDWP supplied.
                    return;
                }
                if (URL_LOADER.equals(owner.name())) {
                    final Method custom = customUrlAccessor(type, owner);
                    if (custom != null) {
                        // A container can store repositories separately from the inherited ucp.
                        addInvokedUrls(loader, suspendedThread, custom, entries);
                        return;
                    }
                }
                if (addFieldUrls(loader, ucp, builtin, entries)) {
                    return;
                }
            }
            final List<Method> accessors = type.methodsByName("getURLs", GET_URLS_SIGNATURE);
            if (!accessors.isEmpty()) {
                addInvokedUrls(loader, suspendedThread, accessors.get(0), entries);
            }
        } catch (Exception e) {
            log.warn("[Discoverer] Error extracting URLs from classloader: {}", e.getMessage());
        }
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

    /** Invokes a custom loader's accessor under the caller's compilation lease. */
    private static void addInvokedUrls(ClassLoaderReference loader, ThreadReference thread, Method getUrls,
                                      Set<String> entries) throws Exception {
        log.debug("[Discoverer] Falling back to getURLs() on {}", loader.referenceType().name());
        final Value result = loader.invokeMethod(thread, getUrls, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
        if (result instanceof ArrayReference urls) {
            addUrlPaths(urls.getValues(), entries);
        }
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
        try {
            final ReferenceType type = url.referenceType();
            final Field path = declaredField(type, "path");
            if (path != null) {
                // URL.getPath() returns this field directly, without the URL's protocol or host.
                final Value value = url.getValue(path);
                return value instanceof StringReference string ? decodeUrlPath(string.value()) : null;
            }
            final Field file = declaredField(type, "file");
            if (file != null && url.getValue(file) instanceof StringReference string) {
                // URL.file can include a query, which is excluded from URL.path.
                final String fileValue = string.value();
                final int query = fileValue.lastIndexOf('?');
                return decodeUrlPath(query < 0 ? fileValue : fileValue.substring(0, query));
            }
        } catch (RuntimeException e) {
            log.debug("[Discoverer] Error extracting path from URL: {}", e.getMessage());
        }
        return null;
    }

    /** Decodes a URL path as UTF-8, leaving the original spelling if decoding fails. */
    private static String decodeUrlPath(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    /** Local JDK path and target classpath entries in insertion order. */
    public record DiscoveryResult(String localJdkPath, Set<String> applicationClasspath, int targetMajorVersion) {
    }
}
