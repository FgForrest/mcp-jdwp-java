package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.request.*;
import one.edee.mcp.jdwp.discovery.DiagnoseReportRenderer;
import one.edee.mcp.jdwp.discovery.JvmDescriptor;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.marks.MarkInfo;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.Watcher;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single MCP-facing surface for the JDWP debugger. Each {@code @McpTool} method is auto-discovered
 * by the Spring AI MCP framework and exposed as an invocable tool; a small number of read-only
 * snapshots are additionally exposed as {@code @McpResource} URIs so users can attach them via
 * the {@code @server:uri} mention picker without burning a tool call. Per-tool / per-resource
 * contracts (parameters, behaviour, error formats) live in the annotation {@code description}
 * strings and are NOT duplicated in this JavaDoc.
 * <p>
 * Architecture: every tool method is a thin orchestration layer over the underlying services
 * ({@link JDIConnectionService}, {@link BreakpointTracker}, {@link JdiExpressionEvaluator},
 * {@link EventHistory}, {@link WatcherManager}). Methods run on the MCP server's worker threads,
 * never on the JDI event listener thread.
 * <p>
 * Error convention: tool methods never throw — they catch every exception, format a human-readable
 * message starting with `Error:`, `[ERROR]`, `[TIMEOUT]`, or `[INTERRUPTED]`, and return it as a
 * `String`. The MCP client is expected to surface these messages verbatim.
 */
@Service
public class JDWPTools {
    private static final Logger log = LoggerFactory.getLogger(JDWPTools.class);

    /**
     * Default JDWP port. Resolved at class load via the `-DJVM_JDWP_PORT` system property
     * (typically passed by the MCP client through `.mcp.json`); falls back to 5005 if unset.
     */
    private static final int JVM_JDWP_PORT = Integer.parseInt(
        System.getProperty("JVM_JDWP_PORT", "5005")
    );
    /**
     * Allow-list of package prefixes treated as "noise" by `isNoiseFrame`. Stack frames whose
     * declaring class starts with any of these are collapsed in `jdwp_get_stack` and
     * `jdwp_get_breakpoint_context` unless the caller passes `includeNoise=true`. Adding or
     * removing entries directly affects what users see in stack traces.
     */
    private static final String[] NOISE_PACKAGE_PREFIXES = {
        "org.junit.",
        "org.apache.maven.surefire.",
        "jdk.internal.reflect.",
        "java.lang.reflect.",
        "java.lang.invoke.",
        "sun.reflect.",
        "jdk.internal.invoke."
    };
    /**
     * Pre-compiled pattern for {@link #parseUnresolvedFieldName}. Recognises three JDT compile-error
     * forms: "X cannot be resolved", "field a.b.c.X is not visible", and "X is not visible".
     */
    private static final Pattern UNRESOLVED_FIELD_PATTERN = Pattern.compile(
        "([A-Za-z_][A-Za-z_0-9]*)\\s+cannot be resolved"
            + "|field\\s+\\S*?\\.([A-Za-z_][A-Za-z_0-9]*)\\s+is not visible"
            + "|([A-Za-z_][A-Za-z_0-9]*)\\s+is not visible"
    );
    private final JDIConnectionService jdiService;
    private final BreakpointTracker breakpointTracker;
    private final WatcherManager watcherManager;
    private final JdiExpressionEvaluator expressionEvaluator;
    private final EventHistory eventHistory;
    private final EvaluationGuard evaluationGuard;
    private final JvmDiscoveryService jvmDiscoveryService;
    private final MarkedInstanceRegistry markedInstances;

    public JDWPTools(JDIConnectionService jdiService, BreakpointTracker breakpointTracker,
                     WatcherManager watcherManager, JdiExpressionEvaluator expressionEvaluator,
                     EventHistory eventHistory, EvaluationGuard evaluationGuard,
                     JvmDiscoveryService jvmDiscoveryService,
                     MarkedInstanceRegistry markedInstances) {
        this.jdiService = jdiService;
        this.breakpointTracker = breakpointTracker;
        this.watcherManager = watcherManager;
        this.expressionEvaluator = expressionEvaluator;
        this.eventHistory = eventHistory;
        this.evaluationGuard = evaluationGuard;
        this.jvmDiscoveryService = jvmDiscoveryService;
        this.markedInstances = markedInstances;
    }

    /**
     * Appends a human-readable list of stack frames to {@code out}, collapsing
     * junit/maven/reflection noise frames (unless {@code includeNoiseFrames} is true) and stopping
     * after {@code limit} user frames have been rendered. Used by both {@link #jdwp_get_stack}
     * and {@link #jdwp_get_breakpoint_context} to keep the rendering identical.
     *
     * @param out                destination buffer
     * @param frames             the full frame list (typically from {@link ThreadReference#frames()})
     * @param limit              maximum number of user frames to render
     * @param includeNoiseFrames if true, render noise frames inline; if false, collapse them into a summary line
     * @param indent             prefix prepended to each frame line (e.g. {@code "  "} for the breakpoint-context dump)
     */
    private static void appendUserFrames(
        StringBuilder out,
        List<StackFrame> frames,
        int limit,
        boolean includeNoiseFrames,
        String indent
    ) {
        int rendered = 0;
        int collapsedNoise = 0;
        for (int i = 0; i < frames.size() && rendered < limit; i++) {
            final StackFrame frame = frames.get(i);
            final Location location = frame.location();
            final String declaringType = location.declaringType().name();

            if (!includeNoiseFrames && isNoiseFrame(declaringType)) {
                collapsedNoise++;
                continue;
            }

            String src;
            try {
                src = location.sourceName() + ':' + location.lineNumber();
            } catch (AbsentInformationException e) {
                src = "Unknown Source";
            }
            out.append(String.format("%s#%d %s.%s (%s)\n",
                indent, i, declaringType, location.method().name(), src));
            rendered++;
        }

        if (collapsedNoise > 0) {
            out.append(String.format("%s... %d junit/maven/reflection frame(s) collapsed (pass includeNoise=true to show)\n",
                indent, collapsedNoise));
        }
        if (rendered >= limit && frames.size() > limit + collapsedNoise) {
            final int remaining = frames.size() - limit - collapsedNoise;
            out.append(String.format("%s... %d more frame(s) hidden (raise maxFrames to see them)\n",
                indent, remaining));
        }
    }

    /**
     * Convenience overload that always collapses noise frames (used by
     * {@code jdwp_get_breakpoint_context}, which never exposes the {@code includeNoise} option to
     * the caller). See the 5-arg overload for the {@code indent} contract.
     */
    private static void appendUserFrames(StringBuilder out, List<StackFrame> frames, int limit, String indent) {
        appendUserFrames(out, frames, limit, false, indent);
    }

