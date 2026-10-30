package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers a local JDK installation matching the target JVM's Java version. The Eclipse JDT
 * compiler used by {@link InMemoryJavaCompiler} needs {@code --system <jdkPath>} to resolve
 * {@code java.*} system classes when compiling expression-evaluator wrapper classes; without this
 * discovery the compiler can't produce bytecode for the target.
 * <p>
 * One-shot, stateful helper: holds {@link #targetMajorVersion} after a successful
 * {@link #discoverMatchingJdk} so callers can read it without re-running discovery. NOT a Spring
 * bean — instantiated manually by {@link ClasspathDiscoverer} per discovery call.
 * <p>
 * Search strategy (in order):
 * 1. The target JVM's own {@code java.home} if accessible from the MCP server's filesystem.
 * 2. The {@code JAVA_HOME} environment variable, but only when its {@code <jdkHome>/release} file
 * confirms a matching major version (a mismatched JAVA_HOME would later trigger cryptic JDT class-file
 * errors).
 * 3. Common per-OS install paths (Adoptium, Oracle, OpenJDK, Zulu on Windows; {@code /usr/lib/jvm},
 * {@code /opt}, SDKMAN under {@code ~/.sdkman/candidates/java} on Linux/macOS).
 * 4. Directory scan of those parent paths for any subdirectory matching a {@code <name>-<version>}
 * pattern containing the major version.
 */
public class JdkDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(JdkDiscoveryService.class);

    private final VirtualMachine vm;
    /**
     * Target JVM major version, populated as a side effect of {@link #discoverMatchingJdk}; 0 until then.
     */
    private int targetMajorVersion;

    public JdkDiscoveryService(VirtualMachine vm) {
        this.vm = vm;
    }

    /**
     * Parses both the legacy `1.8.x` scheme (returns 8) and the modern `<major>.x.x` scheme
     * (returns the first dot-separated integer). Returns 0 on parse failure.
     */
    private static int extractMajorVersion(String version) {
        // Handle both "1.8.0_xxx" (Java 8) and "11.0.21" (Java 9+) formats
        if (version.startsWith("1.8")) {
            return 8;
        }

        // For Java 9+, major version is the first number
        final String[] parts = version.split("\\.", -1);
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            log.warn("[JDK Discovery] Could not parse version: {}", version);
            return 0;
        }
    }

    /**
     * Four-strategy fallback search documented on the class. Returns the first valid JDK home
     * found, or `null` if every strategy fails (the caller throws {@link JdkNotFoundException}).
     */
    @Nullable
    private static String findLocalJdk(int majorVersion, String targetHome) {
        // Strategy 1: Check if target JVM's java.home is accessible locally
        if (isValidJdkHome(targetHome)) {
            log.debug("[JDK Discovery] Target JVM home is accessible locally: {}", targetHome);
            return targetHome;
        }

        // Strategy 2: JAVA_HOME env var, only if its major version matches the target.
        // A mismatched JAVA_HOME would silently produce class-file-version errors from JDT later.
        final String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && !javaHomeEnv.isEmpty() && isValidJdkHome(javaHomeEnv)) {
            final int javaHomeMajor = readMajorVersionFromRelease(javaHomeEnv);
            if (javaHomeMajor == majorVersion) {
                log.debug("[JDK Discovery] JAVA_HOME matches Java {}: {}", majorVersion, javaHomeEnv);
                return javaHomeEnv;
            }
            log.debug("[JDK Discovery] JAVA_HOME points at Java {} but target is Java {} — skipping",
                javaHomeMajor, majorVersion);
        }

        // Strategy 3: Search common JDK installation directories
        final List<String> searchPaths = getCommonJdkPaths(majorVersion);

        for (String path : searchPaths) {
            if (isValidJdkHome(path)) {
                log.debug("[JDK Discovery] Found JDK at: {}", path);
                return path;
            }
        }

        // Strategy 4: Search for any JDK with matching major version
        return searchDirectoriesForJdk(majorVersion);
    }

    /**
     * Reads {@code JAVA_VERSION="…"} from the {@code <jdkHome>/release} file (Java 9+ ships it
     * unconditionally) and returns the parsed major version. Returns 0 if the file is missing
     * or malformed — callers should treat that as "version unknown, don't use".
     */
    private static int readMajorVersionFromRelease(String jdkHome) {
        final Path release = Paths.get(jdkHome, "release");
        if (!Files.isRegularFile(release)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(release)) {
            return lines
                .filter(l -> l.startsWith("JAVA_VERSION="))
                .findFirst()
                .map(l -> l.substring("JAVA_VERSION=".length()).replace("\"", "").trim())
                .map(JdkDiscoveryService::extractMajorVersion)
                .orElse(0);
        } catch (Exception e) {
            log.debug("[JDK Discovery] Failed to read {}: {}", release, e.getMessage());
            return 0;
        }
    }

    /**
     * Returns OS-sensitive list of common JDK install paths. On Windows checks Adoptium, Oracle
     * Java, OpenJDK, and Zulu directories under `Program Files`; on Linux/macOS checks
     * `/usr/lib/jvm`, `/opt`, and SDKMAN (`~/.sdkman/candidates/java/<version>-<vendor>`).
     * <p>
     * SDKMAN entries are enumerated dynamically (the patch and vendor suffix make a static path
     * impractical) and ordered with the newest patch first by numeric semver comparison.
     */
    private static List<String> getCommonJdkPaths(int majorVersion) {
        final List<String> paths = new ArrayList<>();

        // Windows paths
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            paths.add(String.format("C:\\Program Files\\Eclipse Adoptium\\jdk-%d", majorVersion));
            paths.add(String.format("C:\\Program Files\\Java\\jdk-%d", majorVersion));
            paths.add(String.format("C:\\Program Files\\OpenJDK\\jdk-%d", majorVersion));
            paths.add(String.format("C:\\Program Files\\Zulu\\zulu-%d", majorVersion));
        } else {
            // Linux/macOS paths
            paths.add(String.format("/usr/lib/jvm/java-%d-openjdk", majorVersion));
            paths.add(String.format("/usr/lib/jvm/java-%d-openjdk-amd64", majorVersion));
            paths.add(String.format("/usr/lib/jvm/jdk-%d", majorVersion));
            paths.add(String.format("/opt/jdk-%d", majorVersion));

            // SDKMAN: ~/.sdkman/candidates/java/<major>.<minor>.<patch>-<vendor>
            final File sdkmanDir = new File(System.getProperty("user.home"), ".sdkman/candidates/java");
            if (sdkmanDir.isDirectory()) {
                final File[] candidates = sdkmanDir.listFiles();
                if (candidates != null) {
                    final String prefix = majorVersion + ".";
                    Arrays.stream(candidates)
                        .filter(File::isDirectory)
                        .filter(c -> c.getName().startsWith(prefix))
                        .sorted(Comparator.comparing(File::getName, JdkDiscoveryService::compareSdkmanVersion).reversed())
                        .forEach(c -> paths.add(c.getAbsolutePath()));
                }
            }
        }

        return paths;
    }

    /**
     * Numeric comparator for SDKMAN folder names like `17.0.18-tem` vs `17.0.5-tem`. Splits the
     * part before the first dash by `.`, compares dot-separated segments as integers, and falls
     * back to natural string ordering for any non-numeric segment. This avoids the lexicographic
     * trap where "17.0.5" sorts higher than "17.0.18".
     */
    private static int compareSdkmanVersion(String a, String b) {
        final String[] aParts = a.split("-", 2)[0].split("\\.");
        final String[] bParts = b.split("-", 2)[0].split("\\.");
        final int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            final String ap = i < aParts.length ? aParts[i] : "0";
            final String bp = i < bParts.length ? bParts[i] : "0";
            try {
                final int cmp = Integer.compare(Integer.parseInt(ap), Integer.parseInt(bp));
                if (cmp != 0) {
                    return cmp;
                }
            } catch (NumberFormatException e) {
                final int cmp = ap.compareTo(bp);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return a.compareTo(b);
    }

    /**
     * Filesystem scan of the common JDK parent directories looking for a subdirectory whose name
     * contains `jdk` or `java` and matches a `-<major>` / `_<major>` version-suffix pattern.
     * Returns the first valid JDK home found via {@link #isValidJdkHome}.
     */
    @Nullable
    private static String searchDirectoriesForJdk(int majorVersion) {
        final List<Path> searchDirs = new ArrayList<>();

        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            searchDirs.add(Paths.get("C:\\Program Files\\Eclipse Adoptium"));
            searchDirs.add(Paths.get("C:\\Program Files\\Java"));
            searchDirs.add(Paths.get("C:\\Program Files\\OpenJDK"));
        } else {
            searchDirs.add(Paths.get("/usr/lib/jvm"));
            searchDirs.add(Paths.get("/opt"));
        }

        for (Path searchDir : searchDirs) {
            if (!Files.exists(searchDir)) {
                continue;
            }

            try (Stream<Path> paths = Files.list(searchDir)) {
                final Optional<Path> matchingJdk = paths
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().contains("jdk") ||
                        p.getFileName().toString().contains("java"))
                    .filter(p -> {
                        final String name = p.getFileName().toString();
                        return name.contains("-" + majorVersion) ||
                            name.contains("_" + majorVersion) ||
                            name.matches(".*jdk" + majorVersion + ".*");
                    })
                    .filter(p -> isValidJdkHome(p.toString()))
                    .findFirst();

                if (matchingJdk.isPresent()) {
                    return matchingJdk.get().toString();
                }
            } catch (Exception e) {
                log.debug("[JDK Discovery] Error searching {}: {}", searchDir, e.getMessage());
            }
        }

        return null;
    }

    /**
     * Detects whether `path` points to a valid JDK home using three layout markers:
     * - Java 9+: presence of `jmods/` or `lib/jrt-fs.jar`.
     * - Java 8: presence of `lib/rt.jar`.
     * - JDK-with-bundled-JRE layout: presence of `jre/lib/rt.jar`.
     */
    private static boolean isValidJdkHome(String path) {
        if (path.isEmpty()) {
            return false;
        }

        final File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }

        // Check for JDK markers
        // Java 9+: jmods directory or lib/jrt-fs.jar
        if (new File(dir, "jmods").exists() || new File(dir, "lib/jrt-fs.jar").exists()) {
            return true;
        }

        // Java 8: lib/rt.jar
        if (new File(dir, "lib/rt.jar").exists()) {
            return true;
        }

        // Also check in jre subdirectory (some JDK distributions)
        return new File(dir, "jre/lib/rt.jar").exists();
    }

    /**
     * Returns 0 until {@link #discoverMatchingJdk} has been called successfully.
     */
    public int getTargetMajorVersion() {
        return targetMajorVersion;
    }

    /**
     * Runs the search strategy and returns the absolute path of a JDK home directory matching the
     * target JVM. Side effect: populates {@link #targetMajorVersion}. The thrown
     * {@link JdkNotFoundException} carries a user-actionable error message listing the common
     * installation paths the search probed.
     *
     * @param suspendedThread thread suspended at a breakpoint or step (used to invoke
     *                        `System.getProperty` in the target VM)
     * @return path to the local JDK home directory
     * @throws JdkNotFoundException if no matching JDK is found anywhere on the search path
     */
    public String discoverMatchingJdk(ThreadReference suspendedThread) throws JdkNotFoundException {
        try {
            // 1. Get target JVM version
            final String targetVersion = getTargetJavaVersion(suspendedThread);
            final String targetHome = getTargetJavaHome(suspendedThread);

            log.info("[JDK Discovery] Target JVM: Java {} at {}", targetVersion, targetHome);

            // 2. Extract major version (e.g., "11" from "11.0.21")
            final int targetMajorVersion = extractMajorVersion(targetVersion);
            this.targetMajorVersion = targetMajorVersion;

            log.info("[JDK Discovery] Looking for Java {} JDK on MCP server...", targetMajorVersion);

            // 3. Search for matching JDK
            final String localJdkPath = findLocalJdk(targetMajorVersion, targetHome);

            if (localJdkPath != null) {
                log.info("[JDK Discovery] Found matching JDK: {}", localJdkPath);
                return localJdkPath;
            }

            // 4. Not found - throw explicit error
            final String errorMessage = String.format("""
                    No local JDK installation found for Java %d.

                    The target JVM is running Java %s, but the MCP server cannot find a matching JDK.

                    To fix this:
                      1. Install a Java %d JDK on the MCP server, or
                      2. Point JAVA_HOME at an existing Java %d installation before launching the MCP server.
                      3. Locations searched automatically:
                         - $JAVA_HOME (when JAVA_VERSION in <jdkHome>/release matches)
                         - C:\\Program Files\\Eclipse Adoptium\\jdk-%d.*
                         - C:\\Program Files\\Java\\jdk-%d.*
                         - C:\\Program Files\\OpenJDK\\jdk-%d.*
                         - /usr/lib/jvm/java-%d-openjdk*
                         - /usr/lib/jvm/jdk-%d*
                         - /opt/jdk-%d*
                         - ~/.sdkman/candidates/java/%d.*

                    Expression evaluation requires access to JDK system classes.""",
                targetMajorVersion, targetVersion, targetMajorVersion, targetMajorVersion,
                targetMajorVersion, targetMajorVersion, targetMajorVersion,
                targetMajorVersion, targetMajorVersion, targetMajorVersion,
                targetMajorVersion
            );

            throw new JdkNotFoundException(errorMessage);

        } catch (JdkNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new JdkNotFoundException("Failed to discover JDK: " + e.getMessage(), e);
        }
    }

    /**
     * Invokes `System.getProperty("java.version")` in the target VM.
     */
    private String getTargetJavaVersion(ThreadReference suspendedThread) throws Exception {
        return getSystemProperty(suspendedThread, "java.version");
    }

    /**
     * Invokes `System.getProperty("java.home")` in the target VM.
     */
    private String getTargetJavaHome(ThreadReference suspendedThread) throws Exception {
        return getSystemProperty(suspendedThread, "java.home");
    }

    /**
     * Invokes {@code System.getProperty(name)} in the target VM via JDI and returns the string value.
     */
    private String getSystemProperty(ThreadReference suspendedThread, String propertyName) throws Exception {
        final ClassType systemClass = (ClassType) vm.classesByName("java.lang.System").get(0);
        final Method getPropertyMethod = systemClass.methodsByName("getProperty", "(Ljava/lang/String;)Ljava/lang/String;").get(0);
        final Value result;
        try (PinnedMirrors pins = new PinnedMirrors()) {
            final StringReference nameArg = pins.string(vm, propertyName);
            result = systemClass.invokeMethod(
                suspendedThread,
                getPropertyMethod,
                Collections.singletonList(nameArg),
                ClassType.INVOKE_SINGLE_THREADED
            );
        }

        if (!(result instanceof StringReference sr)) {
            throw new IllegalStateException(
                "System.getProperty(\"" + propertyName + "\") returned null — property not set in the target VM");
        }
        return sr.value();
    }

    /**
     * Exception thrown when no matching JDK is found.
     */
    public static class JdkNotFoundException extends Exception {
        @Serial
        private static final long serialVersionUID = -9005487074919610686L;

        public JdkNotFoundException(String message) {
            super(message);
        }

        public JdkNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
