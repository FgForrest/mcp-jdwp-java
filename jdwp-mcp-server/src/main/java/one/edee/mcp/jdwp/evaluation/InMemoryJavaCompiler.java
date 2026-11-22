package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.compiler.parser.TerminalToken;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles Java source for the expression-evaluation pipeline using Eclipse JDT (ECJ) via JSR-199.
 * <p>
 * Compilation output is captured in memory through {@link MemoryJavaFileManager}, but the input source still
 * round-trips through a short-lived temp directory because JDT's standard file-object API requires a real
 * `javax.tools.JavaFileObject` backed by a path. The temp directory is unconditionally deleted in `finally`.
 * <p>
 * Requires {@link #configure(String, String, int)} to be called first with the path to a target-matching JDK
 * (used as {@code --system <jdkPath>} so JDT can resolve {@code java.*} system classes) and the target VM's classpath.
 * Calling {@link #compile(String, String)} without configuration throws {@link JdiEvaluationException}.
 * <p>
 * Source/target version is derived from the major version: `1.8` for Java 8, the bare number for Java 9+.
 * The `-g` flag is always passed so the evaluator can resolve local variable names from the captured bytecode.
 */
@Service
public class InMemoryJavaCompiler {
    private static final Logger log = LoggerFactory.getLogger(InMemoryJavaCompiler.class);

    /** A volatile reference publishes the complete configuration in one write. */
    private volatile @Nullable Configuration configuration;

    /**
     * Immutable options for one compiler configuration.
     *
     * @param jdkPath root of the target-matching JDK, passed as {@code --system} so ECJ can
     *     resolve system classes
     * @param classpath target classpath and any local fallback entries, joined with the host
     *     {@link File#pathSeparator} for ECJ's {@code -classpath} argument
     * @param targetMajorVersion target JVM's major version, used for ECJ's source and target
     *     options and clamped to 8 when the target version is unknown
     */
    record Configuration(String jdkPath, String classpath, int targetMajorVersion) {}

    /**
     * Returns the immutable options, or {@code null} when unconfigured. The evaluator reads this
     * after publishing options on a tool worker or dispatcher to pair them with its cache.
     */
    @Nullable Configuration currentConfiguration() {
        return configuration;
    }

    /** Problem code shared by unresolved local values and prefixes of qualified names. */
    private static final String UNRESOLVED_VARIABLE = Integer.toString(IProblem.UnresolvedVariable);

    /** ECJ problem codes that can indicate a name or type resolvable by additional classpath entries. */
    private static final Set<String> MISSING_TYPE_CODES = Set.of(
        Integer.toString(IProblem.UndefinedType), Integer.toString(IProblem.UndefinedName),
        UNRESOLVED_VARIABLE, Integer.toString(IProblem.ImportNotFound),
        Integer.toString(IProblem.IsClassPathCorrect), Integer.toString(IProblem.IsClassPathCorrectWithReferencingType),
        Integer.toString(IProblem.MissingTypeInMethod),
        Integer.toString(IProblem.MissingTypeInConstructor), Integer.toString(IProblem.MissingTypeInLambda));

    /**
     * Compilation failed on a reference that additional classpath entries might resolve.
     * The evaluator uses this signal to defer a fast probe while local discovery is pending.
     */
    static final class MissingClasspathTypes extends JdiEvaluationException {
        /** Serialization version for this compiler exception. */
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /** Captures the ECJ error text that led the compiler to identify missing types. */
        MissingClasspathTypes(String message) {
            super(message);
        }
    }

    /**
     * Materialises the source for {@code className} under {@code tempDir}, creating any required
     * package subdirectories. Returns the path of the written {@code .java} file.
     */
    /**
     * "Line 7 ({@code return (Object) (sold());}): The method sold() is undefined …" — the line of
     * the generated source the problem is on, so it reads without the temporary file.
     */
    static String describeProblem(long lineNumber, String message, String[] sourceLines) {
        if (lineNumber < 1 || lineNumber > sourceLines.length) {
            return message;
        }
        String code = sourceLines[(int) lineNumber - 1].trim();
        if (code.length() > 100) {
            code = code.substring(0, 100) + "…";
        }
        return "Line " + lineNumber + (code.isEmpty() ? "" : " (`" + code + "`)") + ": " + message;
    }

    private static Path writeSourceFileToTemp(Path tempDir, String className, String sourceCode) throws IOException {
        final @Nullable String[] packageAndClass = getPackageAndClassName(className);
        Path packageDir = tempDir;
        if (packageAndClass[0] != null) {
            packageDir = tempDir.resolve(packageAndClass[0].replace('.', File.separatorChar));
            Files.createDirectories(packageDir);
        }
        final Path sourceFile = packageDir.resolve(packageAndClass[1] + ".java");
        Files.writeString(sourceFile, sourceCode);
        return sourceFile;
    }

    /**
     * Splits a fully qualified class name into `[packageName, simpleName]`. Returns `[null, simpleName]`
     * for default-package classes (no `.` in the name).
     */
    @Nullable
    private static String[] getPackageAndClassName(String fullClassName) {
        final int lastDot = fullClassName.lastIndexOf('.');
        if (lastDot == -1) {
            return new String[]{null, fullClassName};
        }
        return new String[]{fullClassName.substring(0, lastDot), fullClassName.substring(lastDot + 1)};
    }

    /**
     * Configures the compiler with a JDK path, classpath, and target version. A non-positive
     * {@code targetMajorVersion} silently clamps to 8 (the lowest version JDT can target).
     * Publishing the immutable options through the volatile reference lets {@link #compile}
     * capture a consistent snapshot without synchronizing with this method.
     *
     * @param jdkPath            file path to the root of a JDK matching the target VM's major version
     * @param classpath          merged classpath joined with the host path separator
     * @param targetMajorVersion target JVM major version (e.g., 8, 11, 17, 21). Values below 1 fall back to 8.
     */
    public void configure(String jdkPath, String classpath, int targetMajorVersion) {
        final long startTime = System.currentTimeMillis();
        configuration = new Configuration(jdkPath, classpath, targetMajorVersion > 0 ? targetMajorVersion : 8);

        if (classpath.isEmpty()) {
            log.warn("[Compiler] Classpath is empty, application classes may not be resolved.");
        }

        final long elapsed = System.currentTimeMillis() - startTime;
        final String[] classpathEntries = classpath.split(File.pathSeparator);
        log.info("[Compiler] Configured with JDK at {} and {} classpath entries in {}ms",
            jdkPath, classpathEntries.length, elapsed);
    }

    /**
     * Returns the compiler to its unconfigured state. The evaluator calls this before remote
     * discovery when the JDK path is absent, including on first use after reconnect. Clearing the
     * snapshot prevents bytecode from being resolved against the previous target's classpath if
     * discovery fails. {@link #compile} throws the "not configured" error until {@link #configure}
     * publishes options for the current connection.
     */
    public void reset() {
        configuration = null;
    }

    /**
     * Compiles `sourceCode` into bytecode for `className`. The returned map is keyed by fully qualified
     * class name and includes any inner/anonymous classes JDT emits. Reads the immutable config
     * snapshot once, so a concurrent local classpath merge can publish new options without a
     * lock and this compilation still uses one consistent set of options.
     *
     * @param className  fully qualified name of the primary class declared in `sourceCode`
     * @param sourceCode complete `.java` source text (must declare a package matching `className`)
     * @return map of fully qualified class name to compiled bytecode
     * @throws JdiEvaluationException if {@link #configure} was never called, if temp file I/O fails,
     *                                or if JDT reports any `Diagnostic.Kind.ERROR` diagnostic
     */
    public Map<String, byte[]> compile(String className, String sourceCode) throws JdiEvaluationException {
        return compile(className, sourceCode, configuration);
    }

    /**
     * Compiles on the evaluator's tool worker or dispatcher using options captured with its wrapper
     * cache. Returns the generated bytecode by class name. Throws {@link JdiEvaluationException}
     * if options are missing, compiler I/O fails, or ECJ rejects the source.
     */
    Map<String, byte[]> compile(String className, String sourceCode, @Nullable Configuration config)
        throws JdiEvaluationException {
        if (config == null) {
            throw new JdiEvaluationException(
                "Compiler is not configured. Please call configure() with a valid JDK path and classpath."
            );
        }

        final long startTime = System.currentTimeMillis();

        final JavaCompiler compiler = new EclipseCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        Path tempDir = null;
        try (StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, null, null);
             MemoryJavaFileManager fileManager = new MemoryJavaFileManager(standardFileManager)) {

            // 1. Write source to a temp file (tempDir captured for cleanup in finally)
            tempDir = Files.createTempDirectory("mcp-compiler-");
            final Path sourceFile = writeSourceFileToTemp(tempDir, className, sourceCode);

            // 2. Get a JavaFileObject for the temporary file
            final Iterable<? extends JavaFileObject> compilationUnits = standardFileManager.getJavaFileObjects(sourceFile.toFile());

            // 3. Build compiler options and run the compilation task
            final List<String> options = buildCompilerOptions(config);
            final JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, compilationUnits
            );

            final boolean success = task.call();

            if (!success) {
                // One line per distinct problem — the compiler can report one twice — with the
                // offending source line instead of the temporary file's path.
                final String[] sourceLines = sourceCode.split("\n", -1);
                final String errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> describeProblem(d.getLineNumber(), d.getMessage(null), sourceLines))
                    .distinct()
                    .collect(Collectors.joining("\n"));
                final long elapsed = System.currentTimeMillis() - startTime;
                log.error("[Compiler] Compilation failed after {}ms:\n{}", elapsed, errors);
                final String message = "Compilation failed:\n" + errors;
                final boolean missingTypes = diagnostics.getDiagnostics().stream()
                    .anyMatch(d -> mayResolveWithClasspath(d, sourceCode));
                throw missingTypes ? new MissingClasspathTypes(message) : new JdiEvaluationException(message);
            }

            final Map<String, byte[]> compiledBytecode = fileManager.getCompiledBytecode();
            final long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Compiler] Compilation successful in {}ms ({} bytes generated for {} class(es))",
                elapsed, compiledBytecode.values().stream().mapToInt(b -> b.length).sum(), compiledBytecode.size());
            return compiledBytecode;

        } catch (IOException e) {
            throw new JdiEvaluationException("Failed to create or write temporary source file for compilation.", e);
        } finally {
            // 4. Clean up the temporary directory
            if (tempDir != null) {
                try (Stream<Path> paths = Files.walk(tempDir)) {
                    //noinspection ResultOfMethodCallIgnored
                    paths.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                } catch (IOException e) {
                    log.warn("[Compiler] Failed to delete temporary compilation directory: {}", tempDir, e);
                }
            }
        }
    }

    /** Returns whether adding classpath entries could resolve this ECJ error. */
    private static boolean mayResolveWithClasspath(Diagnostic<? extends JavaFileObject> problem, String source) {
        return problem.getKind() == Diagnostic.Kind.ERROR && MISSING_TYPE_CODES.contains(problem.getCode())
            && !(UNRESOLVED_VARIABLE.equals(problem.getCode()) && isUnqualifiedName(problem, source));
    }

    /**
     * Returns whether ECJ's inclusive range spans one identifier without a following dot.
     * The ECJ scanner handles comments and Unicode escapes. An unreadable range keeps the existing classification.
     */
    private static boolean isUnqualifiedName(Diagnostic<? extends JavaFileObject> problem, String source) {
        final long start = problem.getStartPosition();
        final long end = problem.getEndPosition();
        if (start < 0 || end < start || end >= source.length()) {
            return false;
        }
        final Scanner scanner = new Scanner();
        scanner.setSource(source.toCharArray());
        scanner.resetTo((int) start, source.length() - 1);
        try {
            return scanner.getNextToken() == TerminalToken.TokenNameIdentifier
                && scanner.getCurrentTokenEndPosition() == end
                && scanner.getNextToken() != TerminalToken.TokenNameDOT;
        } catch (InvalidInputException unreadableRange) {
            return false;
        }
    }

    /**
     * Builds the JDT compiler option list from the configured target version, JDK path, and classpath.
     */
    private static List<String> buildCompilerOptions(Configuration config) {
        final int targetMajorVersion = config.targetMajorVersion();
        final String versionStr = targetMajorVersion <= 8 ? "1." + targetMajorVersion : String.valueOf(targetMajorVersion);
        final List<String> options = new ArrayList<>(Arrays.asList("-source", versionStr, "-target", versionStr));
        options.add("-g"); // Preserve local variable names
        options.addAll(Arrays.asList("--system", config.jdkPath()));
        if (!config.classpath().isEmpty()) {
            options.addAll(Arrays.asList("-classpath", config.classpath()));
        }
        return options;
    }

    /**
     * Captures compiled bytecode in memory while delegating all read operations (source lookup,
     * type resolution, classpath scanning) to the underlying standard file manager. Only
     * {@link #getJavaFileForOutput} is overridden — JDT writes class files through that hook,
     * and we redirect those writes to per-class {@link ByteArrayOutputStream} instances.
     */
    private static class MemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputStreams = new HashMap<>();

        MemoryJavaFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, FileObject sibling)
            throws IOException {
            return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + ".class"), kind) {
                @Override
                public OutputStream openOutputStream() {
                    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    outputStreams.put(className, bos);
                    return bos;
                }
            };
        }

        /**
         * Returns a snapshot copy of the captured bytecode keyed by fully qualified class name.
         * Safe to call after the file manager has been closed because the byte arrays are detached
         * from the underlying streams.
         */
        Map<String, byte[]> getCompiledBytecode() {
            final Map<String, byte[]> bytecodeMap = new HashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> entry : outputStreams.entrySet()) {
                bytecodeMap.put(entry.getKey(), entry.getValue().toByteArray());
            }
            return bytecodeMap;
        }
    }
}