    /**
     * Returns true if the declaring class belongs to a known-noisy framework or JDK internal.
     */
    static boolean isNoiseFrame(String declaringType) {
        for (String prefix : NOISE_PACKAGE_PREFIXES) {
            if (declaringType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pure regex extraction of an unresolved/invisible field name from a JDT compile error message.
     * Recognises three forms emitted by the Eclipse JDT compiler:
     * <ul>
     *   <li>{@code "X cannot be resolved"} — the wrapper class never saw the identifier</li>
     *   <li>{@code "field a.b.c.X is not visible"} — the wrapper class saw the field but visibility failed</li>
     *   <li>{@code "X is not visible"} — bare-name variant emitted by some compiler versions</li>
     * </ul>
     * Static + package-private so it can be unit-tested without a JDI connection.
     *
     * @param message the raw compiler error string
     * @return the field/identifier name if any of the three patterns match, otherwise {@code null}
     */
    @Nullable
    static String parseUnresolvedFieldName(@Nullable String message) {
        if (message == null) {
            return null;
        }
        final Matcher m = UNRESOLVED_FIELD_PATTERN.matcher(message);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1)
            : m.group(2) != null ? m.group(2)
              : m.group(3);
    }

    /**
     * Parses a user-supplied char literal. Strips surrounding {@code '...'} when present so the
     * documented {@code 'a'} input form yields the character {@code a}, not the apostrophe. Throws
     * {@link IllegalArgumentException} when the resulting payload is not exactly one character.
     */
    static char parseCharInput(String valueStr) {
        String stripped = valueStr;
        if (stripped.length() >= 2 && stripped.startsWith("'") && stripped.endsWith("'")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        if (stripped.length() != 1) {
            throw new IllegalArgumentException(
                "char value must be exactly one character (optionally wrapped in single quotes), got: " + valueStr);
        }
        return stripped.charAt(0);
    }

    /**
     * Parses a string value into a JDI Value matching the target type. Supports primitives, String, and null.
     */
    @Nullable
    private static Value createJdiValue(VirtualMachine vm, String valueStr, Type targetType) throws Exception {
        if ("null".equals(valueStr)) {
            return null;
        }

        final String typeName = targetType.name();
        return switch (typeName) {
            case "int" -> vm.mirrorOf(Integer.parseInt(valueStr));
            case "long" -> vm.mirrorOf(Long.parseLong(valueStr.replace("L", "").replace("l", "")));
            case "double" -> vm.mirrorOf(Double.parseDouble(valueStr));
            case "float" -> vm.mirrorOf(Float.parseFloat(valueStr.replace("f", "").replace("F", "")));
            case "boolean" -> vm.mirrorOf(Boolean.parseBoolean(valueStr));
            case "char" -> vm.mirrorOf(parseCharInput(valueStr));
            case "byte" -> vm.mirrorOf(Byte.parseByte(valueStr));
            case "short" -> vm.mirrorOf(Short.parseShort(valueStr));
            case "java.lang.String" -> vm.mirrorOf(valueStr);
            default -> throw new IllegalArgumentException(
                "Unsupported type: " + typeName + ". Only primitives, String, and null are supported.");
        };
    }

    /**
     * Strips a single pair of surrounding double quotes from a Java string literal — the value
     * passed by callers to jdwp_set_local / jdwp_set_field includes its source quotes (e.g.
     * {@code "hello"}), but the target slot expects the raw string content.
     */
    private static String stripJavaStringQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Finds a thread by its unique ID.
     */
    @Nullable
    private static ThreadReference findThread(VirtualMachine vm, long threadId) {
        return vm.allThreads().stream()
            .filter(t -> t.uniqueID() == threadId)
            .findFirst()
            .orElse(null);
    }

    @McpTool(description = "Connect to the JDWP server using configuration from .mcp.json")
    public String jdwp_connect() {
        final String host = "localhost";
        final int port = JVM_JDWP_PORT;

        try {
            return jdiService.connect(host, port);
        } catch (Exception e) {
            return String.format("""
                    [ERROR] Connection failed to %s:%d
                    
                    Make sure your JVM is running with JDWP enabled:
                      -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:%d
                    
                    Original error: %s""",
                host, port, port, e.getMessage()
            );
        }
    }

    @McpTool(description = "Wait until a JVM is listening for JDWP and attach. Polls every 200ms until timeout. Use this to bootstrap a debug session without manually polling for the listener — call this after launching the target with `mvn test -Dmaven.surefire.debug` (or any other JVM started with `-agentlib:jdwp=...,suspend=y`).")
    public String jdwp_wait_for_attach(
        @McpToolParam(required = false, description = "Hostname (default: localhost)") @Nullable String host,
        @McpToolParam(required = false, description = "JDWP port (default: 5005 — overridable via -DJVM_JDWP_PORT)") @Nullable Integer port,
        @McpToolParam(required = false, description = "Maximum wait time in milliseconds (default: 30000)") @Nullable Integer timeoutMs) {
        final String resolvedHost = (host == null || host.isBlank()) ? "localhost" : host;
        final int resolvedPort = (port != null) ? port : JVM_JDWP_PORT;
        final int deadlineMs = (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 30_000;

        final long deadline = System.currentTimeMillis() + deadlineMs;
        int attempts = 0;
        String lastError = "none";

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                final String result = jdiService.connect(resolvedHost, resolvedPort);
                return String.format("%s (attached after %d attempt(s))", result, attempts);
            } catch (IllegalConnectorArgumentsException e) {
                // Configuration error — retrying will never make this succeed. Fail fast.
                return String.format("[ERROR] Invalid connector arguments for %s:%d — %s",
                    resolvedHost, resolvedPort, e.getMessage());
            } catch (IOException e) {
                // Connection refused or similar — JVM not listening yet, retry.
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            } catch (Exception e) {
                // Could be a transient handshake race during JVM startup; retry until deadline.
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            try {
                //noinspection BusyWait
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return String.format("[INTERRUPTED] After %d attempt(s) waiting for %s:%d. Last error: %s",
                    attempts, resolvedHost, resolvedPort, lastError);
            }
        }

        final String baseMessage = String.format("""
                [TIMEOUT] No JVM listening on %s:%d after %d attempt(s) over %dms.
                Last error: %s

                Make sure the target JVM was launched with -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:%d
                For Maven tests in this repo, use: mvn test -Dmaven.surefire.debug""",
            resolvedHost, resolvedPort, attempts, deadlineMs, lastError, resolvedPort);
        // Run discovery on timeout — surfacing the local-JVM list helps the user spot a JVM
        // that came up on a different port, or didn't come up at all. Same time budget as
        // jdwp_diagnose; failures degrade silently to just the base message.
        JDIConnectionService.@Nullable ConnectionStatus status;
        try {
            status = jdiService.getConnectionStatus();
        } catch (Exception ignored) {
            status = null;
        }
        return baseMessage + '\n' + renderLocalJvmsBlock(status, false);
    }

    @McpTool(description = "Disconnect from the JDWP server")
    public String jdwp_disconnect() {
        return jdiService.disconnect();
    }

    @McpTool(description = "Get JVM version information")
    public String jdwp_get_version() {
        try {
            final VirtualMachine vm = jdiService.getVM();
            return String.format("VM: %s\nVersion: %s\nDescription: %s",
                vm.name(), vm.version(), vm.description());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "List user threads in the JVM (status, frame count). System/JVM-internal threads (Reference Handler, Finalizer, surefire workers, etc.) are hidden unless includeSystemThreads=true.")
    public String jdwp_get_threads(
        @McpToolParam(required = false, description = "Include JVM/JDK/test-runner internal threads (default: false)") @Nullable Boolean includeSystemThreads) {
        try {
            final boolean includeSystem = includeSystemThreads != null && includeSystemThreads;
            final VirtualMachine vm = jdiService.getVM();
            final List<ThreadReference> all = vm.allThreads();
            final List<ThreadReference> threads = includeSystem
                ? all
                : all.stream().filter(t -> !ThreadFormatting.isJvmInternalThread(t)).toList();

            final StringBuilder result = new StringBuilder();
            final int hidden = all.size() - threads.size();
            result.append(String.format("Found %d thread(s)%s:\n\n",
                threads.size(),
                hidden > 0 ? String.format(" (%d system thread(s) hidden — pass includeSystemThreads=true to show)", hidden) : ""));

            for (int i = 0; i < threads.size(); i++) {
                final ThreadReference thread = threads.get(i);
                result.append(String.format("Thread %d:\n", i));
                result.append(String.format("  ID: %d\n", thread.uniqueID()));
                result.append(String.format("  Name: %s\n", thread.name()));
                result.append(String.format("  Status: %s\n", ThreadFormatting.formatStatus(thread.status())));
                result.append(String.format("  Suspended: %s\n", thread.isSuspended()));

                if (thread.isSuspended()) {
                    try {
                        final int frameCount = thread.frameCount();
                        result.append(String.format("  Frames: %d\n", frameCount));
                    } catch (IncompatibleThreadStateException e) {
                        // Thread resumed in the gap between isSuspended() and frameCount();
                        // skip the frame count rather than failing the whole listing.
                    }
                }

                result.append('\n');
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get the call stack for a specific thread. Defaults to top 10 user frames; junit/surefire/reflection internals are collapsed unless you pass includeNoise=true or raise maxFrames.")
    public String jdwp_get_stack(
        @McpToolParam(description = "Thread unique ID") long threadId,
        @McpToolParam(required = false, description = "Maximum frames to render (default: 10). Higher values include deeper call sites.") @Nullable Integer maxFrames,
        @McpToolParam(required = false, description = "If true, do not collapse junit/maven/reflection frames (default: false)") @Nullable Boolean includeNoise) {
        try {
            final int limit = (maxFrames != null && maxFrames > 0) ? maxFrames : 10;
            final boolean includeNoiseFrames = includeNoise != null && includeNoise;

            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended. Thread must be stopped at a breakpoint.";
            }

            final List<StackFrame> frames = thread.frames();
            final StringBuilder result = new StringBuilder();
            result.append(String.format("Stack trace for thread %d (%s) - %d frame(s) total:\n\n",
                threadId, thread.name(), frames.size()));

            appendUserFrames(result, frames, limit, includeNoiseFrames, "");

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get local variables for a specific frame in a thread. Also includes 'this' (cached as Object#N) for instance methods.")
    public String jdwp_get_locals(
        @McpToolParam(description = "Thread unique ID") long threadId,
        @McpToolParam(description = "Frame index (0 = current frame)") int frameIndex) {
        try {
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            final StackFrame frame = thread.frame(frameIndex);
            final StringBuilder result = new StringBuilder();
            result.append(String.format("Local variables in frame %d:\n\n", frameIndex));

            // Synthetic 'this' entry for instance methods. Cached so the user can immediately call
            // jdwp_get_fields(<id>) without a separate eval round-trip.
            final ObjectReference thisObj = frame.thisObject();
            if (thisObj != null) {
                result.append(String.format("this (%s) = %s\n",
                    thisObj.referenceType().name(),
                    formatValue(thisObj)));
            }

            final Map<LocalVariable, Value> vars = frame.getValues(frame.visibleVariables());
            for (Map.Entry<LocalVariable, Value> entry : vars.entrySet()) {
                final LocalVariable var = entry.getKey();
                final Value value = entry.getValue();
                result.append(String.format("%s (%s) = %s\n",
                    var.name(),
                    var.typeName(),
                    formatValue(value)));
            }

            appendMarkedInstancesFooter(result);
            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Appends a "marked instances visible to expressions" footer to {@code out} listing every live
     * mark so the agent can see, at every locals/context call, which {@code $label} names are
     * resolvable from the current frame. Skipped entirely when no marks are registered to keep
     * the existing locals output unchanged for the common case.
     *
     * <p>The footer is purely advisory — the locals/fields payload above it is the primary value.
     * If the registry throws while we are enumerating it (a partially-disconnected VM, a corrupted
     * mark entry, etc.) we swallow the exception, log it, and append a one-line warning so the
     * agent still receives every variable in the frame plus a hint that the marks list was
     * unavailable. Letting the exception propagate would force the outer catch to replace the
     * whole response with {@code "Error: ..."}, destroying the locals.
     */
    private void appendMarkedInstancesFooter(StringBuilder out) {
        if (markedInstances.isEmpty()) {
            return;
        }
        try {
            final List<MarkInfo> marks = markedInstances.list().stream()
                .sorted(Comparator.comparing(MarkInfo::label))
                .toList();
            out.append("\n--- Marked instances visible to expressions ---\n");
            for (MarkInfo m : marks) {
                out.append(String.format("  $%s : %s (Object#%d)%s\n",
                    m.label(), m.typeName(), m.objectId(),
                    m.collected() ? " [collected — binding will be skipped]" : ""));
            }
        } catch (Exception e) {
            log.warn("[JDWP] Marked-instances footer rendering failed: {}", e.getMessage());
            out.append(String.format("(marked-instances footer unavailable: %s)%n", e.getMessage()));
        }
    }

    @McpTool(description = "Get fields (properties) of an object by its object ID (obtained from jdwp_get_locals). Returns '[ERROR] Object #N belongs to a previous VM session ...' if the ID came from a prior connection — re-fetch via jdwp_get_locals after re-attach.")
    public String jdwp_get_fields(@McpToolParam(description = "Object unique ID") long objectId) {
        try {
            final ObjectReference cached = jdiService.getCachedObject(objectId);
            final String staleVmHint = staleVmHintIfMismatched(objectId, cached);
            if (staleVmHint != null) {
                return staleVmHint;
            }
            return jdiService.getObjectFields(objectId);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Returns a user-facing hint string when the cached object belongs to a previous VM session
     * (i.e. its {@link ObjectReference#virtualMachine()} no longer matches the live VM held by
     * {@link JDIConnectionService}). Returns {@code null} when the cache slot is empty (caller is
     * responsible for that case) or when the VM identity matches.
     *
     * <p>A silent re-attach to a different target between MCP calls leaves stale object IDs in
     * the cache; without this check the caller would see an opaque JDI error instead of an
     * actionable instruction to re-fetch the id via {@code jdwp_get_locals}.
     */
    @Nullable
    private String staleVmHintIfMismatched(long objectId, @Nullable ObjectReference cached) {
        if (cached == null) {
            return null;
        }
        final VirtualMachine liveVm;
        try {
            liveVm = jdiService.getVM();
        } catch (Exception e) {
            return null;
        }
        final VirtualMachine cachedVm;
        try {
            cachedVm = cached.virtualMachine();
        } catch (Exception e) {
            // Object reference probably belongs to a dead VM — fall through to the mismatch
            // message rather than letting the caller blow up on a downstream call.
            return staleVmMessage(objectId);
        }
        // A null cached VM means we have no identity information to compare against; treat as
        // "no mismatch detected" rather than producing a false-positive alert.
        if (cachedVm == null || cachedVm == liveVm) {
            return null;
        }
        return staleVmMessage(objectId);
    }

    private static String staleVmMessage(long objectId) {
        return String.format(
            "[ERROR] Object #%d belongs to a previous VM session — the cache entry is stale. "
                + "Re-attach state was lost across reconnects; call jdwp_get_locals() to obtain a fresh object id.",
            objectId);
    }

    /**
     * Canonical {@code [VM_DEATH]} response for tools that hit a {@link VMDisconnectedException}
     * mid-call. JDI throws this unchecked exception when the target VM dies during an in-flight
     * operation (e.g. {@code invokeMethod}, expression evaluation). Surfacing it as a generic
     * {@code "Error: ..."} loses the actionable hint that the cure is to re-attach.
     */
    private static String vmDisconnectedMessage(String operation) {
        return "[VM_DEATH] target VM disconnected during " + operation
            + " — re-attach via jdwp_connect / jdwp_wait_for_attach.";
    }

    @McpTool(description = "Invoke toString() on a cached object to get its string representation. Returns '[VM_DEATH] ...' if the target VM disconnects mid-call.")
    public String jdwp_to_string(
        @McpToolParam(description = "Object unique ID (from jdwp_get_locals or jdwp_get_fields)") long objectId,
        @McpToolParam(required = false, description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") @Nullable Long threadId) {
        try {
            final ObjectReference obj = jdiService.getCachedObject(objectId);
            if (obj == null) {
                return String.format("[ERROR] Object #%d not found in cache.\n" +
                    "Use jdwp_get_locals() to discover objects in the current scope.", objectId);
            }
            final String staleVmHint = staleVmHintIfMismatched(objectId, obj);
            if (staleVmHint != null) {
                return staleVmHint;
            }

            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread;
            if (threadId != null) {
                thread = findThread(vm, threadId);
                if (thread == null) {
                    return "Error: Thread not found with ID " + threadId;
                }
            } else {
                thread = breakpointTracker.getLastBreakpointThread();
                if (thread == null) {
                    return "Error: No suspended thread available. Provide a threadId or hit a breakpoint first.";
                }
            }

            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended.";
            }

            final Method toStringMethod = obj.referenceType()
                .methodsByName("toString", "()Ljava/lang/String;")
                .stream().findFirst().orElse(null);

            if (toStringMethod == null) {
                return String.format("Object #%d (%s): no toString() method found", objectId, obj.referenceType().name());
            }

            // Reentrancy guard: the invoked toString() may hit a user breakpoint. Mark the
            // thread as mid-evaluation so the listener suppresses the recursive hit rather
            // than re-suspending the very thread this invokeMethod is waiting on. Capture the
            // id up front so a thread death during toString() does not leak a guard entry.
            Value result;
            final long guardedThreadId = thread.uniqueID();
            evaluationGuard.enter(guardedThreadId);
            try {
                result = obj.invokeMethod(thread, toStringMethod, Collections.emptyList(),
                    ObjectReference.INVOKE_SINGLE_THREADED);
            } finally {
                evaluationGuard.exit(guardedThreadId);
            }

            if (result instanceof StringReference strRef) {
                return String.format("Object #%d (%s).toString() = \"%s\"",
                    objectId, obj.referenceType().name(), strRef.value());
            }
            return String.format("Object #%d (%s).toString() = %s",
                objectId, obj.referenceType().name(), formatValue(result));
        } catch (VMDisconnectedException vmDead) {
            return vmDisconnectedMessage("jdwp_to_string");
        } catch (Exception e) {
            return "Error invoking toString(): " + e.getMessage();
        }
    }

    @McpTool(description = "Evaluate a Java expression in the context of a suspended thread's stack frame. Returns '[VM_DEATH] ...' if the target VM disconnects mid-call.")
    public String jdwp_evaluate_expression(
        @McpToolParam(description = "Thread unique ID") long threadId,
        @McpToolParam(description = "Java expression to evaluate (e.g., 'order.getTotal()', 'x + y', 'name.length()')") String expression,
        @McpToolParam(required = false, description = "Frame index (0 = current frame, default: 0)") @Nullable Integer frameIndex) {
        try {
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }
            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended.";
            }

            expressionEvaluator.configureCompilerClasspath(thread);

            final StackFrame frame = thread.frame(frameIndex != null ? frameIndex : 0);
            final Value result = expressionEvaluator.evaluate(frame, expression);

            return String.format("Result: %s", formatValue(result));
        } catch (VMDisconnectedException vmDead) {
            return vmDisconnectedMessage("jdwp_evaluate_expression");
        } catch (Exception e) {
            final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            final String enriched = enrichEvaluationError(msg, threadId, frameIndex);
            return "Error evaluating expression: " + enriched;
        }
    }

    @McpTool(description = "Evaluate a Java expression and compare its result against an expected value. Returns 'OK' on match, 'MISMATCH' with actual vs expected on failure, or '[VM_DEATH] ...' if the target VM disconnects mid-call. Comparison is string-based against the same formatting jdwp_evaluate_expression uses (so primitives auto-unbox, strings strip surrounding quotes).")
    public String jdwp_assert_expression(
        @McpToolParam(description = "Java expression (e.g., 'order.getTotal()', 'session.getRole()', 'list.size() == 5')") String expression,
        @McpToolParam(description = "Expected value (string-compared against the formatted expression result)") String expected,
        @McpToolParam(required = false, description = "Thread ID — defaults to the last breakpoint thread") @Nullable Long threadId,
        @McpToolParam(required = false, description = "Frame index (default: 0)") @Nullable Integer frameIndex) {
        try {
            final int frame = (frameIndex != null) ? frameIndex : 0;
            final ThreadReference thread;
            if (threadId != null) {
                thread = findThread(jdiService.getVM(), threadId);
                if (thread == null) {
                    return "Error: Thread not found with ID " + threadId;
                }
            } else {
                thread = breakpointTracker.getLastBreakpointThread();
                if (thread == null) {
                    return "Error: No suspended thread available. Provide a threadId or hit a breakpoint first.";
                }
            }
            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended.";
            }

            expressionEvaluator.configureCompilerClasspath(thread);
            final StackFrame stackFrame = thread.frame(frame);
            final Value result = expressionEvaluator.evaluate(stackFrame, expression);
            final String actual = formatValue(result);

            // Strip wrapping quotes from formatted strings so users can pass `expected="hello"` or `expected=hello`.
            String compareActual = actual;
            if (compareActual.length() >= 2 && compareActual.startsWith("\"") && compareActual.endsWith("\"")) {
                compareActual = compareActual.substring(1, compareActual.length() - 1);
            }

            if (compareActual.equals(expected)) {
                return String.format("OK — %s = %s", expression, actual);
            }
            return String.format("MISMATCH — %s\n  expected: %s\n  actual:   %s", expression, expected, actual);
        } catch (VMDisconnectedException vmDead) {
            return vmDisconnectedMessage("jdwp_assert_expression");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * If the evaluator's error message looks like "X cannot be resolved" and X matches a field on
     * {@code this}'s declared type, append a hint explaining the package-private wrapper-class
     * limitation and pointing the user at {@code jdwp_get_fields(thisObjectId)}.
     */
    private String enrichEvaluationError(String originalMessage, long threadId, @Nullable Integer frameIndex) {
        final String unresolved = parseUnresolvedFieldName(originalMessage);
        if (unresolved == null) {
            return originalMessage;
        }

        try {
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null || !thread.isSuspended()) {
                return originalMessage;
            }
            final StackFrame frame = thread.frame(frameIndex != null ? frameIndex : 0);
            final ObjectReference thisObj = frame.thisObject();
            if (thisObj == null) {
                return originalMessage;
            }
            final Field field = thisObj.referenceType().allFields().stream()
                .filter(f -> f.name().equals(unresolved))
                .findFirst()
                .orElse(null);
            if (field == null) {
                return originalMessage;
            }
            jdiService.cacheObject(thisObj);
            final String thisType = thisObj.referenceType().name();
            final boolean classIsPublic = thisObj.referenceType() instanceof ClassType ct && ct.isPublic();
            final boolean fieldIsPublic = field.isPublic();
            final StringBuilder hint = new StringBuilder(originalMessage);
            hint.append("\n\nHint: '").append(unresolved).append("' is a field on this (")
                .append(thisType).append(", Object#").append(thisObj.uniqueID()).append(").");
            if (classIsPublic && fieldIsPublic) {
                hint.append(" Auto-rewrite should have handled this — please report the expression that triggered it.");
            } else {
                if (!classIsPublic) {
                    hint.append(" The enclosing class is package-private (or non-public),");
                } else {
                    hint.append(" The field is non-public,");
                }
                hint.append(" so the expression wrapper cannot reference it directly. Workaround:")
                    .append(" call jdwp_get_fields(").append(thisObj.uniqueID()).append(')')
                    .append(" to inspect the field, or jdwp_to_string for a quick view.");
            }
            return hint.toString();
        } catch (Exception probeFailure) {
            return originalMessage;
        }
    }

    @McpTool(description = "Resume execution of all threads in the VM")
    public String jdwp_resume() {
        try {
            final VirtualMachine vm = jdiService.getVM();
            vm.resume();
            return "All threads resumed";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Resume the VM and BLOCK until the next breakpoint, step, or exception event fires (or timeout). Returns the same info as jdwp_get_current_thread on success. Replaces the manual 'resume → poll → poll' choreography. Returns one of: 'Event fired ...' on a breakpoint/step/exception hit, '[TIMEOUT] ...' when the deadline expires, '[VM_DEATH] ...' when the target VM died/disconnected before any event, or 'Wait interrupted' on thread interruption.")
    public String jdwp_resume_until_event(
        @McpToolParam(required = false, description = "Maximum wait time in milliseconds (default: 30000)") @Nullable Integer timeoutMs) {
        final int deadlineMs = (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 30_000;
        try {
            final VirtualMachine vm = jdiService.getVM();
            // Arm BEFORE resume so we don't race with a near-instant event firing.
            final CountDownLatch latch = breakpointTracker.armNextEventLatch();
            vm.resume();

            final boolean fired = latch.await(deadlineMs, TimeUnit.MILLISECONDS);
            if (!fired) {
                return buildDiagnosticReport(true, deadlineMs);
            }

            // Snapshot the last breakpoint BEFORE inspecting the event tail. A breakpoint that
            // fired moments before the VM died is real, suspended state the user cares about —
            // discarding it in favour of the terminal VM_DEATH event would erase the only context
            // that explains why the BP fired. If both a live BP snapshot AND a VM_DEATH tail are
            // present, render the BP context with a suffix noting the subsequent disconnect.
            final BreakpointTracker.LastBreakpoint snapshot = breakpointTracker.getLastBreakpoint();
            final List<EventHistory.DebugEvent> tail = eventHistory.getRecent(1);
            final boolean vmDeathTail = !tail.isEmpty() && "VM_DEATH".equals(tail.get(0).type());
            final boolean haveLiveBpSnapshot = snapshot != null && snapshot.thread().isSuspended();

            if (vmDeathTail && !haveLiveBpSnapshot) {
                return "[VM_DEATH] Target VM disconnected/died while waiting. No more events will fire on this connection — "
                    + "run jdwp_diagnose to inspect, or jdwp_connect / jdwp_wait_for_attach to re-attach.";
            }

            if (snapshot == null) {
                return "Event fired but no breakpoint thread recorded (this should not happen — check the listener logs).";
            }
            final ThreadReference thread = snapshot.thread();
            final Integer bpId = snapshot.id();
            final String base = String.format("Event fired. Thread: %s (ID=%d, suspended=%s, frames=%d, breakpoint=%s)",
                thread.name(), thread.uniqueID(), thread.isSuspended(),
                thread.isSuspended() ? thread.frameCount() : -1,
                bpId);
            if (vmDeathTail) {
                return base + " (VM has since disconnected — this is the last captured breakpoint)";
            }
            return base;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "Wait interrupted";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Read-only snapshot of the WHOLE world: (1) this MCP server's status, (2) the JDWP connection (or last-attempt error if disconnected), (3) local JVMs visible to the user with their JDWP ports — answering 'did my target JVM come up, and on which port?' without ps/lsof/jps. When connected, the existing breakpoint+events report continues to appear inside block #2. Run this first when something isn't working.")
    public String jdwp_diagnose(
        @McpToolParam(required = false, description = "If true, briefly attach to each candidate JVM whose JDWP port could not be read from /proc, to discover the port via sun.jdwp.listenerAddress. Default false — costs one short attach per JVM and is visible to targets.") @Nullable Boolean inspectAll) {
        try {
            final boolean doInspectAll = inspectAll != null && inspectAll;
            return buildFullDiagnosticReport(doInspectAll);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpResource(
        uri = "jdwp://diagnose",
        name = "jdwp-diagnose",
        title = "JDWP diagnostic report",
        description = "Full diagnose snapshot: MCP-server status, JDWP connection (or last-attempt error), and local-JVM inventory with their JDWP ports. Same content as the jdwp_diagnose tool with inspectAll=false. Attach with @<server>:jdwp://diagnose to read live status without spending a model turn."
    )
    public String diagnoseResource() {
        try {
            return buildFullDiagnosticReport(false);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpResource(
        uri = "jdwp://jvms",
        name = "jdwp-jvms",
        title = "Local JVMs with their JDWP ports",
        description = "Local-JVM inventory only: which Java processes are running, which expose a JDWP agent, and the state of each port (LISTENING / SUSPENDED / UNREACHABLE / …). Cheaper than jdwp://diagnose when the only question is 'what can I attach to right now, and on which port?'"
    )
    public String jvmsResource() {
        try {
            JDIConnectionService.@Nullable ConnectionStatus status;
            try {
                status = jdiService.getConnectionStatus();
            } catch (Exception e) {
                log.debug("getConnectionStatus() threw — listing JVMs without connection context", e);
                status = null;
            }
            return renderLocalJvmsBlock(status, false);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Composes the three-block diagnose report: MCP-server status, JDWP-connection status
     * (with the existing breakpoint report inline when connected), and a local-JVM inventory.
     * Falls back gracefully if discovery throws — the connection block is always rendered.
     */
    private String buildFullDiagnosticReport(boolean inspectAll) {
        final StringBuilder out = new StringBuilder();
        out.append(DiagnoseReportRenderer.renderHeader());

        out.append(DiagnoseReportRenderer.renderMcpServerBlock(
            ProcessHandle.current().pid(),
            Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()),
            String.format("%s %s", System.getProperty("java.vm.name", "JVM"), System.getProperty("java.version", "?")),
            countMcpTools(),
            "localhost",
            JVM_JDWP_PORT,
            System.getProperty("user.dir", "?")
        ));

        JDIConnectionService.ConnectionStatus status;
        try {
            status = jdiService.getConnectionStatus();
        } catch (Exception e) {
            log.debug("getConnectionStatus() threw — rendering as disconnected", e);
            status = null;
        }
        if (status == null) {
            // Treat a null status the same as "disconnected" — downstream rendering expects a
            // non-null object and a null here would NPE further down.
            status = new JDIConnectionService.ConnectionStatus(false, null, 0, null, null);
        }
        out.append(DiagnoseReportRenderer.renderConnectionBlock(
            new DiagnoseReportRenderer.JDIConnectionStatusView(
                status.connected(), status.lastHost(), status.lastPort(),
                status.lastConnectAttempt(), status.lastConnectError()
            ),
            JVM_JDWP_PORT
        ));
        // Capabilities block — only meaningful when connected (the renderer collapses to empty
        // string when both are false). We probe the live VM defensively because the call can
        // race with a VM-death event; failure degrades to "no capabilities reported".
        if (status.connected()) {
            try {
                final VirtualMachine vm = jdiService.getRawVM();
                if (vm != null) {
                    out.append(DiagnoseReportRenderer.renderVmCapabilitiesBlock(
                        vm.canWatchFieldAccess(),
                        vm.canWatchFieldModification()));
                }
            } catch (Exception e) {
                log.debug("VM capability probe failed during diagnose", e);
            }
        }
        // Always include the breakpoint+events report — even disconnected, pending breakpoints
        // and stale watchers are useful debugging context. The renderer prefixes its own header
        // so it slots cleanly under the connection block.
        out.append('\n').append(buildDiagnosticReport(false, null));

        out.append(renderLocalJvmsBlock(status, inspectAll));
        return out.toString();
    }

    /**
     * Runs discovery and renders the local-JVM inventory block. Discovery errors are caught
     * and surfaced as a one-line note instead of breaking the whole report. Accepts a
     * nullable status so callers don't need to construct an empty stub for the disconnected case.
     */
    private String renderLocalJvmsBlock(JDIConnectionService.@Nullable ConnectionStatus status, boolean inspectAll) {
        try {
            final String connectedHost = status == null ? null : status.lastHost();
            final int connectedPort = status == null ? 0 : status.lastPort();
            List<JvmDescriptor> descriptors = jvmDiscoveryService.discover();
            descriptors = jvmDiscoveryService.confirmAll(descriptors, connectedHost, connectedPort);
            if (inspectAll) {
                descriptors = jvmDiscoveryService.inspectAll(descriptors);
            }
            final String user = System.getProperty("user.name", "?");
            return DiagnoseReportRenderer.renderJvmListBlock(descriptors, user);
        } catch (Exception e) {
            // Preserve thread-interrupt semantics: discovery may run cooperative interruptible
            // work and a swallowed InterruptedException would otherwise hide the interrupt from
            // upstream callers.
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("JVM discovery failed", e);
            return "\n▸ Local JVMs\n  (discovery failed: " + e.getMessage() + ")\n";
        }
    }

    /**
     * Reflection-based count of {@code @McpTool}-annotated methods on this class. Cheap (run
     * once per {@code jdwp_diagnose} call) and avoids hard-coding a number that drifts every
     * time a tool is added. We count public methods only — private helpers are not tools.
     */
    private static int countMcpTools() {
        int n = 0;
        for (java.lang.reflect.Method m : JDWPTools.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.isAnnotationPresent(McpTool.class)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Builds a structured snapshot of breakpoint state and recent JDI events with an interpretation
     * line, used by both {@link #jdwp_resume_until_event} on timeout and the standalone
     * {@link #jdwp_diagnose} tool. The interpretation is the actionable part — it tells the caller
     * whether retrying is pointless (pending BPs, failed bindings) or whether the BP is firing but
     * auto-resuming (logpoints, false conditions).
     */
    private String buildDiagnosticReport(boolean afterTimeout, @Nullable Integer waitedMs) {
        final Map<Integer, BreakpointRequest> activeLineBps = breakpointTracker.getAllBreakpoints();
        final Map<Integer, BreakpointTracker.PendingBreakpoint> pendingLineBps =
            breakpointTracker.getAllPendingBreakpoints();
        final Map<Integer, BreakpointTracker.ExceptionBreakpointInfo> activeExBps =
            breakpointTracker.getAllExceptionBreakpoints();
        final Map<Integer, BreakpointTracker.PendingExceptionBreakpoint> pendingExBps =
            breakpointTracker.getAllPendingExceptionBreakpoints();
        final Map<Integer, BreakpointTracker.FieldBreakpointInfo> activeFieldBps =
            breakpointTracker.getAllFieldBreakpoints();
        final Map<Integer, BreakpointTracker.PendingFieldBreakpoint> pendingFieldBps =
            breakpointTracker.getAllPendingFieldBreakpoints();
        final List<EventHistory.DebugEvent> recent = eventHistory.getRecent(10);
        final BreakpointTracker.LastBreakpoint last = breakpointTracker.getLastBreakpoint();

        final StringBuilder out = new StringBuilder();
        if (afterTimeout) {
            out.append(String.format("[TIMEOUT] No suspending event fired within %dms.%n%n", waitedMs));
        } else {
            out.append("[DIAGNOSTIC] Current debugger state:\n\n");
        }

        out.append(String.format("Active line breakpoints: %d%n", activeLineBps.size()));
        for (Map.Entry<Integer, BreakpointRequest> e : activeLineBps.entrySet()) {
            final Location loc = e.getValue().location();
            out.append(String.format("  - #%d %s:%d%s%n",
                e.getKey(), loc.declaringType().name(), loc.lineNumber(),
                renderChainSuffixForActive(e.getKey(), e.getValue().isEnabled())));
        }

        out.append(String.format("Pending line breakpoints: %d  (class not yet loaded)%n", pendingLineBps.size()));
        for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> e : pendingLineBps.entrySet()) {
            final BreakpointTracker.PendingBreakpoint pb = e.getValue();
            final String reason = pb.getFailureReason() != null
                ? "  [FAILED: " + pb.getFailureReason() + ']' : "";
            out.append(
                String.format("  - #%d %s:%d%s%s%n",
                e.getKey(), pb.getClassName(), pb.getLineNumber(), reason,
                renderChainSuffixForPending(e.getKey()))
            );
        }

        out.append(String.format("Active exception breakpoints: %d%n", activeExBps.size()));
        for (Map.Entry<Integer, BreakpointTracker.ExceptionBreakpointInfo> e : activeExBps.entrySet()) {
            out.append(String.format("  - #%d %s%s%s%n",
                e.getKey(), e.getValue().getExceptionClass(),
                e.getValue().isLogOnly() ? " (logOnly)" : "",
                renderChainSuffixForActive(e.getKey(), e.getValue().getRequest().isEnabled())));
        }

        out.append(String.format("Pending exception breakpoints: %d%n", pendingExBps.size()));
        for (Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint> e : pendingExBps.entrySet()) {
            final BreakpointTracker.PendingExceptionBreakpoint pb = e.getValue();
            final String reason = pb.getFailureReason() != null
                ? "  [FAILED: " + pb.getFailureReason() + ']' : "";
            out.append(String.format("  - #%d %s%s%s%n",
                e.getKey(), pb.getExceptionClass(), reason,
                renderChainSuffixForPending(e.getKey())));
        }

        out.append(String.format("Active field breakpoints: %d%n", activeFieldBps.size()));
        for (Map.Entry<Integer, BreakpointTracker.FieldBreakpointInfo> e : activeFieldBps.entrySet()) {
            final BreakpointTracker.FieldBreakpointSpec spec = e.getValue().getSpec();
            final EventRequest req = breakpointTracker.getEventRequestById(e.getKey());
            final boolean enabled = req != null && req.isEnabled();
            out.append(String.format("  - #%d %s.%s (%s%s)%s%n",
                e.getKey(), spec.className(), spec.fieldName(),
                spec.mode().name().toLowerCase(Locale.ROOT),
                spec.logOnly() ? ", logOnly" : "",
                renderChainSuffixForActive(e.getKey(), enabled)));
        }

        out.append(String.format("Pending field breakpoints: %d%n", pendingFieldBps.size()));
        for (Map.Entry<Integer, BreakpointTracker.PendingFieldBreakpoint> e : pendingFieldBps.entrySet()) {
            final BreakpointTracker.PendingFieldBreakpoint pf = e.getValue();
            final BreakpointTracker.FieldBreakpointSpec spec = pf.getSpec();
            final String reason = pf.getFailureReason() != null
                ? "  [FAILED: " + pf.getFailureReason() + ']' : "";
            out.append(String.format("  - #%d %s.%s (%s%s)%s%s%n",
                e.getKey(), spec.className(), spec.fieldName(),
                spec.mode().name().toLowerCase(Locale.ROOT),
                spec.logOnly() ? ", logOnly" : "",
                reason, renderChainSuffixForPending(e.getKey())));
        }

        out.append("\nLast suspending event: ");
        if (last == null) {
            out.append("never\n");
        } else {
            out.append(String.format("thread %s (id=%d), bp=%s%n",
                last.thread().name(), last.thread().uniqueID(), String.valueOf(last.id())));
        }

        out.append(String.format("%nRecent events (last %d):%n", recent.size()));
        if (recent.isEmpty()) {
            out.append("  (none — no JDI events have fired since attach/reset)\n");
        } else {
            for (EventHistory.DebugEvent ev : recent) {
                out.append(String.format("  [%s] %s (%s)%n",
                    ev.type(), ev.summary(), ev.timestamp().toString().substring(11, 23)));
            }
        }

        out.append("\nINTERPRETATION: ")
            .append(interpretDiagnostic(activeLineBps, pendingLineBps, activeExBps, pendingExBps, recent, last == null));
        return out.toString();
    }

    /**
     * Returns the {@code "  [chain: ...]"} suffix for an ACTIVE BP entry in the diagnostic
     * report, or an empty string when the BP has no chain. {@code enabled} is read from the
     * underlying JDI {@link EventRequest} — when {@code true} the BP renders as {@code ARMED},
     * otherwise {@code WAITING}.
     */
    private String renderChainSuffixForActive(int bpId, boolean enabled) {
        final BreakpointTracker.TriggerLink link = breakpointTracker.getDependencyOfDependent(bpId);
        if (link == null) {
            return "";
        }
        return String.format("  [chain: trigger=#%d, %s, %s]",
            link.triggerId(), link.oneShot() ? "one-shot" : "sticky", enabled ? "ARMED" : "WAITING");
    }

    /**
     * Returns the {@code "  [chain: ...]"} suffix for a PENDING BP entry in the diagnostic
     * report, or an empty string when the BP has no chain. Pending BPs have no underlying JDI
     * request yet, so they always render as {@code WAITING} — by construction they cannot be
     * armed until promotion.
     */
    private String renderChainSuffixForPending(int bpId) {
        final BreakpointTracker.TriggerLink link = breakpointTracker.getDependencyOfDependent(bpId);
        if (link == null) {
            return "";
        }
        return String.format("  [chain: trigger=#%d, %s, WAITING]",
            link.triggerId(), link.oneShot() ? "one-shot" : "sticky");
    }

    /**
     * Picks the most actionable one-liner from the diagnostic state. The aim is to keep the caller
     * (typically an LLM) from blindly retrying {@code jdwp_resume_until_event} with a larger timeout
     * when the real problem is a wrong class name, an unloaded class, or breakpoints that are firing
     * but auto-resuming.
     * <p>
     * The interpretation pass is chain-aware via {@link #describeChainStuckState}, which recognises
     * the "every armed BP is waiting on a trigger that has not fired" state — otherwise
     * indistinguishable from a generic armed-but-idle state.
     */
    private String interpretDiagnostic(
        Map<Integer, BreakpointRequest> activeLine,
        Map<Integer, BreakpointTracker.PendingBreakpoint> pendingLine,
        Map<Integer, BreakpointTracker.ExceptionBreakpointInfo> activeEx,
        Map<Integer, BreakpointTracker.PendingExceptionBreakpoint> pendingEx,
        List<EventHistory.DebugEvent> recent,
        boolean noPriorSuspendingEvent
    ) {
        final int activeCount = activeLine.size() + activeEx.size();
        final int pendingCount = pendingLine.size() + pendingEx.size();
        final boolean anyFailedPending =
            pendingLine.values().stream().anyMatch(p -> p.getFailureReason() != null)
                || pendingEx.values().stream().anyMatch(p -> p.getFailureReason() != null);

        if (activeCount == 0 && pendingCount == 0) {
            return "No breakpoints are armed. Set one with jdwp_set_breakpoint or "
                + "jdwp_set_exception_breakpoint before waiting again.";
        }
        if (anyFailedPending) {
            return "One or more pending breakpoints FAILED to bind (see [FAILED] entries above). "
                + "Fix the class name or line number — retrying with a larger timeout will not help.";
        }
        if (activeCount == 0 && pendingCount > 0) {
            return "All your breakpoints are PENDING — their target classes have not been loaded by "
                + "the target JVM since attach. The code path you are watching is not executing. "
                + "Verify the class name and that the entry point you expected to run has actually "
                + "been invoked. Do NOT retry with a larger timeout; the BP will never fire until "
                + "the class loads.";
        }
        // Chain-aware: are all enabled BPs sitting behind a trigger that has not fired?
        final String chainStuckMsg = describeChainStuckState(activeLine, activeEx, pendingLine, pendingEx);
        if (chainStuckMsg != null) {
            return chainStuckMsg;
        }
        final boolean autoResumedHits = recent.stream().anyMatch(e ->
            "LOGPOINT".equals(e.type()) || "BREAKPOINT_SUPPRESSED".equals(e.type())
                || "EXCEPTION_LOG".equals(e.type()) || "EXCEPTION_SUPPRESSED".equals(e.type()));
        if (autoResumedHits) {
            return "Breakpoints ARE firing but auto-resuming (logpoint, log-only exception, or "
                + "condition evaluated to false). If you want execution to stop, remove the "
                + "condition or switch to a regular (non-logpoint) breakpoint.";
        }
        if (activeCount > 0 && recent.isEmpty() && noPriorSuspendingEvent) {
            return "Breakpoint(s) armed but NO JDI events have fired since attach. Either the thread "
                + "holding the BP location is idle, the BP location is unreachable from the active "
                + "code path, or no code is currently executing in the target JVM. Check live thread "
                + "state with jdwp_get_threads before waiting again.";
        }
        return "Some events have fired but nothing suspended within the wait window. Inspect "
            + "'Recent events' above for the most likely cause.";
    }

    /**
     * If every active BP is a chained dependent currently in WAITING state (its underlying JDI
     * request is disabled because the trigger has not fired), returns an interpretation message
     * pointing the caller at the unfired trigger; otherwise returns {@code null}. This catches
     * the "my chained BP is stuck because the trigger never hit" scenario which is otherwise
     * indistinguishable from a generic "armed-but-not-fired" state.
     * <p>
     * Folds pending BPs into the tally: they cannot have an underlying JDI request yet, so they
     * are categorically WAITING. When pending BPs are present alongside active chained-WAITING
     * ones, the interpretation line calls out the pending count and an example pending ID so the
     * user sees the full picture instead of just the active half. The message
     * stays unchanged when no pending BPs are present.
     */
    @Nullable
    private String describeChainStuckState(
        Map<Integer, BreakpointRequest> activeLine,
        Map<Integer, BreakpointTracker.ExceptionBreakpointInfo> activeEx,
        Map<Integer, BreakpointTracker.PendingBreakpoint> pendingLine,
        Map<Integer, BreakpointTracker.PendingExceptionBreakpoint> pendingEx
    ) {
        final ChainStuckTally tally = new ChainStuckTally();
        for (Map.Entry<Integer, BreakpointRequest> e : activeLine.entrySet()) {
            tally.observe(e.getKey(), e.getValue().isEnabled());
        }
        for (Map.Entry<Integer, BreakpointTracker.ExceptionBreakpointInfo> e : activeEx.entrySet()) {
            tally.observe(e.getKey(), e.getValue().getRequest().isEnabled());
        }
        // Pending BPs have no JDI request yet — pass enabled=false so they always land in the
        // chained-WAITING / unchained-WAITING bucket, and remember the count separately so the
        // user-facing message can call them out explicitly.
        for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> e : pendingLine.entrySet()) {
            tally.observePending(e.getKey());
        }
        for (Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint> e : pendingEx.entrySet()) {
            tally.observePending(e.getKey());
        }
        if (tally.chainedWaiting > 0 && tally.unchainedOrArmed == 0) {
            final StringBuilder msg = new StringBuilder(256);
            msg.append(String.format("Every active BP is WAITING on a trigger. e.g. BP #%d is chained to "
                    + "trigger BP #%d, which has not fired since attach. Verify the trigger's code path "
                    + "is being exercised — or temporarily call jdwp_clear_breakpoint_dependency(#%d) "
                    + "to detach the chain.",
                tally.sampleDependentId, tally.sampleTriggerId, tally.sampleDependentId));
            if (tally.pendingCount > 0) {
                msg.append(String.format(" Additionally, %d pending BP(s) (e.g. #%d) are waiting for "
                        + "their class to load — see [PENDING] entries above.",
                    tally.pendingCount, tally.samplePendingId));
            }
            return msg.toString();
        }
        return null;
    }

    /**
     * Bucket counts for {@link #describeChainStuckState}: active BPs split into chained-waiting vs
     * unchained-or-armed, with pending BPs tallied separately because they cannot be ARMED until
     * promotion. A sample ID from each bucket is kept so the user-facing message can name names.
     */
    private final class ChainStuckTally {
        int chainedWaiting;
        int unchainedOrArmed;
        int pendingCount;
        @Nullable
        Integer sampleDependentId;
        @Nullable
        Integer sampleTriggerId;
        @Nullable
        Integer samplePendingId;

        /**
         * Classifies a single active BP into the chained-waiting vs unchained-or-armed bucket;
         * {@code enabled} is the current state of the BP's underlying JDI request.
         */
        void observe(int bpId, boolean enabled) {
            final BreakpointTracker.TriggerLink link = breakpointTracker.getDependencyOfDependent(bpId);
            if (link != null && !enabled) {
                chainedWaiting++;
                if (sampleDependentId == null) {
                    sampleDependentId = bpId;
                    sampleTriggerId = link.triggerId();
                }
            } else {
                unchainedOrArmed++;
            }
        }

        /**
         * Records a pending BP. Pending entries have no JDI request and therefore cannot be
         * classified as ARMED — they are always WAITING. The active-side tally is left untouched
         * so the chain-stuck condition still only fires when every ACTIVE BP is WAITING.
         */
        void observePending(int bpId) {
            pendingCount++;
            if (samplePendingId == null) {
                samplePendingId = bpId;
            }
        }
    }

    @McpTool(description = "Suspend a specific thread by its ID")
    public String jdwp_suspend_thread(@McpToolParam(description = "Thread unique ID") long threadId) {
        try {
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (thread.isSuspended()) {
                return String.format("Thread %d (%s) is already suspended", threadId, thread.name());
            }

            thread.suspend();
            return String.format("Thread %d (%s) suspended", threadId, thread.name());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Resume a specific thread by its ID")
    public String jdwp_resume_thread(@McpToolParam(description = "Thread unique ID") long threadId) {
        try {
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (!thread.isSuspended()) {
                return String.format("Thread %d (%s) is not suspended", threadId, thread.name());
            }

            thread.resume();
            return String.format("Thread %d (%s) resumed", threadId, thread.name());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Step over the current line (resumes the thread; JDI fires a STEP event when the next line is reached). " +
        "Thread must be suspended. After calling, use jdwp_resume_until_event to block until the step lands. " +
        "Each step is one round-trip — for jumps longer than ~3 lines, place a breakpoint at the destination and resume_until_event instead.")
    public String jdwp_step_over(
        @McpToolParam(required = false, description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") @Nullable Long threadId) {
        return doStep(threadId, StepRequest.STEP_OVER, "over");
    }

    @McpTool(description = "Step into a method call (resumes the thread; JDI fires a STEP event on the first line of the callee). " +
        "Thread must be suspended. After calling, use jdwp_resume_until_event to block until the step lands. " +
        "Most useful when polymorphic dispatch is unclear and you can't tell from source which override will actually run.")
    public String jdwp_step_into(
        @McpToolParam(required = false, description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") @Nullable Long threadId) {
        return doStep(threadId, StepRequest.STEP_INTO, "into");
    }

    @McpTool(description = "Step out of the current frame (resumes the thread; JDI fires a STEP event on the next line of the caller). " +
        "Thread must be suspended. After calling, use jdwp_resume_until_event to block until the step lands. " +
        "Useful for escaping uninteresting frames when finding the right caller line for a breakpoint would be awkward.")
    public String jdwp_step_out(
        @McpToolParam(required = false, description = "Thread unique ID (must be suspended). If omitted, uses the last breakpoint thread.") @Nullable Long threadId) {
        return doStep(threadId, StepRequest.STEP_OUT, "out");
    }

    @McpTool(description = "Set a local variable's value in a suspended thread's stack frame")
    public String jdwp_set_local(
        @McpToolParam(description = "Thread unique ID") long threadId,
        @McpToolParam(description = "Frame index (0 = current frame)") int frameIndex,
        @McpToolParam(description = "Variable name") String varName,
        @McpToolParam(description = "New value (e.g., '42', '3.14', 'true', '\"hello\"', 'null')") String value) {
        try {
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }
            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended.";
            }

            final StackFrame frame = thread.frame(frameIndex);
            final LocalVariable localVar = frame.visibleVariableByName(varName);
            if (localVar == null) {
                return String.format("Error: Variable '%s' not found in frame %d", varName, frameIndex);
            }

            final String parsedValue = "java.lang.String".equals(localVar.typeName())
                ? stripJavaStringQuotes(value) : value;

            final Value newValue = createJdiValue(vm, parsedValue, localVar.type());
            frame.setValue(localVar, newValue);

            return String.format("Variable '%s' set to %s in frame %d of thread %d", varName, value, frameIndex, threadId);
        } catch (ClassNotLoadedException notLoaded) {
            return String.format(
                "Error setting variable: type '%s' is not yet loaded in the target VM, so JDI cannot validate the assignment. "
                    + "Wait for the application to reference the type, or call jdwp_force_load_class(\"%s\") to load it now.",
                notLoaded.className(), notLoaded.className());
        } catch (Exception e) {
            return "Error setting variable: " + e.getMessage();
        }
    }

    @McpTool(description = "Set a field's value on a cached object. Returns '[ERROR] Object #N belongs to a previous VM session ...' if the ID came from a prior connection — re-fetch via jdwp_get_locals after re-attach.")
    public String jdwp_set_field(
        @McpToolParam(description = "Object unique ID (from jdwp_get_locals or jdwp_get_fields)") long objectId,
        @McpToolParam(description = "Field name") String fieldName,
        @McpToolParam(description = "New value (e.g., '42', '3.14', 'true', '\"hello\"', 'null')") String value) {
        try {
            final ObjectReference obj = jdiService.getCachedObject(objectId);
            if (obj == null) {
                return String.format("[ERROR] Object #%d not found in cache", objectId);
            }
            final String staleVmHint = staleVmHintIfMismatched(objectId, obj);
            if (staleVmHint != null) {
                return staleVmHint;
            }

            final VirtualMachine vm = jdiService.getVM();
            final Field field = obj.referenceType().fieldByName(fieldName);
            if (field == null) {
                return String.format("Error: Field '%s' not found on %s", fieldName, obj.referenceType().name());
            }

            final String parsedValue = "java.lang.String".equals(field.typeName())
                ? stripJavaStringQuotes(value) : value;

            final Value newValue = createJdiValue(vm, parsedValue, field.type());
            obj.setValue(field, newValue);

            return String.format("Field '%s.%s' set to %s", obj.referenceType().name(), fieldName, value);
        } catch (Exception e) {
            return "Error setting field: " + e.getMessage();
        }
    }

    /**
     * Single-line step (over/into/out) on the given thread; when {@code threadId} is null,
     * falls back to the last breakpoint thread.
     */
    private String doStep(@Nullable Long threadId, int stepDepth, String label) {
        try {
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread;
            if (threadId != null) {
                thread = findThread(vm, threadId);
                if (thread == null) {
                    return "Error: Thread not found with ID " + threadId;
                }
            } else {
                thread = breakpointTracker.getLastBreakpointThread();
                if (thread == null) {
                    return "Error: No suspended thread available. Provide a threadId or hit a breakpoint first.";
                }
            }

            if (!thread.isSuspended()) {
                return "Error: Thread is not suspended. Cannot step.";
            }

            final EventRequestManager erm = vm.eventRequestManager();

            // JDI permits only one StepRequest per thread; synchronising on the EventRequestManager
            // keeps the delete-then-create pair atomic against concurrent step calls.
            final StepRequest stepRequest;
            synchronized (erm) {
                erm.stepRequests().stream()
                    .filter(sr -> sr.thread().equals(thread))
                    .toList()
                    .forEach(erm::deleteEventRequest);

                stepRequest = erm.createStepRequest(thread, StepRequest.STEP_LINE, stepDepth);
                stepRequest.addCountFilter(1);
                stepRequest.enable();
            }

            thread.resume();

            return String.format("Step %s executed on thread %d (%s). Call jdwp_resume_until_event to wait for the STEP event.",
                label, thread.uniqueID(), thread.name());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Set a breakpoint at a specific line in a class. Supports conditional breakpoints. If the class is not yet loaded, the breakpoint is deferred.")
    public String jdwp_set_breakpoint(
        @McpToolParam(description = "Fully qualified class name (e.g. 'com.example.MyClass')") String className,
        @McpToolParam(description = "Line number") int lineNumber,
        @McpToolParam(required = false, description = "Suspend policy: 'all' (default), 'thread', 'none'") @Nullable String suspendPolicy,
        @McpToolParam(required = false, description = "Optional condition — only suspend when this evaluates to true (e.g., 'i > 100')") @Nullable String condition,
        @McpToolParam(required = false, description = "Optional ID of a trigger breakpoint — this BP stays disarmed until the trigger fires. Sticky by default: once armed it stays so unless re-disarmed via jdwp_disarm_until_trigger or oneShot=true.") @Nullable Integer triggerBreakpointId,
        @McpToolParam(required = false, description = "If true, re-disarm this BP after each hit so the next trigger fire re-arms it (IntelliJ-style). Default: false (sticky).") @Nullable Boolean oneShot) {
        // Track the pending ID outside the try so the catch can clean it up if locationsOfLine
        // (or any later step) throws after the pending entry has already been registered.
        Integer pendingIdForCleanup = null;
        try {
            final VirtualMachine vm = jdiService.getVM();
            final EventRequestManager erm = vm.eventRequestManager();
            if (triggerBreakpointId != null && !breakpointTracker.isKnownBreakpointId(triggerBreakpointId)) {
                return String.format("Error: Trigger breakpoint #%d does not exist", triggerBreakpointId);
            }
            final boolean effectiveOneShot = oneShot != null && oneShot;

            int jdiPolicy = EventRequest.SUSPEND_ALL;
            String policyLabel = "all";
            switch ((suspendPolicy != null ? suspendPolicy : "all").toLowerCase(Locale.ROOT)) {
                case "thread" -> {
                    jdiPolicy = EventRequest.SUSPEND_EVENT_THREAD;
                    policyLabel = "thread";
                }
                case "none" -> {
                    jdiPolicy = EventRequest.SUSPEND_NONE;
                    policyLabel = "none";
                }
                case "all" -> { /* default */ }
                default -> {
                    return String.format("Error: Invalid suspend policy '%s'. Use 'all', 'thread', or 'none'.", suspendPolicy);
                }
            }

            final String conditionInfo = condition != null && !condition.isBlank()
                ? String.format(", condition: %s", condition) : "";

            final ReferenceType eagerType = jdiService.findOrForceLoadClass(className);
            final List<ReferenceType> classes = eagerType != null ? List.of(eagerType) : List.of();

            final String chainInfo = triggerBreakpointId != null
                ? String.format(", chain: trigger=#%d%s",
                    triggerBreakpointId, effectiveOneShot ? " (one-shot)" : " (sticky)") : "";

            if (classes.isEmpty()) {
                final int pendingId = breakpointTracker.registerPendingBreakpoint(className, lineNumber, jdiPolicy, policyLabel);
                pendingIdForCleanup = pendingId;
                if (condition != null && !condition.isBlank()) {
                    breakpointTracker.setCondition(pendingId, condition);
                }
                if (triggerBreakpointId != null) {
                    breakpointTracker.registerDependency(pendingId, triggerBreakpointId, effectiveOneShot);
                }

                if (!breakpointTracker.hasClassPrepareRequest(className)) {
                    final ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                    cpr.addClassFilter(className);
                    cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    cpr.enable();
                    breakpointTracker.registerClassPrepareRequest(className, cpr);
                }

                // Race guard: class may have loaded between initial classesByName and ClassPrepareRequest registration
                final List<ReferenceType> recheck = vm.classesByName(className);
                if (!recheck.isEmpty()) {
                    final ReferenceType refType = recheck.get(0);
                    final List<Location> locations = refType.locationsOfLine(lineNumber);
                    if (!locations.isEmpty()) {
                        // Same disable-then-publish ordering as the eager path — do not enable the
                        // request while it is still being wired up. JDI delivers events the instant
                        // a request is enabled, so a chained BP enabled before its chain edge is
                        // registered could fire once unchained on a hot code path.
                        final BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
                        bpRequest.setSuspendPolicy(jdiPolicy);
                        breakpointTracker.promotePendingToActive(pendingId, bpRequest);
                        if (triggerBreakpointId == null) {
                            bpRequest.setEnabled(true);
                        }
                        return String.format("Breakpoint set at %s:%d (ID: %d, suspend: %s%s%s)",
                            className, lineNumber, pendingId, policyLabel, conditionInfo, chainInfo);
                    }
                }

                return String.format("Breakpoint deferred for %s:%d (ID: %d, suspend: %s%s%s). " +
                        "Class not yet loaded — will activate automatically when the JVM loads it.",
                    className, lineNumber, pendingId, policyLabel, conditionInfo, chainInfo);
            }

            final ReferenceType refType = classes.get(0);
            final List<Location> locations = refType.locationsOfLine(lineNumber);
            if (locations.isEmpty()) {
                return String.format("Error: No executable code found at line %d in class %s", lineNumber, className);
            }

            // Leave the request disabled while we register and wire up the chain. JDI delivers
            // events the instant a request is enabled — enabling first then disabling for a chained
            // BP opens a window where the BP can fire once before its trigger ever does. JDI
            // request creation already returns a disabled request; postpone the enable to the very
            // last step (and only when no chain edge applies).
            final BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
            bpRequest.setSuspendPolicy(jdiPolicy);

            final int breakpointId = breakpointTracker.registerBreakpoint(bpRequest);
            if (condition != null && !condition.isBlank()) {
                breakpointTracker.setCondition(breakpointId, condition);
            }
            if (triggerBreakpointId != null) {
                breakpointTracker.registerDependency(breakpointId, triggerBreakpointId, effectiveOneShot);
                // Stays disabled — the chain will arm it when the trigger fires.
            } else {
                // No chain: now safe to publish the request to the target VM.
                bpRequest.setEnabled(true);
            }

            return String.format("Breakpoint set at %s:%d (ID: %d, suspend: %s%s%s)",
                className, lineNumber, breakpointId, policyLabel, conditionInfo, chainInfo);
        } catch (AbsentInformationException e) {
            cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
            return "Error: No line number information available for this class. Compile with debug info (-g).";
        } catch (Exception e) {
            cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Set a logpoint (non-stopping breakpoint) that evaluates an expression and logs the result without pausing execution. Supports an optional condition — the expression is only logged when the condition evaluates to true.")
    public String jdwp_set_logpoint(
        @McpToolParam(description = "Fully qualified class name") String className,
        @McpToolParam(description = "Line number") int lineNumber,
        @McpToolParam(description = "Java expression to evaluate and log (e.g., '\"x=\" + x', 'order.getTotal()')") String expression,
        @McpToolParam(required = false, description = "Optional condition — only log when this evaluates to true (e.g., 'i > 100')") @Nullable String condition) {
        // Track the pending ID outside the try so the catch can clean it up if locationsOfLine
        // (or any later step) throws after the pending entry has already been registered.
        Integer pendingIdForCleanup = null;
        try {
            final VirtualMachine vm = jdiService.getVM();
            final EventRequestManager erm = vm.eventRequestManager();

            final int jdiPolicy = EventRequest.SUSPEND_EVENT_THREAD;

            final String conditionInfo = condition != null && !condition.isBlank()
                ? String.format(", condition: %s", condition) : "";

            final ReferenceType eagerType = jdiService.findOrForceLoadClass(className);
            final List<ReferenceType> classes = eagerType != null ? List.of(eagerType) : List.of();

            if (classes.isEmpty()) {
                final int pendingId = breakpointTracker.registerPendingBreakpoint(className, lineNumber, jdiPolicy, "thread");
                pendingIdForCleanup = pendingId;
                breakpointTracker.setLogpointExpression(pendingId, expression);
                if (condition != null && !condition.isBlank()) {
                    breakpointTracker.setCondition(pendingId, condition);
                }

                if (!breakpointTracker.hasClassPrepareRequest(className)) {
                    final ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                    cpr.addClassFilter(className);
                    cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    cpr.enable();
                    breakpointTracker.registerClassPrepareRequest(className, cpr);
                }

                // Race guard: class may have loaded between initial classesByName and ClassPrepareRequest registration
                final List<ReferenceType> recheck = vm.classesByName(className);
                if (!recheck.isEmpty()) {
                    final ReferenceType refType = recheck.get(0);
                    final List<Location> locations = refType.locationsOfLine(lineNumber);
                    if (!locations.isEmpty()) {
                        final BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
                        bpRequest.setSuspendPolicy(jdiPolicy);
                        bpRequest.enable();
                        breakpointTracker.promotePendingToActive(pendingId, bpRequest);
                        return String.format("Logpoint set at %s:%d (ID: %d, expression: %s%s)",
                            className, lineNumber, pendingId, expression, conditionInfo);
                    }
                }

                return String.format("Logpoint deferred for %s:%d (ID: %d, expression: %s%s). " +
                        "Class not yet loaded — will activate when the JVM loads it.",
                    className, lineNumber, pendingId, expression, conditionInfo);
            }

            final ReferenceType refType = classes.get(0);
            final List<Location> locations = refType.locationsOfLine(lineNumber);
            if (locations.isEmpty()) {
                return String.format("Error: No executable code at line %d in class %s", lineNumber, className);
            }

            final BreakpointRequest bpRequest = erm.createBreakpointRequest(locations.get(0));
            bpRequest.setSuspendPolicy(jdiPolicy);
            bpRequest.enable();

            final int breakpointId = breakpointTracker.registerBreakpoint(bpRequest);
            breakpointTracker.setLogpointExpression(breakpointId, expression);
            if (condition != null && !condition.isBlank()) {
                breakpointTracker.setCondition(breakpointId, condition);
            }

            return String.format("Logpoint set at %s:%d (ID: %d, expression: %s%s)",
                className, lineNumber, breakpointId, expression, conditionInfo);
        } catch (AbsentInformationException e) {
            cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
            return "Error: No line number information available. Compile with debug info (-g).";
        } catch (Exception e) {
            cleanupOrphanPendingBreakpoint(pendingIdForCleanup);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Removes a pending breakpoint that was registered before a downstream JDI call threw.
     * No-op when {@code pendingId} is null (no pending entry to clean up). Used by
     * {@link #jdwp_set_breakpoint} and {@link #jdwp_set_logpoint} to avoid orphaning
     * pending entries on {@code AbsentInformationException} from {@code locationsOfLine}.
     */
    private void cleanupOrphanPendingBreakpoint(@Nullable Integer pendingId) {
        if (pendingId != null) {
            breakpointTracker.removePendingBreakpoint(pendingId);
        }
    }

    @McpTool(description = "Clear a breakpoint by its synthetic ID (from jdwp_overview). Routes by kind: line, exception, and field breakpoints share one ID space.")
    public String jdwp_clear_breakpoint(@McpToolParam(description = "Breakpoint ID to clear") int breakpointId) {
        try {
            // Distinguish "unknown" from "wrong kind" up-front. Cascade BEFORE removal so
            // CHAIN_BROKEN events land in front of the removal confirmation.
            final boolean isLineBp = breakpointTracker.getBreakpoint(breakpointId) != null
                || breakpointTracker.getPendingBreakpoint(breakpointId) != null;
            final boolean isExceptionBp = !isLineBp && (
                breakpointTracker.getAllExceptionBreakpoints().containsKey(breakpointId)
                || breakpointTracker.getAllPendingExceptionBreakpoints().containsKey(breakpointId));
            final boolean isFieldBp = !isLineBp && !isExceptionBp && (
                breakpointTracker.getAllFieldBreakpoints().containsKey(breakpointId)
                || breakpointTracker.getAllPendingFieldBreakpoints().containsKey(breakpointId));

            if (!isLineBp && !isExceptionBp && !isFieldBp) {
                return String.format("Breakpoint %d not found", breakpointId);
            }

            final int detached = cascadeChainBreak(breakpointId);
            final boolean removed;
            if (isLineBp) {
                removed = breakpointTracker.removeBreakpoint(breakpointId);
            } else if (isExceptionBp) {
                removed = breakpointTracker.removeExceptionBreakpoint(breakpointId);
            } else {
                removed = breakpointTracker.removeFieldBreakpoint(breakpointId);
            }
            if (!removed) {
                // Defensive: handle the race where another thread removed the BP between the
                // kind probe above and this call.
                return String.format("Breakpoint %d not found", breakpointId);
            }

            // Watchers may be attached to any BP kind via jdwp_attach_watcher — clean them on every
            // remove so a recycled or stale ID can never resurface as an orphan in jdwp_list_watchers.
            final int watchersRemoved = watcherManager.deleteWatchersForBreakpoint(breakpointId);

            final String kindLabel;
            if (isLineBp) {
                kindLabel = "Breakpoint";
            } else if (isExceptionBp) {
                kindLabel = "Exception breakpoint";
            } else {
                kindLabel = "Field breakpoint";
            }
            String msg = String.format("%s %d cleared", kindLabel, breakpointId);
            if (watchersRemoved > 0) {
                msg += String.format(" (%d associated watcher(s) also removed)", watchersRemoved);
            }
            if (detached > 0) {
                msg += String.format(" — %d dependent BP(s) armed (chain broken)", detached);
            }
            return msg;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Detaches every dependent that was waiting on {@code removedTriggerId}, arms them when
     * possible, and emits one {@code CHAIN_BROKEN} event per detached edge. Called from the
     * BP-removal code paths so the dependent BPs collapse back to plain (unconditional) BPs when
     * their trigger goes away — the BP itself is the user's primary intent, and the chain was a
     * refinement on top.
     * <p>
     * The {@code CHAIN_BROKEN} summary distinguishes the active and pending cases: an active
     * dependent is armed immediately; a pending dependent has no JDI request yet, so the arming
     * applies when its class loads. Using the same wording for both is misleading because "armed
     * unconditionally" implies an action that did not occur for the pending entries.
     *
     * @return number of dependent BPs that were detached
     */
    private int cascadeChainBreak(int removedTriggerId) {
        final Set<Integer> dependents = breakpointTracker.clearDependentsOfTrigger(removedTriggerId);
        for (Integer depId : dependents) {
            final boolean active = breakpointTracker.getEventRequestById(depId) != null;
            if (active) {
                try {
                    // Toggle every underlying request (both halves for a BOTH-mode field BP) so the
                    // dependent comes back armed as a logical BP, not as a half-disarmed survivor.
                    breakpointTracker.setBreakpointEnabledById(depId, true);
                } catch (Exception e) {
                    // Best-effort — dependent may be in a torn state; CHAIN_BROKEN still records the detach.
                    log.debug("[JDWPTools] Failed to arm dependent BP #{}: {}", depId, e.getMessage());
                }
            }
            // Record CHAIN_BROKEN per dependent (not per trigger) so the user sees which BPs
            // lost their guard. Pending dependents get a different message because they have no
            // JDI request to "arm" — the arming applies on the eventual pending → active promotion.
            final String summary = active
                ? String.format("BP #%d trigger (#%d) was removed — dependent armed unconditionally",
                    depId, removedTriggerId)
                : String.format("BP #%d trigger (#%d) was removed — dependent still pending; "
                        + "will come up armed when its class loads",
                    depId, removedTriggerId);
            eventHistory.record(new EventHistory.DebugEvent("CHAIN_BROKEN",
                summary,
                Map.of("breakpointId", String.valueOf(depId),
                    "triggerId", String.valueOf(removedTriggerId))));
        }
        return dependents.size();
    }

    @McpTool(description = "Make breakpoint <dependentId> only fire after breakpoint <triggerId> has fired. The dependent is disabled immediately. Sticky by default: it stays armed forever after the first trigger fire. With oneShot=true the dependent re-disarms after each hit so the next trigger fire re-arms it.")
    public String jdwp_set_breakpoint_dependency(
        @McpToolParam(description = "ID of the BP that will be controlled by the trigger") int dependentId,
        @McpToolParam(description = "ID of the BP whose hit arms the dependent") int triggerId,
        @McpToolParam(required = false, description = "If true, re-disarm after each hit (default false = sticky).") @Nullable Boolean oneShot) {
        try {
            if (dependentId == triggerId) {
                return "Error: A breakpoint cannot be its own trigger";
            }
            if (!breakpointTracker.isKnownBreakpointId(dependentId)) {
                return String.format("Error: Dependent breakpoint #%d does not exist", dependentId);
            }
            if (!breakpointTracker.isKnownBreakpointId(triggerId)) {
                return String.format("Error: Trigger breakpoint #%d does not exist", triggerId);
            }
            // Capture active-vs-pending state up front: the disarm path below must skip pending
            // dependents (they have no JDI request yet) and so must the summary message.
            final boolean active = breakpointTracker.getEventRequestById(dependentId) != null;

            final boolean effectiveOneShot = oneShot != null && oneShot;
            try {
                breakpointTracker.registerDependency(dependentId, triggerId, effectiveOneShot);
            } catch (BreakpointTracker.ChainRegistrationException cre) {
                // Atomic re-validation inside registerDependency caught either a concurrent trigger
                // removal or a would-be cycle. Surface a focused error message instead of the
                // generic catch-all branch below.
                return "Error: " + cre.getMessage();
            }
            if (active) {
                // Disarms every underlying request — both halves for a BOTH-mode field BP — so the
                // dependent really is waiting on the trigger across all event kinds.
                breakpointTracker.setBreakpointEnabledById(dependentId, false);
            }
            return String.format("BP #%d is now chained to trigger BP #%d (%s). %s",
                dependentId, triggerId,
                effectiveOneShot ? "one-shot" : "sticky",
                active
                    ? "Disarmed — will arm when trigger fires."
                    : "Dependent is still pending; will come up disarmed when its class loads.");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Clear a chain dependency — the breakpoint becomes always-active again. Does NOT change its enabled state; if the BP was disarmed waiting on the trigger you may also want to call jdwp_resume_until_event after this clears.")
    public String jdwp_clear_breakpoint_dependency(@McpToolParam(description = "Dependent breakpoint ID") int dependentId) {
        try {
            final BreakpointTracker.TriggerLink previous = breakpointTracker.clearDependency(dependentId);
            if (previous == null) {
                return String.format("BP #%d has no chain dependency", dependentId);
            }
            if (breakpointTracker.getEventRequestById(dependentId) != null) {
                try {
                    // Auto-re-arm on detach — once the chain is cleared, the BP collapses back to
                    // a plain BP, which is always-armed. For a BOTH-mode field BP both underlying
                    // requests must come back on, otherwise the "armed independently" claim is a
                    // half-truth for one event kind.
                    breakpointTracker.setBreakpointEnabledById(dependentId, true);
                } catch (Exception e) {
                    // Best-effort — caller can re-set enabled later if needed.
                }
            }
            return String.format("BP #%d chain to trigger #%d cleared; BP is now armed independently.",
                dependentId, previous.triggerId());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Re-engage the chain after a sticky dependent has fired: disable the dependent BP again so its trigger must fire before it becomes active. Use this when you want to catch the flow fresh again without rebuilding the chain. No-op if the BP has no chain.")
    public String jdwp_disarm_until_trigger(@McpToolParam(description = "Dependent breakpoint ID to re-disarm") int dependentId) {
        try {
            final BreakpointTracker.TriggerLink link = breakpointTracker.getDependencyOfDependent(dependentId);
            if (link == null) {
                return String.format("BP #%d has no chain — there is no trigger to wait on. Use jdwp_set_breakpoint_dependency first.", dependentId);
            }
            if (breakpointTracker.getEventRequestById(dependentId) == null) {
                return String.format("BP #%d is still pending (class not loaded); it will come up disarmed when promoted.", dependentId);
            }
            // Disarms both halves of a BOTH-mode field BP so the next event of either kind waits
            // on the trigger; for line and exception BPs it collapses to a single setEnabled call.
            breakpointTracker.setBreakpointEnabledById(dependentId, false);
            return String.format("BP #%d re-disarmed; waiting on trigger BP #%d to fire again.",
                dependentId, link.triggerId());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get recent JDWP events (breakpoints, steps, exceptions, logpoints, etc.)")
    public String jdwp_get_events(@McpToolParam(required = false, description = "Number of recent events to retrieve (default: 20, max: 100)") @Nullable Integer count) {
        try {
            final int resolvedCount;
            if (count == null || count <= 0) {
                resolvedCount = 20;
            } else if (count > 100) {
                resolvedCount = 100;
            } else {
                resolvedCount = count;
            }

            final List<EventHistory.DebugEvent> events = eventHistory.getRecent(resolvedCount);

            if (events.isEmpty()) {
                return """
                    No events recorded yet.
                    
                    Events are captured automatically when connected:
                      - Breakpoint hits
                      - Step completions
                      - Exception throws
                      - Logpoint evaluations
                      - VM lifecycle events""";
            }

            final StringBuilder result = new StringBuilder();
            result.append(String.format("Recent events (%d of %d total):\n\n", events.size(), eventHistory.size()));

            for (int i = 0; i < events.size(); i++) {
                final EventHistory.DebugEvent event = events.get(i);
                result.append(String.format("%d. [%s] %s (%s)\n",
                    i + 1, event.type(), event.summary(),
                    event.timestamp().toString().substring(11, 23)));
            }

            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Clear the JDWP event history")
    public String jdwp_clear_events() {
        eventHistory.clear();
        return "Event history cleared";
    }

    @McpTool(description = "Set a breakpoint that suspends the throwing thread when a specific exception " +
        "is thrown — caught at the throw site, before the stack unwinds. For non-stopping tracing, use " +
        "jdwp_set_exception_logpoint instead. If the exception class is not yet loaded, the breakpoint is " +
        "deferred and will activate automatically when the JVM loads it.")
    public String jdwp_set_exception_breakpoint(
        @McpToolParam(description = "Exception class name (e.g., 'java.lang.NullPointerException', 'java.lang.Exception' for all)") String exceptionClass,
        @McpToolParam(required = false, description = "Break on caught exceptions (default: true)") @Nullable Boolean caught,
        @McpToolParam(required = false, description = "Break on uncaught exceptions (default: true)") @Nullable Boolean uncaught,
        @McpToolParam(required = false, description = "Optional ID of a trigger breakpoint — this exception BP stays disarmed until the trigger fires. Sticky by default.") @Nullable Integer triggerBreakpointId,
        @McpToolParam(required = false, description = "If true, re-disarm this BP after each hit so the next trigger fire re-arms it. Default: false (sticky).") @Nullable Boolean oneShot) {
        return registerExceptionBreakpointInternal(
            exceptionClass, caught, uncaught,
            /* expression */ null, /* condition */ null,
            triggerBreakpointId, oneShot);
    }

    @McpTool(description = "Set a non-stopping breakpoint that records an EXCEPTION_LOG event for each " +
        "throw of the given exception type. The expression is evaluated against the throwing frame with " +
        "$exception bound to the thrown object (e.g., \"$exception.getMessage()\"); its result is attached " +
        "to the event. The optional condition is evaluated with the same $exception binding — the log is " +
        "recorded only when the condition is true. Use this for tracing exception flows in long-running " +
        "services without halting traffic. If the exception class is not yet loaded, the logpoint is " +
        "deferred and will activate automatically when the JVM loads it.")
    public String jdwp_set_exception_logpoint(
        @McpToolParam(description = "Exception class name (e.g., 'java.sql.SQLException')") String exceptionClass,
        @McpToolParam(description = "Java expression evaluated on each hit; $exception is bound to the thrown object") String expression,
        @McpToolParam(required = false, description = "Optional condition with $exception bound — only log when this evaluates to true") @Nullable String condition,
        @McpToolParam(required = false, description = "Log caught exceptions (default: true)") @Nullable Boolean caught,
        @McpToolParam(required = false, description = "Log uncaught exceptions (default: true)") @Nullable Boolean uncaught,
        @McpToolParam(required = false, description = "Optional ID of a trigger breakpoint — this logpoint stays disarmed until the trigger fires. Sticky by default.") @Nullable Integer triggerBreakpointId,
        @McpToolParam(required = false, description = "If true, re-disarm after each hit so the next trigger fire re-arms it. Default: false (sticky).") @Nullable Boolean oneShot) {
        if (expression == null || expression.isBlank()) {
            return "Error: expression is required for jdwp_set_exception_logpoint. "
                + "Use jdwp_set_exception_breakpoint for a suspending exception BP without expression evaluation.";
        }
        return registerExceptionBreakpointInternal(
            exceptionClass, caught, uncaught,
            expression, condition,
            triggerBreakpointId, oneShot);
    }

    /**
     * Shared registration path for suspending exception breakpoints and exception logpoints. The two
     * public tools differ only in their parameter validation and the rendered summary; everything
     * inside (trigger validation, eager class load, deferred path with {@code ClassPrepareRequest},
     * active path with the chain-disable-then-enable ordering) is identical and lives here.
     *
     * @param expression when non-null, switches the helper into log-only mode and is rendered on a dedicated line
     * @param condition  non-null persists a metadata condition under the synthetic ID; evaluated by
     *                   {@link JdiEventListener} on each hit with {@code $exception} bound
     */
    private String registerExceptionBreakpointInternal(
        String exceptionClass,
        @Nullable Boolean caught, @Nullable Boolean uncaught,
        @Nullable String expression, @Nullable String condition,
        @Nullable Integer triggerBreakpointId, @Nullable Boolean oneShot) {
        try {
            final boolean effectiveCaught = caught == null || caught;
            final boolean effectiveUncaught = uncaught == null || uncaught;
            if (triggerBreakpointId != null && !breakpointTracker.isKnownBreakpointId(triggerBreakpointId)) {
                return String.format("Error: Trigger breakpoint #%d does not exist", triggerBreakpointId);
            }
            final boolean effectiveOneShot = oneShot != null && oneShot;
            final String chainInfo = triggerBreakpointId != null
                ? String.format("\n  Chain: trigger=#%d%s",
                    triggerBreakpointId, effectiveOneShot ? " (one-shot)" : " (sticky)") : "";
            final boolean isLogpoint = expression != null;
            final String normalisedCondition = (condition == null || condition.isBlank()) ? null : condition;
            final String expressionLine = isLogpoint ? "\n  Expression: " + expression : "";
            final String conditionLine = normalisedCondition != null ? "\n  Condition: " + normalisedCondition : "";
            final BreakpointTracker.ExceptionBreakpointSpec spec = isLogpoint
                ? BreakpointTracker.ExceptionBreakpointSpec.logOnly(exceptionClass, effectiveCaught, effectiveUncaught, expression)
                : BreakpointTracker.ExceptionBreakpointSpec.suspending(exceptionClass, effectiveCaught, effectiveUncaught);

            final VirtualMachine vm = jdiService.getVM();
            final EventRequestManager erm = vm.eventRequestManager();

            // Try eager: check classesByName, and if empty, force-load via Class.forName
            final ReferenceType eagerType = jdiService.findOrForceLoadClass(exceptionClass);

            if (eagerType == null) {
                // Class not loadable yet — defer
                final int pendingId = breakpointTracker.registerPendingExceptionBreakpoint(spec);
                if (normalisedCondition != null) {
                    breakpointTracker.setCondition(pendingId, normalisedCondition);
                }
                if (triggerBreakpointId != null) {
                    breakpointTracker.registerDependency(pendingId, triggerBreakpointId, effectiveOneShot);
                }

                if (!breakpointTracker.hasClassPrepareRequest(exceptionClass)) {
                    final ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                    cpr.addClassFilter(exceptionClass);
                    cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    cpr.enable();
                    breakpointTracker.registerClassPrepareRequest(exceptionClass, cpr);
                }

                return String.format("""
                        Exception breakpoint deferred (ID: %d)
                          Exception: %s
                          Caught: %s
                          Uncaught: %s
                          Mode: %s%s%s%s
                        Class not yet loaded — will activate automatically when the JVM loads it.""",
                    pendingId, exceptionClass, effectiveCaught, effectiveUncaught,
                    isLogpoint ? "log-only" : "suspend",
                    expressionLine,
                    conditionLine,
                    chainInfo);
            }

            // Create the exception request DISABLED, finish wiring it up (including the chain
            // edge), and only enable as the very last step. JDI delivers events the instant a
            // request is enabled; enabling-then-disabling a chained BP opens a window where an
            // exception could fire and bypass the chain.
            final ExceptionRequest exReq = erm.createExceptionRequest(eagerType, effectiveCaught, effectiveUncaught);
            // Always SUSPEND_EVENT_THREAD; logOnly BPs are auto-resumed by JdiEventListener after
            // recording / expression evaluation (invokeMethod requires the firing thread suspended).
            exReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);

            final int id = breakpointTracker.registerExceptionBreakpoint(exReq, spec);
            if (normalisedCondition != null) {
                breakpointTracker.setCondition(id, normalisedCondition);
            }
            if (triggerBreakpointId != null) {
                breakpointTracker.registerDependency(id, triggerBreakpointId, effectiveOneShot);
                // Stays disabled — the chain will arm it when the trigger fires.
            } else {
                exReq.setEnabled(true);
            }

            return String.format("Exception breakpoint set (ID: %d)\n  Exception: %s\n  Caught: %s\n  Uncaught: %s\n  Mode: %s%s%s%s",
                id, exceptionClass, effectiveCaught, effectiveUncaught,
                isLogpoint ? "log-only" : "suspend",
                expressionLine,
                conditionLine,
                chainInfo);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Set a field watchpoint that suspends the firing thread when the field is read " +
        "(mode='access'), written (mode='modification'), or both. Conditions are evaluated against the firing " +
        "frame with $oldValue, $newValue (modification only), $object (null for static), $fieldName, and $mode " +
        "bound. If the class is not yet loaded, the watchpoint is deferred and activates automatically. " +
        "ERROR if the field is ambiguous on the class, missing, or static when objectFilterId is supplied.")
    public String jdwp_set_field_breakpoint(
        @McpToolParam(description = "Fully-qualified class declaring the field (e.g., 'com.example.Order')") String className,
        @McpToolParam(description = "Field name (must be unambiguous on the class)") String fieldName,
        @McpToolParam(description = "Watch mode: 'access', 'modification', or 'both' (case-insensitive)") String mode,
        @McpToolParam(required = false, description = "Optional condition with $oldValue, $newValue, $object, $fieldName, $mode bound — fire only when this evaluates to true") @Nullable String condition,
        @McpToolParam(required = false, description = "Optional thread filter — only fire when this thread (uniqueID) hits the field") @Nullable Long threadFilterId,
        @McpToolParam(required = false, description = "Optional object filter — only fire on the given instance (object ID from jdwp_get_locals/jdwp_get_fields). Must be omitted for static fields.") @Nullable Long objectFilterId,
        @McpToolParam(required = false, description = "Optional ID of a trigger breakpoint — this field BP stays disarmed until the trigger fires. Sticky by default.") @Nullable Integer triggerBreakpointId,
        @McpToolParam(required = false, description = "If true, re-disarm this BP after each hit so the next trigger fire re-arms it. Default: false (sticky).") @Nullable Boolean oneShot) {
        return registerFieldBreakpointInternal(
            className, fieldName, mode,
            /* expression */ null, condition,
            threadFilterId, objectFilterId,
            triggerBreakpointId, oneShot);
    }

    @McpTool(description = "Set a non-stopping field watchpoint that records a FIELD_LOGPOINT event for each " +
        "access or modification of the field. The expression is evaluated against the firing frame with " +
        "$oldValue, $newValue (modification only), $object (null for static), $fieldName, and $mode bound; " +
        "its result is attached to the event. Optional condition gates whether the log is recorded. " +
        "Use this to trace state transitions in long-running services without halting traffic.")
    public String jdwp_set_field_logpoint(
        @McpToolParam(description = "Fully-qualified class declaring the field") String className,
        @McpToolParam(description = "Field name (must be unambiguous on the class)") String fieldName,
        @McpToolParam(description = "Watch mode: 'access', 'modification', or 'both' (case-insensitive)") String mode,
        @McpToolParam(description = "Java expression evaluated on each hit (e.g., '$oldValue + \" -> \" + $newValue')") String expression,
        @McpToolParam(required = false, description = "Optional condition with the same synthetic bindings — log only when true") @Nullable String condition,
        @McpToolParam(required = false, description = "Optional thread filter — only fire when this thread (uniqueID) hits the field") @Nullable Long threadFilterId,
        @McpToolParam(required = false, description = "Optional object filter — only fire on the given instance. Must be omitted for static fields.") @Nullable Long objectFilterId,
        @McpToolParam(required = false, description = "Optional ID of a trigger breakpoint — this logpoint stays disarmed until the trigger fires. Sticky by default.") @Nullable Integer triggerBreakpointId,
        @McpToolParam(required = false, description = "If true, re-disarm after each hit so the next trigger fire re-arms it. Default: false (sticky).") @Nullable Boolean oneShot) {
        if (expression == null || expression.isBlank()) {
            return "Error: expression is required for jdwp_set_field_logpoint. "
                + "Use jdwp_set_field_breakpoint for a suspending field BP without expression evaluation.";
        }
        return registerFieldBreakpointInternal(
            className, fieldName, mode,
            expression, condition,
            threadFilterId, objectFilterId,
            triggerBreakpointId, oneShot);
    }

    /**
     * Shared registration path for suspending field breakpoints and field logpoints. Returns an
     * {@code Error: ...} response (does not throw, does not silently fall back) when the user
     * supplies an invalid mode, an objectFilterId for a static field, or an ambiguous / missing
     * field — silent fallback would let the caller believe the BP is set when it isn't, and field
     * BPs only re-surface on event delivery, so a half-installed one can hide for the remainder of
     * the session. Falls back to the pending state when the declaring class is not loaded,
     * identical to the exception-BP path; the static-vs-objectFilter validation defers to class
     * load in that case and a {@code Note:} line in the response warns the caller.
     */
    private String registerFieldBreakpointInternal(
        String className, String fieldName, String mode,
        @Nullable String expression, @Nullable String condition,
        @Nullable Long threadFilterId, @Nullable Long objectFilterId,
        @Nullable Integer triggerBreakpointId, @Nullable Boolean oneShot) {
        try {
            if (className == null || className.isBlank()) {
                return "Error: className is required";
            }
            if (fieldName == null || fieldName.isBlank()) {
                return "Error: fieldName is required";
            }
            if (mode == null || mode.isBlank()) {
                return "Error: mode is required (one of: access, modification, both)";
            }
            final BreakpointTracker.FieldWatchMode watchMode;
            switch (mode.toLowerCase(Locale.ROOT)) {
                case "access" -> watchMode = BreakpointTracker.FieldWatchMode.ACCESS;
                case "modification", "modify", "write" -> watchMode = BreakpointTracker.FieldWatchMode.MODIFICATION;
                case "both" -> watchMode = BreakpointTracker.FieldWatchMode.BOTH;
                default -> {
                    return String.format(
                        "Error: invalid mode '%s' — expected one of: access, modification, both", mode);
                }
            }
            if (triggerBreakpointId != null && !breakpointTracker.isKnownBreakpointId(triggerBreakpointId)) {
                return String.format("Error: Trigger breakpoint #%d does not exist", triggerBreakpointId);
            }
            final boolean effectiveOneShot = oneShot != null && oneShot;
            final String normalisedCondition = (condition == null || condition.isBlank()) ? null : condition;
            // Capture the non-null expression in a local so NullAway can track the narrowing
            // across the ternary that picks suspending vs log-only.
            final String logpointExpression = expression;
            final boolean isLogpoint = logpointExpression != null;

            final BreakpointTracker.FieldBreakpointSpec spec = logpointExpression != null
                ? BreakpointTracker.FieldBreakpointSpec.logOnly(className, fieldName, watchMode,
                    logpointExpression, threadFilterId, objectFilterId, normalisedCondition)
                : BreakpointTracker.FieldBreakpointSpec.suspending(className, fieldName, watchMode,
                    threadFilterId, objectFilterId, normalisedCondition);

            final VirtualMachine vm = jdiService.getVM();
            final EventRequestManager erm = vm.eventRequestManager();
            final ReferenceType eagerType = jdiService.findOrForceLoadClass(className);

            final String chainInfo = triggerBreakpointId != null
                ? String.format("\n  Chain: trigger=#%d%s",
                    triggerBreakpointId, effectiveOneShot ? " (one-shot)" : " (sticky)") : "";
            final String expressionLine = isLogpoint ? "\n  Expression: " + expression : "";
            final String conditionLine = normalisedCondition != null ? "\n  Condition: " + normalisedCondition : "";
            final String filterLine =
                (threadFilterId != null || objectFilterId != null)
                    ? "\n  Filters:"
                        + (threadFilterId != null ? " thread=" + threadFilterId : "")
                        + (objectFilterId != null ? " object=" + objectFilterId : "")
                    : "";

            if (eagerType == null) {
                final int pendingId = breakpointTracker.registerPendingFieldBreakpoint(spec);
                if (normalisedCondition != null) {
                    breakpointTracker.setCondition(pendingId, normalisedCondition);
                }
                if (triggerBreakpointId != null) {
                    breakpointTracker.registerDependency(pendingId, triggerBreakpointId, effectiveOneShot);
                }
                if (!breakpointTracker.hasClassPrepareRequest(className)) {
                    final ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                    cpr.addClassFilter(className);
                    cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    cpr.enable();
                    breakpointTracker.registerClassPrepareRequest(className, cpr);
                }
                // Static-ness cannot be checked until the class loads — surface a warning so users
                // who supplied an objectFilterId know the validation is deferred. If the field turns
                // out to be static on load, the pending entry is marked FAILED and visible via
                // jdwp_overview(types="field_breakpoint").
                final String objectFilterWarning = objectFilterId != null
                    ? "\n  Note: objectFilterId is set — if '" + fieldName
                        + "' turns out to be static on load, the breakpoint will fail; "
                        + "check jdwp_overview(types=\"field_breakpoint\") after the class loads."
                    : "";
                return String.format("""
                        Field breakpoint deferred (ID: %d)
                          Class: %s
                          Field: %s
                          Mode: %s (%s)%s%s%s%s%s
                        Class not yet loaded — will activate automatically when the JVM loads it.""",
                    pendingId, className, fieldName, watchMode.name().toLowerCase(Locale.ROOT),
                    isLogpoint ? "log-only" : "suspend",
                    expressionLine, conditionLine, filterLine, chainInfo, objectFilterWarning);
            }

            // Resolve the field on the eagerly-loaded class. Ambiguous (declared on multiple types
            // via shadowing or interface inheritance) and missing fields are hard errors — silent
            // fallback would let the user think the BP is set when it isn't.
            final List<Field> candidates = eagerType.allFields().stream()
                .filter(f -> f.name().equals(fieldName))
                .toList();
            if (candidates.isEmpty()) {
                return String.format("Error: Field '%s' not found on %s or its supertypes", fieldName, className);
            }
            if (candidates.size() > 1) {
                return String.format(
                    "Error: Field '%s' is ambiguous on %s (declared on %d types) — use a more specific className",
                    fieldName, className, candidates.size());
            }
            final Field field = candidates.get(0);
            if (objectFilterId != null && field.isStatic()) {
                return String.format(
                    "Error: Field '%s' on %s is static; objectFilterId does not apply", fieldName, className);
            }

            // Two-step creation for BOTH-mode mirrors BreakpointTracker.promoteSinglePendingField:
            // accumulate created requests and roll back on any failure so a half-armed pair never
            // leaks onto the target VM.
            final List<EventRequest> createdRequests = new ArrayList<>(2);
            AccessWatchpointRequest accessReq = null;
            ModificationWatchpointRequest modReq = null;
            try {
                if (watchMode == BreakpointTracker.FieldWatchMode.ACCESS
                    || watchMode == BreakpointTracker.FieldWatchMode.BOTH) {
                    accessReq = erm.createAccessWatchpointRequest(field);
                    createdRequests.add(accessReq);
                    configureWatchpoint(accessReq, threadFilterId, objectFilterId, vm);
                }
                if (watchMode == BreakpointTracker.FieldWatchMode.MODIFICATION
                    || watchMode == BreakpointTracker.FieldWatchMode.BOTH) {
                    modReq = erm.createModificationWatchpointRequest(field);
                    createdRequests.add(modReq);
                    configureWatchpoint(modReq, threadFilterId, objectFilterId, vm);
                }
            } catch (Exception inner) {
                for (EventRequest leaked : createdRequests) {
                    try {
                        erm.deleteEventRequest(leaked);
                    } catch (Exception ignore) {
                        // No-op: best-effort cleanup, do not mask the original failure.
                    }
                }
                return "Error: failed to create field watchpoint — " + inner.getMessage();
            }

            final int id = breakpointTracker.registerFieldBreakpoint(spec, accessReq, modReq);
            if (normalisedCondition != null) {
                breakpointTracker.setCondition(id, normalisedCondition);
            }
            if (triggerBreakpointId != null) {
                breakpointTracker.registerDependency(id, triggerBreakpointId, effectiveOneShot);
                // Stays disabled — the chain will arm it when the trigger fires.
            } else {
                if (accessReq != null) {
                    accessReq.setEnabled(true);
                }
                if (modReq != null) {
                    modReq.setEnabled(true);
                }
            }

            return String.format("""
                    Field breakpoint set (ID: %d)
                      Class: %s
                      Field: %s
                      Mode: %s (%s)%s%s%s%s""",
                id, className, fieldName, watchMode.name().toLowerCase(Locale.ROOT),
                isLogpoint ? "log-only" : "suspend",
                expressionLine, conditionLine, filterLine, chainInfo);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Applies suspend policy, thread filter, and instance filter to a freshly-created watchpoint
     * request. The MCP tool path here mirrors {@code BreakpointTracker.configureFieldRequest} but
     * stays in the tool layer because the deferred / promotion path lives there; keeping the two
     * configurators identical means a refactor in either place is one-line obvious. Always
     * SUSPEND_EVENT_THREAD: logOnly BPs are auto-resumed by the listener after recording.
     */
    private void configureWatchpoint(WatchpointRequest req,
                                     @Nullable Long threadFilterId, @Nullable Long objectFilterId,
                                     VirtualMachine vm) {
        req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        if (threadFilterId != null) {
            final ThreadReference thread = vm.allThreads().stream()
                .filter(t -> t.uniqueID() == threadFilterId)
                .findFirst().orElse(null);
            if (thread == null) {
                throw new IllegalStateException(
                    "Thread #" + threadFilterId + " no longer alive — cannot apply thread filter");
            }
            req.addThreadFilter(thread);
        }
        if (objectFilterId != null) {
            final ObjectReference instance = jdiService.getCachedObject(objectFilterId);
            if (instance == null) {
                throw new IllegalStateException(
                    "Object #" + objectFilterId + " no longer in cache — re-fetch via jdwp_get_locals or jdwp_get_fields");
            }
            req.addInstanceFilter(instance);
        }
    }

    @McpTool(description = "Clear ALL session state (breakpoints, exception breakpoints, field breakpoints, watchers, object cache, event history) WITHOUT disconnecting from the target VM. Use between sequential debugging scenarios against the same long-running target.")
    public String jdwp_reset() {
        final int activeBp = breakpointTracker.getAllBreakpoints().size();
        final int pendingBp = breakpointTracker.getAllPendingBreakpoints().size();
        final int activeExBp = breakpointTracker.getAllExceptionBreakpoints().size();
        final int pendingExBp = breakpointTracker.getAllPendingExceptionBreakpoints().size();
        final int activeFieldBp = breakpointTracker.getAllFieldBreakpoints().size();
        final int pendingFieldBp = breakpointTracker.getAllPendingFieldBreakpoints().size();
        final int watchers = watcherManager.getAllWatchers().size();
        final int events = eventHistory.size();

        // VM-dependent path first: try to delete the live JDI requests via the EventRequestManager.
        // If the VM is unreachable (external disconnect, crash) we fall back to a pure in-memory
        // reset so the server-local state still gets cleared.
        boolean vmCleared = false;
        try {
            final VirtualMachine vm = jdiService.getVM();
            breakpointTracker.clearAll(vm.eventRequestManager());
            vmCleared = true;
        } catch (Exception e) {
            log.debug("VM unreachable during jdwp_reset — falling back to in-memory reset", e);
            breakpointTracker.reset();
        }

        // These clears are server-local and must happen regardless of VM liveness.
        watcherManager.clearAll();
        jdiService.clearObjectCache();
        eventHistory.clear();

        final String header = vmCleared
            ? "Reset complete (VM connection preserved)."
            : "Reset complete (VM unreachable — server-local state cleared).";
        return String.format("""
                %s
                  Breakpoints cleared:           %d active + %d pending
                  Exception breakpoints cleared: %d active + %d pending
                  Field breakpoints cleared:     %d active + %d pending
                  Watchers cleared:              %d
                  Event history cleared:         %d entries
                  Object cache cleared.""",
            header, activeBp, pendingBp, activeExBp, pendingExBp,
            activeFieldBp, pendingFieldBp, watchers, events);
    }

    // ========================================
    // Marked Instances + Unified Overview / Clear
    // ========================================

    /**
     * Type tags accepted by {@link #jdwp_overview} / {@link #jdwp_clear}. The string values are the
     * stable tokens the agent passes in via the {@code types} parameter; "all" is the wildcard.
     *
     * <p>Backed by a {@link LinkedHashSet} (not {@code Set.of}) so iteration order is deterministic
     * — the order is what surfaces in the {@code "Supported: ..."} error message when an unknown
     * token is rejected, and a stable order keeps the agent-facing diagnostics reproducible.
     */
    private static final Set<String> OVERVIEW_TYPES = Collections.unmodifiableSet(
        new LinkedHashSet<>(List.of(
            "breakpoint", "exception_breakpoint", "field_breakpoint",
            "logpoint", "watcher", "mark")));

    @McpTool(description = "Mark a cached object instance with an agent-chosen label so it can be referenced by name as $label inside conditional-breakpoint, logpoint, watcher, and exception-logpoint expressions. The object is pinned in the target heap (disableCollection) by default so the label remains usable across many events; pass pin=false to allow natural GC. Label must be a valid Java identifier and must not collide with a reserved binding ($exception, $oldValue, $newValue, $object, $fieldName, $mode, _this) or another live mark.")
    public String jdwp_mark_instance(
        @McpToolParam(description = "Agent-chosen label (Java identifier, no $ sigil). The expression-side reference is $label.") String label,
        @McpToolParam(description = "Object unique ID (from jdwp_get_locals or jdwp_get_fields)") long objectId,
        @McpToolParam(required = false, description = "Pin the object in the target heap so it cannot be GC'd while marked (default: true). Set to false to observe natural collection.") @Nullable Boolean pin) {
        try {
            final ObjectReference ref = jdiService.getCachedObject(objectId);
            if (ref == null) {
                return String.format("[ERROR] Object #%d not found in cache. "
                    + "Use jdwp_get_locals or jdwp_get_fields to discover it first.", objectId);
            }
            final String staleVmHint = staleVmHintIfMismatched(objectId, ref);
            if (staleVmHint != null) {
                return staleVmHint;
            }
            final boolean effectivePin = pin == null || pin;
            markedInstances.mark(label, ref, effectivePin);
            return String.format("✓ Marked Object#%d (%s) as $%s%s",
                objectId, ref.referenceType().name(), label,
                effectivePin ? " [pinned]" : " [unpinned — may be GC'd]");
        } catch (IllegalArgumentException badLabel) {
            return "[ERROR] " + badLabel.getMessage();
        } catch (IllegalStateException badState) {
            return "[ERROR] " + badState.getMessage();
        } catch (ObjectCollectedException dead) {
            return "[ERROR] " + dead.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Remove a marked instance by its label. Releases the target-heap pin (enableCollection) when one was set. Returns an error if no such mark exists.")
    public String jdwp_unmark_instance(
        @McpToolParam(description = "Label to remove (no $ sigil)") String label) {
        try {
            if (!markedInstances.unmark(label)) {
                return String.format("[ERROR] No mark named '%s'. "
                    + "Use jdwp_overview(types=\"mark\") to list current marks.", label);
            }
            return String.format("✓ Removed mark $%s", label);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Rename a marked instance, preserving its pin and underlying object. New label must be valid and not collide with reserved bindings or existing marks.")
    public String jdwp_rename_mark(
        @McpToolParam(description = "Existing label (no $ sigil)") String oldLabel,
        @McpToolParam(description = "New label (Java identifier, no $ sigil)") String newLabel) {
        try {
            markedInstances.rename(oldLabel, newLabel);
            return String.format("✓ Renamed $%s -> $%s", oldLabel, newLabel);
        } catch (IllegalArgumentException notFound) {
            return "[ERROR] " + notFound.getMessage();
        } catch (IllegalStateException collision) {
            return "[ERROR] " + collision.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Read-only overview of MCP-side debug state — breakpoints, exception breakpoints, field breakpoints, logpoints, watchers, and marked instances — in one call. Filter by 'types' (comma-separated subset of breakpoint, exception_breakpoint, field_breakpoint, logpoint, watcher, mark — defaults to all) and/or by 'filter' substring (matches class / label / expression / type, case-insensitive).")
    public String jdwp_overview(
        @McpToolParam(required = false, description = "Comma-separated subset of: breakpoint, exception_breakpoint, field_breakpoint, logpoint, watcher, mark. Defaults to all.") @Nullable String types,
        @McpToolParam(required = false, description = "Optional case-insensitive substring filter applied to class / label / expression / type") @Nullable String filter) {
        try {
            final Set<String> wanted = parseOverviewTypes(types);
            final String needle = (filter == null || filter.isBlank()) ? null : filter.toLowerCase(Locale.ROOT);

            final StringBuilder out = new StringBuilder();
            out.append("=== Overview ===\n");
            if (needle != null) {
                out.append("(filter: '").append(filter).append("')\n");
            }

            int totalShown = 0;
            if (wanted.contains("mark")) {
                totalShown += appendMarkOverview(out, needle);
            }
            if (wanted.contains("breakpoint")) {
                totalShown += appendBreakpointOverview(out, needle, false);
            }
            if (wanted.contains("logpoint")) {
                totalShown += appendBreakpointOverview(out, needle, true);
            }
            if (wanted.contains("exception_breakpoint")) {
                totalShown += appendExceptionBreakpointOverview(out, needle);
            }
            if (wanted.contains("field_breakpoint")) {
                totalShown += appendFieldBreakpointOverview(out, needle);
            }
            if (wanted.contains("watcher")) {
                totalShown += appendWatcherOverview(out, needle);
            }

            if (totalShown == 0) {
                out.append("\n(no entries matched)\n");
            }
            return out.toString();
        } catch (IllegalArgumentException badInput) {
            return "[ERROR] " + badInput.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Delete debug state by type and/or substring filter. The 'types' arg is REQUIRED so an empty call cannot wipe everything by accident; pass 'all' to clear every supported type. To preview what would be cleared, call jdwp_overview with the same types/filter first.")
    public String jdwp_clear(
        @McpToolParam(description = "Comma-separated subset of: breakpoint, exception_breakpoint, field_breakpoint, logpoint, watcher, mark. Use 'all' for every type. REQUIRED.") String types,
        @McpToolParam(required = false, description = "Optional case-insensitive substring filter applied to class / label / expression / type") @Nullable String filter) {
        try {
            if (types == null || types.isBlank()) {
                return "[ERROR] 'types' is required. Pass 'all' to clear every supported type, or a comma-separated subset of: "
                    + String.join(", ", OVERVIEW_TYPES);
            }
            final Set<String> wanted = parseOverviewTypes(types);
            final String needle = (filter == null || filter.isBlank()) ? null : filter.toLowerCase(Locale.ROOT);

            // VM probe drives the server-local warning when BP clears run without a live target.
            VirtualMachine vm = null;
            try {
                vm = jdiService.getVM();
            } catch (Exception ignored) {
                // Server-local clears below still work without a live VM.
            }

            final StringBuilder out = new StringBuilder();
            out.append("=== Clear ===\n");

            int totalCleared = 0;
            if (wanted.contains("mark")) {
                totalCleared += clearMarks(out, needle);
            }
            if (wanted.contains("watcher")) {
                totalCleared += clearWatchers(out, needle);
            }
            if (wanted.contains("breakpoint") || wanted.contains("logpoint")) {
                totalCleared += clearLineBreakpoints(out, needle,
                    wanted.contains("breakpoint"), wanted.contains("logpoint"));
            }
            if (wanted.contains("exception_breakpoint")) {
                totalCleared += clearExceptionBreakpoints(out, needle);
            }
            if (wanted.contains("field_breakpoint")) {
                totalCleared += clearFieldBreakpoints(out, needle);
            }

            if (totalCleared == 0) {
                out.append("\n(nothing matched)\n");
            } else {
                out.append(String.format("\nTotal cleared: %d entr%s\n",
                    totalCleared, totalCleared == 1 ? "y" : "ies"));
            }
            if (vm == null && (wanted.contains("breakpoint") || wanted.contains("exception_breakpoint")
                || wanted.contains("field_breakpoint") || wanted.contains("logpoint"))) {
                out.append("(note: VM not connected — BP removal is server-local only)\n");
            }
            return out.toString();
        } catch (IllegalArgumentException badInput) {
            return "[ERROR] " + badInput.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Parses the {@code types} argument shared by {@link #jdwp_overview} and {@link #jdwp_clear}.
     * Accepts {@code null} / blank as "every type"; accepts {@code "all"} as the explicit wildcard.
     * Rejects unknown tokens with an {@link IllegalArgumentException} carrying the supported list
     * so the agent can self-correct.
     *
     * <p>Every token is validated before the {@code "all"} wildcard is honoured, so a typo like
     * {@code "all,bogos"} or {@code "bogos,all"} is rejected symmetrically — otherwise an unknown
     * token sitting next to the wildcard would silently slip through and the agent would never
     * learn about the typo.
     */
    private Set<String> parseOverviewTypes(@Nullable String types) {
        if (types == null || types.isBlank() || "all".equalsIgnoreCase(types.trim())) {
            return OVERVIEW_TYPES;
        }
        final Set<String> out = new LinkedHashSet<>();
        boolean sawAll = false;
        for (String raw : types.split(",", -1)) {
            final String tok = raw.trim().toLowerCase(Locale.ROOT);
            if (tok.isEmpty()) {
                continue;
            }
            if ("all".equals(tok)) {
                sawAll = true;
                continue;
            }
            if (!OVERVIEW_TYPES.contains(tok)) {
                throw new IllegalArgumentException(
                    "Unknown type '" + tok + "'. Supported: " + String.join(", ", OVERVIEW_TYPES) + " (or 'all').");
            }
            out.add(tok);
        }
        if (sawAll || out.isEmpty()) {
            return OVERVIEW_TYPES;
        }
        return out;
    }

    /**
     * Renders the marked-instance section. Returns the number of rows emitted; emits the header
     * even when no rows match so the agent can confirm the type was actually inspected.
     */
    private int appendMarkOverview(StringBuilder out, @Nullable String needle) {
        final List<MarkInfo> all = markedInstances.list().stream()
            .sorted(Comparator.comparing(MarkInfo::label))
            .toList();
        final List<MarkInfo> shown = all.stream()
            .filter(m -> matchesFilter(needle, m.label(), m.typeName()))
            .toList();
        appendSectionHeader(out, "Marks", shown.size(), all.size());
        if (shown.isEmpty()) {
            return 0;
        }
        for (MarkInfo m : shown) {
            out.append(String.format("  $%s -> Object#%d (%s) [%s%s]\n",
                m.label(), m.objectId(), m.typeName(),
                m.pinned() ? "pinned" : "unpinned",
                m.collected() ? ", collected" : ""));
        }
        return shown.size();
    }

    /**
     * Renders chain dependency suffix ({@code  chain=trigger=#7 (one-shot, ARMED)}) for a BP id, or
     * an empty string when the BP is not part of a chain. Centralised so all three BP overview
     * renderers expose the same chain shape.
     *
     * @param armed whether the underlying JDI request is currently enabled — {@code null} for
     *              pending entries where there is no request yet
     */
    private String chainSuffix(int id, @Nullable Boolean armed) {
        final BreakpointTracker.TriggerLink chain = breakpointTracker.getDependencyOfDependent(id);
        if (chain == null) {
            return "";
        }
        final String mode = chain.oneShot() ? "one-shot" : "sticky";
        if (armed == null) {
            return String.format("  chain=trigger=#%d (%s)", chain.triggerId(), mode);
        }
        return String.format("  chain=trigger=#%d (%s, %s)", chain.triggerId(), mode,
            armed ? "ARMED" : "WAITING");
    }

    /**
     * Renders the line-breakpoint section. When {@code logpoints} is true, only entries with a
     * logpoint expression are included; otherwise plain breakpoints (no logpoint expression) are
     * shown. Pending entries always render with their stored class name.
     */
    private int appendBreakpointOverview(StringBuilder out, @Nullable String needle, boolean logpoints) {
        final Map<Integer, BreakpointRequest> active = breakpointTracker.getAllBreakpoints();
        final Map<Integer, BreakpointTracker.PendingBreakpoint> pending = breakpointTracker.getAllPendingBreakpoints();
        final List<String> rows = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointRequest> e : active.entrySet()) {
            final int id = e.getKey();
            final String expr = breakpointTracker.getLogpointExpression(id);
            if (logpoints == (expr != null)) {
                final BreakpointRequest bp = e.getValue();
                final Location loc = bp.location();
                final String className = loc.declaringType().name();
                final String cond = breakpointTracker.getCondition(id);
                if (matchesFilter(needle, className, expr, cond)) {
                    rows.add(String.format("  #%d  %s:%d%s%s%s",
                        id, className, loc.lineNumber(),
                        expr != null ? "  expr=\"" + expr + "\"" : "",
                        cond != null ? "  cond=\"" + cond + "\"" : "",
                        chainSuffix(id, bp.isEnabled())));
                }
            }
        }
        // Pending entries cannot have a logpoint expression bound yet — they only carry class/line.
        if (!logpoints) {
            for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> e : pending.entrySet()) {
                final int id = e.getKey();
                final BreakpointTracker.PendingBreakpoint pb = e.getValue();
                if (matchesFilter(needle, pb.getClassName(), null)) {
                    rows.add(String.format("  #%d  %s:%d  [%s]%s",
                        id, pb.getClassName(), pb.getLineNumber(),
                        pb.getFailureReason() != null ? "FAILED: " + pb.getFailureReason() : "PENDING",
                        chainSuffix(id, null)));
                }
            }
        }
        out.append(logpoints
            ? String.format("\nLogpoints (%d):\n", rows.size())
            : String.format("\nBreakpoints (%d):\n", rows.size()));
        rows.forEach(r -> out.append(r).append('\n'));
        return rows.size();
    }

    /**
     * Renders the exception-breakpoint section. Pending entries (class not yet loaded) are tagged
     * `[PENDING]` so the agent can tell them apart from active ones at a glance.
     */
    private int appendExceptionBreakpointOverview(StringBuilder out, @Nullable String needle) {
        final Map<Integer, BreakpointTracker.ExceptionBreakpointInfo> active =
            breakpointTracker.getAllExceptionBreakpoints();
        final Map<Integer, BreakpointTracker.PendingExceptionBreakpoint> pending =
            breakpointTracker.getAllPendingExceptionBreakpoints();
        final List<String> rows = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointTracker.ExceptionBreakpointInfo> e : active.entrySet()) {
            final int id = e.getKey();
            final BreakpointTracker.ExceptionBreakpointInfo info = e.getValue();
            final String name = info.getExceptionClass();
            final String expr = info.getExpression();
            final String cond = breakpointTracker.getCondition(id);
            if (matchesFilter(needle, name, expr, cond)) {
                rows.add(String.format("  #%d  %s  [%s]%s%s%s",
                    id, name,
                    info.isLogOnly() ? "log-only" : "suspend",
                    expr != null ? "  expr=\"" + expr + "\"" : "",
                    cond != null ? "  cond=\"" + cond + "\"" : "",
                    chainSuffix(id, info.getRequest().isEnabled())));
            }
        }
        for (Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint> e : pending.entrySet()) {
            final int id = e.getKey();
            final BreakpointTracker.PendingExceptionBreakpoint pb = e.getValue();
            if (matchesFilter(needle, pb.getExceptionClass(), null)) {
                rows.add(String.format("  #%d  %s  [PENDING]%s",
                    id, pb.getExceptionClass(), chainSuffix(id, null)));
            }
        }
        out.append(String.format("\nException breakpoints (%d):\n", rows.size()));
        rows.forEach(r -> out.append(r).append('\n'));
        return rows.size();
    }

    /**
     * Renders the field-breakpoint section. Pending entries (class not yet loaded) are tagged
     * `[PENDING]` so the agent can tell them apart from active ones at a glance.
     */
    private int appendFieldBreakpointOverview(StringBuilder out, @Nullable String needle) {
        final Map<Integer, BreakpointTracker.FieldBreakpointInfo> active =
            breakpointTracker.getAllFieldBreakpoints();
        final Map<Integer, BreakpointTracker.PendingFieldBreakpoint> pending =
            breakpointTracker.getAllPendingFieldBreakpoints();
        final List<String> rows = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointTracker.FieldBreakpointInfo> e : active.entrySet()) {
            final int id = e.getKey();
            final BreakpointTracker.FieldBreakpointSpec spec = e.getValue().getSpec();
            final String label = spec.className() + '.' + spec.fieldName();
            final String cond = breakpointTracker.getCondition(id);
            if (matchesFilter(needle, label, spec.expression(), cond)) {
                final EventRequest req = breakpointTracker.getEventRequestById(id);
                rows.add(String.format("  #%d  %s  mode=%s%s%s%s%s",
                    id, label, spec.mode(),
                    spec.logOnly() ? "  [log-only]" : "",
                    spec.expression() != null ? "  expr=\"" + spec.expression() + "\"" : "",
                    cond != null ? "  cond=\"" + cond + "\"" : "",
                    chainSuffix(id, req != null && req.isEnabled())));
            }
        }
        for (Map.Entry<Integer, BreakpointTracker.PendingFieldBreakpoint> e : pending.entrySet()) {
            final int id = e.getKey();
            final BreakpointTracker.FieldBreakpointSpec spec = e.getValue().getSpec();
            final String label = spec.className() + '.' + spec.fieldName();
            if (matchesFilter(needle, label, spec.expression())) {
                rows.add(String.format("  #%d  %s  mode=%s  [PENDING]%s",
                    id, label, spec.mode(), chainSuffix(id, null)));
            }
        }
        out.append(String.format("\nField breakpoints (%d):\n", rows.size()));
        rows.forEach(r -> out.append(r).append('\n'));
        return rows.size();
    }

    /**
     * Renders the watcher section. Rows are sorted by owning breakpoint ID so all watchers tied to
     * the same BP cluster together — the agent typically wants to see "what fires at BP #7" rather
     * than an alphabetical label list.
     */
    private int appendWatcherOverview(StringBuilder out, @Nullable String needle) {
        final List<Watcher> all = watcherManager.getAllWatchers();
        final List<Watcher> shown = all.stream()
            .filter(w -> matchesFilter(needle, w.getLabel(), w.getExpression()))
            .sorted(Comparator.comparingInt(Watcher::getBreakpointId))
            .toList();
        appendSectionHeader(out, "Watchers", shown.size(), all.size());
        for (Watcher w : shown) {
            out.append(String.format("  [%s] %s  on BP #%d  \"%s\"\n",
                w.getId().substring(0, 8), w.getLabel(), w.getBreakpointId(), w.getExpression()));
        }
        return shown.size();
    }

    /**
     * Appends a single {@code "  removed <descriptor>\n"} row used by every {@code jdwp_clear}
     * bulk path.
     */
    private static void appendBulkClearLine(StringBuilder out, String descriptor) {
        out.append("  removed ").append(descriptor).append('\n');
    }

    /**
     * Appends the {@code "\n<label> (<shown> of <total>):\n"} overview header. The "of total"
     * tail is omitted when no filter culled rows so unfiltered sections stay terse.
     */
    private static void appendSectionHeader(StringBuilder out, String label, int shown, int total) {
        out.append('\n').append(label).append(" (").append(shown);
        if (shown != total) {
            out.append(" of ").append(total);
        }
        out.append("):\n");
    }

    /**
     * Case-insensitive contains-any. {@code null} entries in {@code haystacks} are skipped so
     * callers can pass an optional expression without an extra null-guard. A {@code null} needle
     * means "no filter" and always matches.
     */
    private static boolean matchesFilter(@Nullable String needle, @Nullable String... haystacks) {
        if (needle == null) {
            return true;
        }
        for (String h : haystacks) {
            if (h != null && h.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bulk-clear marks matching {@code needle}.
     */
    private int clearMarks(StringBuilder out, @Nullable String needle) {
        final List<MarkInfo> matches = markedInstances.list().stream()
            .filter(m -> matchesFilter(needle, m.label(), m.typeName()))
            .toList();
        if (matches.isEmpty()) {
            return 0;
        }
        out.append(String.format("Marks: %d match%s\n", matches.size(), matches.size() == 1 ? "" : "es"));
        for (MarkInfo m : matches) {
            appendBulkClearLine(out, String.format("$%s -> Object#%d (%s)",
                m.label(), m.objectId(), m.typeName()));
            markedInstances.unmark(m.label());
        }
        return matches.size();
    }

    /**
     * Bulk-clear watchers matching {@code needle}.
     */
    private int clearWatchers(StringBuilder out, @Nullable String needle) {
        final List<Watcher> matches = watcherManager.getAllWatchers().stream()
            .filter(w -> matchesFilter(needle, w.getLabel(), w.getExpression()))
            .toList();
        if (matches.isEmpty()) {
            return 0;
        }
        out.append(String.format("Watchers: %d match%s\n", matches.size(), matches.size() == 1 ? "" : "es"));
        for (Watcher w : matches) {
            appendBulkClearLine(out, String.format("[%s] %s on BP #%d",
                w.getId().substring(0, 8), w.getLabel(), w.getBreakpointId()));
            watcherManager.deleteWatcher(w.getId());
        }
        return matches.size();
    }

    /**
     * Clears line breakpoints and/or logpoints matching the filter. Both kinds live in the same
     * BP map, so we partition active entries by presence of a logpoint expression to honour the
     * {@code includeBreakpoints} / {@code includeLogpoints} switches separately. Pending entries
     * are clearable only as "breakpoints" because a logpoint expression cannot be bound until the
     * BP is active.
     */
    private int clearLineBreakpoints(StringBuilder out, @Nullable String needle,
                                     boolean includeBreakpoints, boolean includeLogpoints) {
        final List<Integer> toClear = new ArrayList<>();
        final Map<Integer, BreakpointRequest> active = breakpointTracker.getAllBreakpoints();
        for (Map.Entry<Integer, BreakpointRequest> e : active.entrySet()) {
            final int id = e.getKey();
            final String expr = breakpointTracker.getLogpointExpression(id);
            final boolean isLogpoint = expr != null;
            if ((isLogpoint && !includeLogpoints) || (!isLogpoint && !includeBreakpoints)) {
                continue;
            }
            final Location loc = e.getValue().location();
            final String className = loc.declaringType().name();
            if (matchesFilter(needle, className, expr, breakpointTracker.getCondition(id))) {
                toClear.add(id);
            }
        }
        if (includeBreakpoints) {
            for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> e :
                    breakpointTracker.getAllPendingBreakpoints().entrySet()) {
                if (matchesFilter(needle, e.getValue().getClassName())) {
                    toClear.add(e.getKey());
                }
            }
        }
        if (toClear.isEmpty()) {
            return 0;
        }
        final String header = (includeBreakpoints && includeLogpoints) ? "Breakpoints + logpoints"
            : includeLogpoints ? "Logpoints" : "Breakpoints";
        out.append(String.format("%s: %d match%s\n", header, toClear.size(), toClear.size() == 1 ? "" : "es"));
        for (int id : toClear) {
            appendBulkClearLine(out, "#" + id);
            clearLineBreakpointById(id);
        }
        return toClear.size();
    }

    /**
     * Bulk-clear exception breakpoints matching {@code needle}, cascading watcher cleanup per BP.
     */
    private int clearExceptionBreakpoints(StringBuilder out, @Nullable String needle) {
        final List<Integer> toClear = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointTracker.ExceptionBreakpointInfo> e :
                breakpointTracker.getAllExceptionBreakpoints().entrySet()) {
            final BreakpointTracker.ExceptionBreakpointInfo info = e.getValue();
            if (matchesFilter(needle, info.getExceptionClass(), info.getExpression(),
                    breakpointTracker.getCondition(e.getKey()))) {
                toClear.add(e.getKey());
            }
        }
        for (Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint> e :
                breakpointTracker.getAllPendingExceptionBreakpoints().entrySet()) {
            if (matchesFilter(needle, e.getValue().getExceptionClass())) {
                toClear.add(e.getKey());
            }
        }
        if (toClear.isEmpty()) {
            return 0;
        }
        out.append(String.format("Exception breakpoints: %d match%s\n",
            toClear.size(), toClear.size() == 1 ? "" : "es"));
        for (int id : toClear) {
            appendBulkClearLine(out, "#" + id);
            breakpointTracker.removeExceptionBreakpoint(id);
            watcherManager.deleteWatchersForBreakpoint(id);
        }
        return toClear.size();
    }

    /**
     * Bulk-clear field breakpoints matching {@code needle}, cascading watcher cleanup per BP.
     */
    private int clearFieldBreakpoints(StringBuilder out, @Nullable String needle) {
        final List<Integer> toClear = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointTracker.FieldBreakpointInfo> e :
                breakpointTracker.getAllFieldBreakpoints().entrySet()) {
            final BreakpointTracker.FieldBreakpointSpec spec = e.getValue().getSpec();
            if (matchesFilter(needle, spec.className() + '.' + spec.fieldName(), spec.expression(),
                    breakpointTracker.getCondition(e.getKey()))) {
                toClear.add(e.getKey());
            }
        }
        for (Map.Entry<Integer, BreakpointTracker.PendingFieldBreakpoint> e :
                breakpointTracker.getAllPendingFieldBreakpoints().entrySet()) {
            final BreakpointTracker.FieldBreakpointSpec spec = e.getValue().getSpec();
            if (matchesFilter(needle, spec.className() + '.' + spec.fieldName(), spec.expression())) {
                toClear.add(e.getKey());
            }
        }
        if (toClear.isEmpty()) {
            return 0;
        }
        out.append(String.format("Field breakpoints: %d match%s\n",
            toClear.size(), toClear.size() == 1 ? "" : "es"));
        for (int id : toClear) {
            appendBulkClearLine(out, "#" + id);
            breakpointTracker.removeFieldBreakpoint(id);
            watcherManager.deleteWatchersForBreakpoint(id);
        }
        return toClear.size();
    }

    /**
     * Per-id line/logpoint removal — picks the right BreakpointTracker mutator and cleans up any
     * attached watchers, mirroring {@link #jdwp_clear_breakpoint}'s contract. The chain-break
     * cascade still runs via {@link #cascadeChainBreak(int)}; only its narration is suppressed
     * here so the bulk-clear report stays compact (the agent can re-render via the next overview).
     */
    private void clearLineBreakpointById(int id) {
        if (breakpointTracker.getBreakpoint(id) != null
                || breakpointTracker.getPendingBreakpoint(id) != null) {
            cascadeChainBreak(id);
            breakpointTracker.removeBreakpoint(id);
            watcherManager.deleteWatchersForBreakpoint(id);
        }
    }

    // ========================================
    // Watcher Management Tools
    // ========================================

    private String formatValue(@Nullable Value value) {
        return jdiService.formatFieldValue(value);
    }

    @McpTool(description = "One-shot debugging context at the current breakpoint: thread, top frames, locals at frame 0, and 'this' field dump. Use this instead of the four-call sequence (get_current_thread → get_stack → get_locals → get_fields(this)) at every BP hit.")
    public String jdwp_get_breakpoint_context(
        @McpToolParam(required = false, description = "Max stack frames to render (default: 5). Junit/maven/reflection frames are always collapsed.") @Nullable Integer maxFrames,
        @McpToolParam(required = false, description = "Include the 'this' field dump (default: true)") @Nullable Boolean includeThisFields) {
        final int frameLimit = (maxFrames != null && maxFrames > 0) ? maxFrames : 5;
        final boolean includeThis = includeThisFields == null || includeThisFields;

        try {
            final BreakpointTracker.LastBreakpoint snapshot = breakpointTracker.getLastBreakpoint();
            if (snapshot == null) {
                return "No current breakpoint detected. Set a breakpoint and trigger it first.";
            }
            final ThreadReference thread = snapshot.thread();
            if (!thread.isSuspended()) {
                return String.format("Thread %s (ID=%d) is no longer suspended.", thread.name(), thread.uniqueID());
            }

            final Integer bpId = snapshot.id();
            final StringBuilder sb = new StringBuilder();
            sb.append("=== Breakpoint Context ===\n");
            sb.append(String.format("Thread: %s (ID=%d, breakpoint=%s)\n\n",
                thread.name(), thread.uniqueID(), bpId));

            // Top frames (junit/maven/reflection collapsed via the same noise list as jdwp_get_stack)
            final List<StackFrame> frames = thread.frames();
            sb.append(String.format("--- Top frames (showing up to %d, %d total) ---\n", frameLimit, frames.size()));
            appendUserFrames(sb, frames, frameLimit, "  ");

            if (frames.isEmpty()) {
                sb.append("\n(thread has no frames — possibly suspended at VM startup before any user code)\n");
                return sb.toString();
            }
            sb.append('\n');

            // Locals at frame 0 (with synthetic 'this' the same way jdwp_get_locals does it)
            final StackFrame frame0 = frames.get(0);
            sb.append("--- Locals at frame 0 ---\n");
            final ObjectReference thisObj = frame0.thisObject();
            if (thisObj != null) {
                sb.append(String.format("  this (%s) = %s\n",
                    thisObj.referenceType().name(), formatValue(thisObj)));
            }
            try {
                final Map<LocalVariable, Value> vars = frame0.getValues(frame0.visibleVariables());
                if (vars.isEmpty() && thisObj == null) {
                    sb.append("  (none)\n");
                }
                for (Map.Entry<LocalVariable, Value> e : vars.entrySet()) {
                    sb.append(String.format("  %s (%s) = %s\n",
                        e.getKey().name(), e.getKey().typeName(), formatValue(e.getValue())));
                }
            } catch (AbsentInformationException e) {
                sb.append("  (no debug info — compile with -g)\n");
            }
            sb.append('\n');

            // 'this' field dump — instance fields only. Static fields are class-level state and
            // would clutter the dump (e.g. constant tables like PRICE_CATALOG showing up under
            // every instance) without telling the user anything about THIS object.
            if (includeThis && thisObj != null) {
                jdiService.cacheObject(thisObj);
                sb.append(String.format("--- this fields (Object#%d, %s) ---\n",
                    thisObj.uniqueID(), thisObj.referenceType().name()));
                final List<Field> instanceFields = thisObj.referenceType().allFields().stream()
                    .filter(f -> !f.isStatic())
                    .toList();
                if (instanceFields.isEmpty()) {
                    sb.append("  (no instance fields)\n");
                } else {
                    for (Field field : instanceFields) {
                        // Per-field guard: a single dead reference (e.g. ObjectCollectedException)
                        // must not torpedo the whole context dump. The user still benefits from
                        // seeing every other field, so we emit "<unavailable>" for the offender
                        // and continue rendering.
                        try {
                            final Value v = thisObj.getValue(field);
                            sb.append(String.format("  %s %s = %s\n", field.typeName(), field.name(), formatValue(v)));
                        } catch (Exception fieldErr) {
                            sb.append(String.format("  %s %s = <unavailable: %s>\n",
                                field.typeName(), field.name(), fieldErr.getClass().getSimpleName()));
                        }
                    }
                }
            } else if (includeThis) {
                sb.append("--- this --- (static method, no this)\n");
            }

            appendMarkedInstancesFooter(sb);
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Get the thread ID of the current breakpoint")
    public String jdwp_get_current_thread() {
        try {
            final BreakpointTracker.LastBreakpoint snapshot = breakpointTracker.getLastBreakpoint();
            if (snapshot == null) {
                return "No current breakpoint detected. Set a breakpoint and trigger it first.";
            }
            final ThreadReference thread = snapshot.thread();
            final Integer bpId = snapshot.id();
            return String.format("Current thread: %s (ID=%d, suspended=%s, frames=%d, breakpoint=%s)",
                thread.name(), thread.uniqueID(), thread.isSuspended(),
                thread.isSuspended() ? thread.frameCount() : -1,
                bpId);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Attach a watcher to a breakpoint to evaluate a Java expression when hit. Returns the watcher ID.")
    public String jdwp_attach_watcher(
        @McpToolParam(description = "Breakpoint request ID (from jdwp_overview)") int breakpointId,
        @McpToolParam(description = "Descriptive label for this watcher (e.g., 'Trace entity ID', 'Check user name')") String label,
        @McpToolParam(description = "Java expression to evaluate (e.g., 'entity.id', 'user.name', 'items.size()')") String expression) {
        try {
            if (expression.trim().isEmpty()) {
                return "Error: No expression provided";
            }
            if (label.trim().isEmpty()) {
                return "Error: No label provided";
            }

            // Create the watcher
            final String watcherId = watcherManager.createWatcher(label, breakpointId, expression.trim());

            return String.format("""
                    ✓ Watcher attached successfully
                    
                      Watcher ID: %s
                      Label: %s
                      Breakpoint: %d
                      Expression: %s
                    
                    The watcher will evaluate this expression when breakpoint %d is hit.
                    Use jdwp_detach_watcher(watcherId) to remove it.""",
                watcherId, label, breakpointId, expression.trim(), breakpointId
            );

        } catch (Exception e) {
            log.error("[Watcher] Error attaching watcher", e);
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "Detach a watcher from its breakpoint using the watcher ID")
    public String jdwp_detach_watcher(@McpToolParam(description = "Watcher ID (UUID returned by jdwp_attach_watcher)") String watcherId) {
        try {
            final Watcher watcher = watcherManager.getWatcher(watcherId);
            if (watcher == null) {
                return String.format(
                    "Error: Watcher '%s' not found.\n\nUse jdwp_overview(types=\"watcher\") to see active watchers.", watcherId
                );
            }

            final String label = watcher.getLabel();
            final int breakpointId = watcher.getBreakpointId();

            final boolean deleted = watcherManager.deleteWatcher(watcherId);
            if (deleted) {
                return String.format("✓ Watcher detached: '%s' (ID: %s, Breakpoint: %d)", label, watcherId, breakpointId);
            } else {
                return "Error: Failed to detach watcher";
            }

        } catch (Exception e) {
            log.error("[Watcher] Error detaching watcher", e);
            return "Error: " + e.getMessage();
        }
    }

    @McpTool(description = "List all watchers attached to a specific breakpoint")
    public String jdwp_list_watchers_for_breakpoint(@McpToolParam(description = "Breakpoint request ID") int breakpointId) {
        try {
            final List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);

            if (watchers.isEmpty()) {
                return String.format("""
                    No watchers attached to breakpoint %d.
                    
                    Use jdwp_attach_watcher(%d, "label", "expression") to attach a watcher.""", breakpointId, breakpointId);
            }

            final StringBuilder result = new StringBuilder();
            result.append(String.format("Watchers for breakpoint %d (%d total):\n\n", breakpointId, watchers.size()));

            for (int i = 0; i < watchers.size(); i++) {
                final Watcher w = watchers.get(i);
                result.append(String.format("%d. [%s] %s\n", i + 1, w.getId().substring(0, 8), w.getLabel()));
                result.append(String.format("   Expression: %s\n\n", w.getExpression()));
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[Watcher] Error listing watchers for breakpoint", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Evaluate watchers on a suspended thread's stack.
     * Can operate in two scopes:
     * - 'current_frame': (Default and Recommended) Evaluates watchers only for the breakpoint
     * that caused the suspension. Fast and precise.
     * - 'full_stack': Scans every frame of the stack to find any location matching any breakpoint
     * with a watcher. Powerful but slower.
     */
    @McpTool(description = "Evaluate watchers on a suspended thread's stack based on a scope. Returns '[VM_DEATH] ...' if the target VM disconnects mid-call.")
    public String jdwp_evaluate_watchers(
        @McpToolParam(description = "Thread unique ID") long threadId,
        @McpToolParam(description = "Evaluation scope: 'current_frame' (default) or 'full_stack'") String scope,
        @McpToolParam(required = false, description = "Optional: The specific breakpoint ID that was hit. If provided, evaluation is much faster for 'current_frame' scope") @Nullable Integer breakpointId) {
        try {
            // Fall back to the last breakpoint ID when the caller omits the parameter
            if (breakpointId == null) {
                breakpointId = breakpointTracker.getLastBreakpointId();
            }
            final VirtualMachine vm = jdiService.getVM();
            final ThreadReference thread = findThread(vm, threadId);
            if (thread == null) {
                return "Error: Thread not found with ID " + threadId;
            }

            if (!thread.isSuspended()) {
                return String.format("""
                    [ERROR] Thread %d is NOT suspended
                    
                    Thread must be stopped at a breakpoint to evaluate watchers.""", threadId);
            }

            // Configure classpath here, not inside evaluate(), to avoid nested JDI calls.
            expressionEvaluator.configureCompilerClasspath(thread);

            if (scope.isBlank()) {
                scope = "current_frame";
            }

            final StringBuilder result = new StringBuilder();
            result.append(String.format("=== Watcher Evaluation for Thread %d (Scope: %s) ===\n\n", threadId, scope));
            result.append(String.format("Thread: %s (frames: %d)\n\n", thread.name(), thread.frameCount()));

            final int watchersEvaluated;
            if ("full_stack".equalsIgnoreCase(scope)) {
                watchersEvaluated = evaluateWatchersFullStack(thread, result);
            } else {
                watchersEvaluated = evaluateWatchersCurrentFrame(thread, breakpointId, result);
            }

            if (watchersEvaluated == 0) {
                result.append("No watchers found or evaluated for the given scope.\n");
            } else {
                result.append(String.format("Total: Evaluated %d expression(s)\n", watchersEvaluated));
            }

            return result.toString();

        } catch (VMDisconnectedException vmDead) {
            return vmDisconnectedMessage("jdwp_evaluate_watchers");
        } catch (Exception e) {
            log.error("[Watcher] Error evaluating watchers", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Evaluates watchers for the current (topmost) stack frame only.
     *
     * @param thread       the suspended thread whose frame 0 will be inspected
     * @param breakpointId the breakpoint ID to look up watchers for
     * @param result       accumulator for formatted evaluation output
     * @return number of watchers successfully evaluated
     */
    private int evaluateWatchersCurrentFrame(
        ThreadReference thread, @Nullable Integer breakpointId, StringBuilder result) throws Exception {
        if (thread.frameCount() == 0) {
            return 0;
        }

        final StackFrame frame = thread.frame(0);
        final Location location = frame.location();
        int watchersEvaluated = 0;

        if (breakpointId == null) {
            result.append("No breakpoint ID available — cannot resolve watchers for current frame.\n");
            return 0;
        }

        final List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);
        if (watchers.isEmpty()) {
            return 0;
        }

        result.append(String.format("─── Current Frame #0: %s:%d (Breakpoint ID: %d) ───\n\n",
            location.declaringType().name(), location.lineNumber(), breakpointId));

        final Map<String, Value> markBindings = markedInstances.buildBindings();
        for (Watcher watcher : watchers) {
            result.append(String.format("  • [%s] %s\n", watcher.getId().substring(0, 8), watcher.getLabel()));
            try {
                final Value value = expressionEvaluator.evaluate(frame, watcher.getExpression(), markBindings);
                result.append(String.format("    %s = %s\n\n", watcher.getExpression(), formatValue(value)));
                watchersEvaluated++;
            } catch (Exception e) {
                result.append(String.format("    %s = [ERROR: %s]\n\n", watcher.getExpression(), e.getMessage()));
            }
        }
        return watchersEvaluated;
    }

    /**
     * Evaluates watchers across the entire call stack by scanning each frame's location against
     * the breakpoint location map. Only frames that match a known breakpoint have their watchers evaluated.
     *
     * @param thread the suspended thread whose full stack will be scanned
     * @param result accumulator for formatted evaluation output
     * @return total number of watchers successfully evaluated across all matching frames
     */
    private int evaluateWatchersFullStack(ThreadReference thread, StringBuilder result) throws Exception {
        final Map<String, Integer> locationToBreakpointId = breakpointTracker.getBreakpointLocationMap();
        if (locationToBreakpointId.isEmpty()) {
            result.append("No breakpoints found. Cannot evaluate watchers.\n");
            return 0;
        }

        int watchersEvaluated = 0;
        final List<StackFrame> frames = thread.frames();
        final Map<String, Value> markBindings = markedInstances.buildBindings();

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            final StackFrame frame = frames.get(frameIndex);
            final Location location = frame.location();
            final String locationKey = location.declaringType().name() + ':' + location.lineNumber();

            final Integer breakpointId = locationToBreakpointId.get(locationKey);
            if (breakpointId == null) {
                continue;
            }

            final List<Watcher> watchers = watcherManager.getWatchersForBreakpoint(breakpointId);
            if (watchers.isEmpty()) {
                continue;
            }

            result.append(String.format("─── Frame #%d: %s:%d (Breakpoint ID: %d) ───\n\n",
                frameIndex, location.declaringType().name(), location.lineNumber(), breakpointId));

            for (Watcher watcher : watchers) {
                result.append(String.format("  • [%s] %s\n", watcher.getId().substring(0, 8), watcher.getLabel()));
                try {
                    final Value value = expressionEvaluator.evaluate(frame, watcher.getExpression(), markBindings);
                    result.append(String.format("    %s = %s\n\n", watcher.getExpression(), formatValue(value)));
                    watchersEvaluated++;
                } catch (Exception e) {
                    result.append(String.format("    %s = [ERROR: %s]\n\n", watcher.getExpression(), e.getMessage()));
                }
            }
        }
        return watchersEvaluated;
    }
}
