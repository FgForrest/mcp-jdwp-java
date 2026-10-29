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
 * Discovers the full classpath of a target JVM by exploring its classloader hierarchy. Necessary
 * for Tomcat / container applications where most JARs are loaded dynamically via custom
 * classloaders and are not visible in the initial `java.class.path` system property.
 * <p>
 * NOT a Spring bean — instantiated manually by {@link JDIConnectionService} with a
 * `VirtualMachine` instance per discovery call. The result is cached on the connection service.
 * <p>
 * Discovery aggregates entries from three sources, in order:
 * 1. The target VM's `java.class.path` system property.
 * 2. The chain of `URLClassLoader` instances reachable from the suspended thread's context
 * classloader (via `getURLs()` invocations).
 * 3. Tomcat `WebappClassLoaderBase` instances detected the same way.
 * <p>
 * Discovery aborts with {@link JdkDiscoveryService.JdkNotFoundException} if no local JDK matching
 * the target version is found via {@link JdkDiscoveryService} — JDT requires
 * {@code --system <jdkPath>} to compile against the target's system classes.
 */
public class ClasspathDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(ClasspathDiscoverer.class);

    private final VirtualMachine vm;

    public ClasspathDiscoverer(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Runs the full discovery sequence: locates a matching local JDK first (fail-fast), then walks
     * the suspended thread's context classloader chain collecting JAR entries from each
     * URL-capable classloader. The thread MUST already be suspended at a JDI method-invocation
     * event so the in-VM `invokeMethod` calls succeed.
     *
     * @param suspendedThread thread suspended at a breakpoint, step, or class-prepare event
     * @return discovery result with local JDK path and the aggregated application classpath set
     * @throws JdkDiscoveryService.JdkNotFoundException if no matching local JDK installation can be found
     */
    public DiscoveryResult discoverFullClasspath(ThreadReference suspendedThread)
        throws JdkDiscoveryService.JdkNotFoundException {
        final long startTime = System.currentTimeMillis();

        try {
            // STEP 1 (CRITICAL): Discover matching local JDK FIRST
            // This MUST succeed before we continue with application classpath discovery
            final JdkDiscoveryService jdkDiscovery = new JdkDiscoveryService(vm);
            final String localJdkPath = jdkDiscovery.discoverMatchingJdk(suspendedThread);

            log.info("[Discoverer] Local JDK found: {}", localJdkPath);

            // STEP 2: Discover application classpath from classloaders
            final Set<String> classpathEntries = new LinkedHashSet<>();

            // 2a. Get the initial java.class.path (might be incomplete but good to include)
            addInitialClasspath(suspendedThread, classpathEntries);

            // 2b. Get Thread.getContextClassLoader()
            final ClassLoaderReference contextClassLoader = getContextClassLoader(suspendedThread);
            if (contextClassLoader == null) {
                log.warn("[Discoverer] Context classloader is null, falling back to initial classpath only");
                return new DiscoveryResult(localJdkPath, classpathEntries, jdkDiscovery.getTargetMajorVersion());
            }

            // 2c. Prepare ClassType references for known classloader types
            final ClassType urlClassLoaderClass = getClassTypeSafe("java.net.URLClassLoader");
            final ClassType webappClassLoaderBaseClass = getClassTypeSafe("org.apache.catalina.loader.WebappClassLoaderBase");

            // 2d. Traverse classloader hierarchy
            final Set<ClassLoaderReference> visitedClassLoaders = new HashSet<>();
            ClassLoaderReference currentClassLoader = contextClassLoader;

            while (currentClassLoader != null && visitedClassLoaders.add(currentClassLoader)) {
                final ReferenceType clType = currentClassLoader.referenceType();
                log.debug("[Discoverer] Inspecting ClassLoader: {}", clType.name());

                // Try to extract URLs from this classloader
                final boolean isUrlClassLoader = urlClassLoaderClass != null && isAssignableTo(clType, urlClassLoaderClass);
                final boolean isWebappClassLoader = webappClassLoaderBaseClass != null
                    && isAssignableTo(clType, webappClassLoaderBaseClass);
                if (isUrlClassLoader || isWebappClassLoader) {
                    extractUrlsFromClassLoader(currentClassLoader, suspendedThread, classpathEntries);
                } else {
                    log.debug("[Discoverer] ClassLoader {} is not a recognized URL-based classloader", clType.name());
                }

                // Move to parent classloader
                currentClassLoader = getParentClassLoader(currentClassLoader, suspendedThread);
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

    /**
     * Invokes `System.getProperty("java.class.path")` in the target VM via `INVOKE_SINGLE_THREADED`
     * and adds each parsed entry to `classpathEntries`. Failures are logged and swallowed because
     * the follow-up classloader traversal usually fills in the missing entries anyway.
     */
    private void addInitialClasspath(ThreadReference suspendedThread, Set<String> classpathEntries) {
        try {
            final ClassType systemClass = (ClassType) vm.classesByName("java.lang.System").get(0);
            final Method getPropertyMethod = systemClass.methodsByName("getProperty", "(Ljava/lang/String;)Ljava/lang/String;").get(0);
            final Value result;
            try (PinnedMirrors pins = new PinnedMirrors()) {
                final StringReference pathArg = pins.string(vm, "java.class.path");
                result = systemClass.invokeMethod(
                    suspendedThread,
                    getPropertyMethod,
                    Collections.singletonList(pathArg),
                    ClassType.INVOKE_SINGLE_THREADED
                );
            }

            if (result instanceof StringReference stringRef) {
                final String initialClasspath = stringRef.value();
                if (initialClasspath != null && !initialClasspath.isEmpty()) {
                    // Target-OS heuristic: presence of `;` implies Windows, otherwise assume Unix.
                    // May be wrong on hybrid Cygwin/WSL setups where both separators appear.
                    final String separator = initialClasspath.contains(";") ? ";" : ":";
                    final String[] entries = initialClasspath.split(separator, -1);
                    for (String entry : entries) {
                        if (!entry.trim().isEmpty()) {
                            classpathEntries.add(entry.trim());
                        }
                    }
                    log.debug("[Discoverer] Added {} entries from initial java.class.path", entries.length);
                }
            }
        } catch (Exception e) {
            log.warn("[Discoverer] Could not retrieve initial java.class.path: {}", e.getMessage());
        }
    }

    /**
     * Invokes `Thread.getContextClassLoader()` on the suspended thread to obtain its context
     * classloader. Returns `null` on any error so the caller can fall back to the initial classpath
     * only.
     */
    @Nullable
    private static ClassLoaderReference getContextClassLoader(ThreadReference suspendedThread) {
        try {
            final Method getContextClassLoaderMethod = suspendedThread.referenceType()
                .methodsByName("getContextClassLoader", "()Ljava/lang/ClassLoader;").get(0);

            final Value result = suspendedThread.invokeMethod(
                suspendedThread,
                getContextClassLoaderMethod,
                Collections.emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            );

            return (ClassLoaderReference) result;
        } catch (Exception e) {
            log.error("[Discoverer] Failed to get context classloader", e);
            return null;
        }
    }

    /**
     * Looks up a {@link ClassType} by name in the target VM, returning `null` instead of throwing
     * when the class isn't visible. Used to optionally probe for classloader types like
     * `java.net.URLClassLoader` and `org.apache.catalina.loader.WebappClassLoaderBase` that may or
     * may not be present.
     */
    @Nullable
    private ClassType getClassTypeSafe(String className) {
        try {
            final List<ReferenceType> classes = vm.classesByName(className);
            if (!classes.isEmpty()) {
                return (ClassType) classes.get(0);
            }
        } catch (Exception e) {
            log.debug("[Discoverer] ClassType {} not found: {}", className, e.getMessage());
        }
        return null;
    }

    /**
     * Walks the target VM's type hierarchy to determine whether `type` is assignable to `target`.
     * Operates on JDI mirrors only — does not consult the MCP server's own class hierarchy.
     */
    private static boolean isAssignableTo(ReferenceType type, ClassType target) {
        try {
            if (type instanceof ClassType classType) {
                return classType.equals(target) || classType.allInterfaces().contains(target) || isSuperclassOf(classType, target);
            }
        } catch (Exception e) {
            log.debug("[Discoverer] Error checking type assignability: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Walks the target VM's superclass chain looking for `target`.
     */
    private static boolean isSuperclassOf(ClassType classType, ClassType target) {
        try {
            ClassType current = classType;
            while (current != null) {
                if (current.equals(target)) {
                    return true;
                }
                current = current.superclass();
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Reflectively invokes {@code getURLs()} on a URLClassLoader (or compatible subclass) in the target JVM
     * via JDI method invocation, then extracts file paths from the returned URL array.
     */
    private static void extractUrlsFromClassLoader(
        ClassLoaderReference classLoaderRef,
        ThreadReference suspendedThread,
        Set<String> classpathEntries
    ) {
        try {
            final ReferenceType clType = classLoaderRef.referenceType();
            final List<Method> getUrlsMethods = clType.methodsByName("getURLs", "()[Ljava/net/URL;");

            if (getUrlsMethods.isEmpty()) {
                log.debug("[Discoverer] ClassLoader {} does not have getURLs() method", clType.name());
                return;
            }

            final Method getUrlsMethod = getUrlsMethods.get(0);
            final Value result = classLoaderRef.invokeMethod(
                suspendedThread,
                getUrlsMethod,
                Collections.emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            );

            if (!(result instanceof ArrayReference urlsArray)) {
                return;
            }
            int urlCount = 0;

            for (Value urlValue : urlsArray.getValues()) {
                if (urlValue instanceof ObjectReference urlRef) {
                    final String path = extractPathFromUrl(urlRef, suspendedThread);
                    if (path != null && !path.isEmpty()) {
                        classpathEntries.add(path);
                        urlCount++;
                    }
                }
            }

            log.debug("[Discoverer] Extracted {} URLs from {}", urlCount, clType.name());

        } catch (Exception e) {
            log.warn("[Discoverer] Error extracting URLs from classloader: {}", e.getMessage());
        }
    }

    /**
     * Invokes `URL.getPath()` in the target VM and returns the URL-decoded result.
     */
    @Nullable
    private static String extractPathFromUrl(ObjectReference urlRef, ThreadReference suspendedThread) {
        try {
            final ClassType urlClass = (ClassType) urlRef.referenceType();
            final Method getPathMethod = urlClass.methodsByName("getPath", "()Ljava/lang/String;").get(0);

            final Value result = urlRef.invokeMethod(
                suspendedThread,
                getPathMethod,
                Collections.emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            );

            if (!(result instanceof StringReference stringRef)) {
                return null;
            }
            // URL paths may be URL-encoded (e.g., spaces as %20) — decode if needed
            return decodeUrlPath(stringRef.value());
        } catch (Exception e) {
            log.debug("[Discoverer] Error extracting path from URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort URL decode (e.g., `%20` → space); returns the original path on failure.
     */
    private static String decodeUrlPath(String path) {
        try {
            // Simple URL decoding (replace %20 with space, etc.)
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // If decoding fails, return the original path
            return path;
        }
    }

    /**
     * Invokes `ClassLoader.getParent()` to walk up the classloader hierarchy; `null` ends the chain.
     */
    @Nullable
    private static ClassLoaderReference getParentClassLoader(
        ClassLoaderReference classLoaderRef, ThreadReference suspendedThread) {
        try {
            final ReferenceType clType = classLoaderRef.referenceType();
            final List<Method> getParentMethods = clType.methodsByName("getParent", "()Ljava/lang/ClassLoader;");

            if (getParentMethods.isEmpty()) {
                return null;
            }

            final Method getParentMethod = getParentMethods.get(0);
            final Value result = classLoaderRef.invokeMethod(
                suspendedThread,
                getParentMethod,
                Collections.emptyList(),
                ClassType.INVOKE_SINGLE_THREADED
            );

            return (ClassLoaderReference) result;
        } catch (Exception e) {
            log.debug("[Discoverer] Could not get parent classloader: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Result of classpath discovery containing both JDK path and application classpath. The
     * `applicationClasspath` is an insertion-ordered {@link LinkedHashSet} preserving the
     * classloader-traversal order so the eventual classpath string keeps the parent-first
     * resolution semantics that JDT expects.
     */
    public record DiscoveryResult(
        String localJdkPath,
        Set<String> applicationClasspath,
        int targetMajorVersion
    ) {

    }
}
