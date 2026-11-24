package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.discovery.LocalJdwpTarget;
import one.edee.mcp.jdwp.evaluation.ClasspathDiscoverer;
import one.edee.mcp.jdwp.evaluation.ClasspathDiscoverer.DiscoveryResult;
import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import one.edee.mcp.jdwp.evaluation.JdkDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdkDiscoveryService.JdkNotFoundException;
import one.edee.mcp.jdwp.evaluation.PinnedMirrors;
import one.edee.mcp.jdwp.evaluation.RemoteCodeExecutor;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static one.edee.mcp.jdwp.ThreadFormatting.isJvmInternalThread;

/**
 * Singleton service maintaining a persistent JDI connection to a JDWP-enabled target JVM.
 * <p>
 * Responsibilities:
 * - Connect / disconnect / auto-reconnect on a dropped connection (see {@link #getVM()}).
 * - Object reference caching: every JDI {@link ObjectReference} returned via {@link #formatFieldValue}
 * is stored in {@link #objectCache} so subsequent tool calls can navigate object graphs by ID.
 * The cache is cleared on disconnect and `jdwp_reset`.
 * - Classpath discovery for expression evaluation: collaborates with {@link ClasspathDiscoverer}
 * and {@link JdkDiscoveryService} on demand. Later compiles can extend the connection's classpath
 * or retry failed loader reads before the state is discarded on disconnect.
 * - Post-mortem cleanup driven by {@link JdiEventListener}'s VM-death hook (see
 * {@link #notifyVmDied()}), distinct from the user-initiated {@link #disconnect()} path.
 * <p>
 * Connection transitions and classpath publication use the service monitor. Classpath walks run
 * outside that monitor. The object cache is a {@link ConcurrentHashMap}, and the classpath/JDK
 * fields are volatile for cross-thread reads.
 */
@Service
public class JDIConnectionService {
    private static final Logger log = LoggerFactory.getLogger(JDIConnectionService.class);
    /**
     * Maximum number of entries/elements to render in any smart-collection view.
     */
    private static final int COLLECTION_VIEW_LIMIT = 50;
    /**
     * Names of the eight Java primitive wrapper types — gates {@link #tryUnboxPrimitive} fast-path.
     */
    private static final Set<String> BOXED_PRIMITIVE_TYPES = Set.of(
        "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
        "java.lang.Boolean", "java.lang.Character", "java.lang.Byte", "java.lang.Short");
    /**
     * Maximum tree depth for {@link #walkTreeMapInOrder}. A balanced TreeMap of 50 entries (our
     * {@link #COLLECTION_VIEW_LIMIT}) needs at most ~6 levels; 64 is generous enough for any
     * realistic tree and will terminate promptly on a corrupted or circular structure.
     */
    private static final int MAX_TREE_DEPTH = 64;
    private final JdiEventListener eventListener;
    private final BreakpointTracker breakpointTracker;
    private final EventHistory eventHistory;
    private final WatcherManager watcherManager;
    private final EvaluationGuard evaluationGuard;
    /**
     * Passive JDI traffic observer + active wedge probe. Lifecycle is gated by this service:
     * armed in {@link #connect}, torn down in {@link #cleanupSessionState} / {@link #notifyVmDied}.
     */
    private final JdiHealthMonitor healthMonitor;
    private final JvmDiscoveryService jvmDiscovery;
    /** Asynchronous locality and process evidence for the current attachment. */
    private LocalJdwpTarget.@Nullable Lookup targetLookup;
    /**
     * Server-wide registry of agent-labelled JDI object references. Held here so its lifecycle is
     * gated by the connection: pinned object references must be released both on VM death (see
     * {@link #notifyVmDied()}) and on explicit session teardown (see {@link #cleanupSessionState()})
     * — otherwise stale pins would survive a reconnect and leak across sessions.
     */
    private final MarkedInstanceRegistry markedInstances;
    /**
     * Restores bytecode that fast logpoints rewrote before the VM handle is disposed; registered by
     * {@link FastLogpointService} itself, null in tests that build the service without it.
     */
    @Nullable
    private volatile FastLogpointService fastLogpoints;
    /**
     * Leftover-fast-logpoint notice of the latest attach, until {@link #takeAttachNotice} takes it.
     */
    private volatile String attachNotice = "";
    /**
     * Maps {@link ObjectReference#uniqueID()} to the live JDI mirror so MCP tools can reference
     * objects by ID across multiple calls. Populated as a side effect of {@link #formatFieldValue}
     * (and {@link #getArrayElements}); cleared on disconnect and on `jdwp_reset`.
     */
    private final Map<Long, ObjectReference> objectCache = new ConcurrentHashMap<>();
    @Nullable
    private VirtualMachine vm;
    /**
     * Host of the last SUCCESSFUL attach. Drives {@link #ensureConnected}'s auto-reconnect path;
     * stays null until an attach actually succeeds, so a failed first connect cannot bait a
     * downstream tool call into silently retrying against a never-valid target.
     */
    @Nullable
    private String lastHost;
    /** Port of the last SUCCESSFUL attach. See {@link #lastHost} for semantics. */
    private int lastPort = 0;
    /** Normalized endpoint of the last successful attach, also kept as the auto-reconnect seed's identity. */
    private @Nullable TargetEndpoint targetEndpoint;
    /**
     * What the target's exit took with it — set by {@link #notifyVmDied}, cleared by the next attach
     * and by {@link #disconnect}. Lets {@code jdwp_reset} and {@code jdwp_disconnect} say that the
     * target exited instead of reporting an empty session.
     */
    @Nullable
    private volatile VmExit lastExit;
    /**
     * Host of the most recent {@link #connect} attempt, successful or not. Distinct from
     * {@link #lastHost} so a failed first connect can be rendered in {@code jdwp_diagnose}
     * ("tried foo:5005 and got Connection refused") without seeding the reconnect target.
     */
    @Nullable
    private String lastConnectAttemptHost;
    /** Port of the most recent {@link #connect} attempt. See {@link #lastConnectAttemptHost}. */
    private int lastConnectAttemptPort = 0;
    /**
     * Timestamp of the most recent {@link #connect} attempt (successful or not). Used by the
     * {@code jdwp_diagnose} tool to render a "last attempt: ..." line when not connected.
     */
    @Nullable
    private volatile Instant lastConnectAttempt;
    /**
     * Human-readable error from the most recent {@link #connect} failure, or {@code null} when
     * the last attempt succeeded. Reset to null on a successful attach.
     */
    @Nullable
    private volatile String lastConnectError;
    /** Last published walk and its path, cleared together when the connection ends. */
    private volatile @Nullable RemoteClasspath classpathDiscovery;
    /**
     * Filesystem path to a local JDK matching the target JVM version. Populated as a side effect of
     * {@link #discoverClasspath} and consumed by {@link InMemoryJavaCompiler}
     * for the `--system` argument; cleared on disconnect.
     */
    @Nullable
    private volatile String discoveredJdkPath;
    /**
     * Major Java version of the target JVM (e.g., 8, 11, 17, 21); 0 until {@link #discoverClasspath}
     * has been called. Used by the JDT compiler to pick the correct `-source`/`-target` strings.
     */
    private volatile int targetMajorVersion = 0;

    public JDIConnectionService(JdiEventListener eventListener, BreakpointTracker breakpointTracker,
                                EventHistory eventHistory, WatcherManager watcherManager,
                                EvaluationGuard evaluationGuard, MarkedInstanceRegistry markedInstances,
                                JdiHealthMonitor healthMonitor, JvmDiscoveryService jvmDiscovery) {
        this.eventListener = eventListener;
        this.breakpointTracker = breakpointTracker;
        this.eventHistory = eventHistory;
        this.watcherManager = watcherManager;
        this.evaluationGuard = evaluationGuard;
        this.markedInstances = markedInstances;
        this.healthMonitor = healthMonitor;
        this.jvmDiscovery = jvmDiscovery;
        // Wire post-mortem cleanup so the listener can null the VM the moment the target dies.
        eventListener.setVmDeathHook(this::notifyVmDied);
        eventListener.setVmExitingHook(this::captureExit);
        // Wire traffic observation so every JDI event drained from the queue advances the
        // health monitor's last-traffic timestamp without the listener knowing about the monitor.
        eventListener.setTrafficObserver(healthMonitor::notifyTraffic);
    }

    /**
     * Pure type-name check for the eight Java primitive wrapper classes. Extracted as a separate
     * static so it can be unit-tested without a {@link ObjectReference}.
     */
    static boolean isBoxedPrimitiveType(@Nullable String typeName) {
        return typeName != null && BOXED_PRIMITIVE_TYPES.contains(typeName);
    }

    /**
     * Allow-list of {@code java.util} collection types whose internal field layout is known and
     * stable enough for the smart-view rendering. Anything not on this list falls through to the
     * generic field dump in {@link #getObjectFields}.
     */
    private static boolean isCollection(String typeName) {
        return collectionKind(typeName) != CollectionKind.UNKNOWN;
    }

    /**
     * Classifies a JDK collection type by its concrete class name. Uses explicit equality checks
     * rather than substring matching so future additions like {@code ConcurrentSkipListMap}
     * (which contains both "List" and "Map") cannot accidentally route to the wrong branch.
     */
    private static CollectionKind collectionKind(String typeName) {
        return switch (typeName) {
            case "java.util.ArrayList", "java.util.LinkedList" -> CollectionKind.LIST;
            case "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap" -> CollectionKind.MAP;
            case "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet" -> CollectionKind.SET;
            default -> CollectionKind.UNKNOWN;
        };
    }

    /**
     * Appends a {@code "... (N more …)"} footer when the collection's reported size exceeds the
     * smart-view limit. {@code label} is typically {@code "entries"} or {@code "elements"}.
     */
    private static void appendOverflowFooter(StringBuilder out, int size, String label) {
        if (size > COLLECTION_VIEW_LIMIT) {
            out.append(String.format("  ... (%d more %s)\n", size - COLLECTION_VIEW_LIMIT, label));
        }
    }

    /**
     * If {@code obj} is a wrapper type for a Java primitive, reads its private {@code value} field
     * directly via JDI (no invocation needed) and returns the unboxed string form. Returns {@code null}
     * for any other type so the caller can fall through to the regular {@code Object#N (...)} rendering.
     */
    @Nullable
    static String tryUnboxPrimitive(ObjectReference obj, ReferenceType type, String typeName) {
        if (!isBoxedPrimitiveType(typeName)) {
            return null;
        }
        final Field valueField = type.fieldByName("value");
        if (valueField == null) {
            return null;
        }
        final Value inner = obj.getValue(valueField);
        if (inner instanceof PrimitiveValue) {
            return inner.toString();
        }
        return null;
    }

    /**
     * Defensive frame count probe — returns `-1` if the thread is in a state where `frameCount()`
     * throws (e.g., not suspended, or suspended in a state JDI cannot inspect).
     */
    private static int tryFrameCount(ThreadReference thread) {
        try {
            return thread.frameCount();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Checks whether a thread satisfies JDI's preconditions for `invokeMethod`: must be suspended
     * AND have at least one stack frame. JDI rejects invocations on threads suspended via
     * `vm.suspend()` (no frames yet) or threads suspended in native waits.
     */
    private static boolean isUsableForInvoke(ThreadReference t) {
        try {
            return t.isSuspended() && t.frameCount() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cheap, optimistic liveness probe — issues the JDI-cached {@code vm.name()} and returns false
     * on any exception. Because {@code name()} is cached after its first fetch, this can report a VM
     * as alive after its socket has already closed; it is therefore only used on the hot
     * {@code getVM()} / {@link #ensureConnected()} precondition path, where a false positive is
     * harmless (the next real JDI operation surfaces {@code VMDisconnectedException}). Authoritative
     * checks that must not alias to a dead VM use {@link #isVMResponsive()} instead. Does NOT mutate
     * the {@link #vm} field.
     */
    private boolean isVMAlive() {
        final VirtualMachine local = vm;
        if (local == null) {
            return false;
        }
        try {
            local.name();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Connect-time liveness timeout. A held VM that cannot answer a round-tripping JDI command
     * within this budget is treated as dead, so {@link #connect} re-attaches rather than aliasing to
     * it. Deliberately shorter than the health monitor's wedged-detection probe — a connect attempt
     * should not stall on a hung VM.
     */
    private static final long LIVENESS_PROBE_TIMEOUT_MS = 2_000L;

    /**
     * Authoritative liveness probe: issues a round-tripping {@code vm.allThreads()} and waits up to
     * {@link #LIVENESS_PROBE_TIMEOUT_MS} for it. Unlike {@link #isVMAlive()} (which calls the
     * JDI-cached {@code vm.name()} and so cannot detect a socket that closed since the first fetch),
     * this forces a fresh JDWP exchange: a dead socket throws promptly and a wedged VM is bounded by
     * the timeout, so a non-live VM reliably reads as dead. Used by the {@link #connect}
     * "already connected" guard and by {@link #getConnectionStatus()} so neither aliases to a stale
     * VM (e.g. an orphaned test JVM left over from a previous debug session). Side-effect free apart
     * from the probe traffic; does NOT mutate the {@link #vm} field.
     */
    private boolean isVMResponsive() {
        final VirtualMachine local = vm;
        if (local == null) {
            return false;
        }
        final ExecutorService probe = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "jdi-liveness-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            final Future<Boolean> result = probe.submit(() -> {
                local.allThreads();
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(result.get(LIVENESS_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException probeFailed) {
            return false;
        } finally {
            probe.shutdownNow();
        }
    }

    /**
     * Registers the service whose instrumentation must be reverted before every VM dispose.
     */
    public void setFastLogpointService(@Nullable FastLogpointService service) {
        this.fastLogpoints = service;
    }

    /**
     * Returns and clears the notice about fast logpoints an earlier session left in the target,
     * found by the latest attach ({@code ""} when none). Kept until taken, so a notice from an
     * {@link #ensureConnected} auto-reconnect — whose reply nobody sees — reaches the next attach
     * reply that asks for it.
     */
    public String takeAttachNotice() {
        final String notice = attachNotice;
        attachNotice = "";
        return notice;
    }

    /**
     * Lists fast logpoints left in {@code attached} by an earlier session; never fails the attach.
     */
    private void discoverLeftovers(VirtualMachine attached) {
        final FastLogpointService flps = fastLogpoints;
        if (flps != null) {
            attachNotice = flps.discover(attached);
        }
    }

    /**
     * Attaches to a JDWP server. If a live connection to the same {@code host}/{@code port} already
     * exists this is a no-op; if the requested target differs from the current one the existing
     * session is torn down via {@link #cleanupSessionState()} before the fresh attach so breakpoints,
     * watchers and cached object references cannot leak across targets.
     *
     * @param host hostname of the target JVM
     * @param port JDWP debug port
     * @return status message indicating connection result
     */
    public synchronized String connect(String host, int port) throws Exception {
        return connect(host, port, false);
    }

    /**
     * {@link #connect(String, int)}, except that with {@code requireVmStart} an existing session is never
     * reused or replaced: the reply starts with "Already connected" so the caller can refuse it.
     */
    synchronized String connect(String host, int port, boolean requireVmStart) throws Exception {
        lastConnectAttempt = Instant.now();
        lastConnectAttemptHost = host;
        lastConnectAttemptPort = port;
        String releasedNotice = "";
        if (vm != null && isVMResponsive()) {
            if (requireVmStart) {
                return "Already connected to " + vm.name() + " at " + lastHost + ':' + lastPort;
            }
            if (host.equals(lastHost) && port == lastPort) {
                lastConnectError = null;
                return "Already connected to " + vm.name();
            }
            // Deliberate host/port change — release current session so the fresh attach below
            // does not leak breakpoints / watchers / ObjectReferences across targets. Snapshot the
            // discarded-spec notice first so connect() does not silently drop them on the floor.
            releasedNotice = describeReleasedSession();
            log.info("[JDI] Switching target from {}:{} to {}:{} — releasing current session",
                lastHost, lastPort, host, port);
            cleanupSessionState();
        } else if (vm != null) {
            // Probe said dead but the field is still non-null — wipe stale session caches
            // before the fresh attach so they cannot leak across attachments.
            releasedNotice = describeReleasedSession();
            log.info("[JDI] Clearing stale state from previous (dead) VM connection");
            cleanupSessionState();
        }

        try {
            final SocketAttachment attached = attachSocket(socketConnector(), host, port);
            vm = attached.vm();
            targetEndpoint = attached.endpoint();
        } catch (Exception e) {
            lastConnectError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // Do NOT seed lastHost/lastPort on failure — ensureConnected() uses those for the
            // auto-reconnect path and a never-valid target would otherwise be retried silently
            // on the next tool call. The attempt host/port are tracked separately for diagnostics.
            throw e;
        }
        lastHost = host;
        lastPort = port;
        lastConnectError = null;
        lastExit = null;

        // Advance the event-history session epoch before any event of this attach is recorded. If a
        // prior VM died and its events were preserved in the buffer (notifyVmDied keeps them), this
        // new session's events get a distinct epoch so jdwp_get_events can segment old from new.
        eventHistory.beginNewSession();

        eventListener.start(vm);
        targetLookup = LocalJdwpTarget.startLookup(jvmDiscovery, host, port);
        // Arm the health watchdog. The attach round-trip itself counts as traffic, so the
        // monitor seeds lastTrafficAt to now and only escalates to an active probe if the
        // listener subsequently sees a full silence window.
        healthMonitor.start(vm);
        discoverLeftovers(vm);

        return releasedNotice + String.format("Connected to %s (version %s)", vm.name(), vm.version());
    }

    /** The current JDI handle, without the auto-reconnect and promotion side effects of getVM(). */
    @Nullable
    synchronized VirtualMachine attachedVM() {
        return vm;
    }

    /**
     * The attached target's process identity line (PID, start time, command) for attach replies;
     * {@code ""} when the target is remote or could not be identified within the lookup budget.
     */
    synchronized String describeTargetProcess() {
        return LocalJdwpTarget.await(targetLookup).describe();
    }

    /**
     * Builds the one-line "previous session released" notice that {@link #connect} prepends when it
     * tears down a differing (or dead) target before a fresh attach. Returns the empty string when
     * no breakpoints or watchers were set — there is then nothing the agent needs warning about, and
     * connect()'s reply stays terse. When specs <em>were</em> cleared, the notice names the counts
     * and points at {@code jdwp_reconnect} as the preserve-across-restart alternative.
     *
     * <p>Package-private rather than private: the notice is only emitted on a live-VM target-switch
     * success path (which needs a real attach), so the only feasible unit test calls this directly.
     */
    String describeReleasedSession() {
        final int breakpoints =
            breakpointTracker.getAllBreakpoints().size() + breakpointTracker.getAllPendingBreakpoints().size()
            + breakpointTracker.getAllExceptionBreakpoints().size() + breakpointTracker.getAllPendingExceptionBreakpoints().size()
            + breakpointTracker.getAllFieldBreakpoints().size() + breakpointTracker.getAllPendingFieldBreakpoints().size();
        final int watchers = watcherManager.getAllWatchers().size();
        if (breakpoints + watchers == 0) {
            return "";
        }
        final String previousTarget = lastHost != null ? String.format("%s:%d", lastHost, lastPort) : "previous target";
        return String.format(
            "Released previous session at %s — %d breakpoint(s), %d watcher(s) cleared. "
            + "(To keep specs across a same-target restart, use jdwp_reconnect instead.)%n",
            previousTarget, breakpoints, watchers);
    }

    /**
     * Read-only snapshot of the connection target used by {@code jdwp_diagnose}. The
     * {@code connected} field runs a fresh round-tripping liveness probe ({@link #isVMResponsive()})
     * on every call, so callers must not cache the returned status — the underlying VM can die
     * between calls.
     */
    public synchronized ConnectionStatus getConnectionStatus() {
        return new ConnectionStatus(
            vm != null && isVMResponsive(),
            lastConnectAttemptHost,
            lastConnectAttemptPort,
            lastConnectAttempt,
            lastConnectError
        );
    }

    /**
     * Immutable snapshot of the JDI connection used for diagnostic rendering. {@code connected}
     * is {@code true} only when there is a live {@link VirtualMachine}; the cached
     * {@code lastHost}/{@code lastPort} reflect the most recent {@link #connect} attempt
     * regardless of outcome, so diagnostics can describe what the user was trying to attach to.
     * The internal reconnect target driven by {@code ensureConnected} is tracked separately
     * and is only seeded after a successful attach.
     *
     * @param connected           {@code true} when a live VM exists right now (liveness-probed
     *                            via {@code isVMAlive()}); not "was once connected"
     * @param lastHost            host of the most recent {@link #connect} attempt, successful
     *                            or not; {@code null} when no attempt was ever made
     * @param lastPort            port of the most recent attempt; {@code 0} when no attempt
     *                            was ever made
     * @param lastConnectAttempt  wall-clock time of the most recent attempt; {@code null}
     *                            when no attempt was ever made in this MCP-server lifetime
     * @param lastConnectError    human-readable failure reason from the most recent attempt;
     *                            {@code null} when the last attempt succeeded
     */
    public record ConnectionStatus(
        boolean connected,
        @Nullable String lastHost,
        int lastPort,
        @Nullable Instant lastConnectAttempt,
        @Nullable String lastConnectError
    ) {}

    /** Reads the last successful endpoint without probing or connecting to the JVM. */
    synchronized @Nullable TargetEndpoint targetEndpoint() {
        return targetEndpoint;
    }

    /** Confirms which endpoint produced a particular VM, even if an attach raced with getVM(). */
    synchronized @Nullable TargetEndpoint targetEndpointFor(VirtualMachine expected) {
        return vm == expected ? targetEndpoint : null;
    }

    /** JDWP endpoint for a standing approval. Loopback is determined by the address used to attach. */
    record TargetEndpoint(String host, int port, boolean loopback) {
        /** Canonicalizes loopback and host spelling while preserving case-sensitive IPv6 zones. */
        TargetEndpoint {
            if (loopback) {
                host = "localhost";
            } else {
                host = stripHostBrackets(host);
                final int zone = host.contains(":") ? host.indexOf('%') : -1;
                host = zone < 0 ? host.toLowerCase(Locale.ROOT)
                    : host.substring(0, zone).toLowerCase(Locale.ROOT) + host.substring(zone);
            }
        }

        @Override
        public String toString() {
            final String label = host.contains(":") ? "[" + host + "]" : host;
            return label + ':' + port + (!loopback && host.equals("localhost") ? " (non-loopback)" : "");
        }
    }

    /**
     * A VM attached through {@code SocketAttach}, with the endpoint derived from the same resolved
     * address the connector used.
     */
    record SocketAttachment(VirtualMachine vm, TargetEndpoint endpoint) {
    }

    /**
     * The JDK's {@code com.sun.jdi.SocketAttach} connector.
     */
    private static AttachingConnector socketConnector() {
        for (AttachingConnector connector : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(connector.name())) {
                return connector;
            }
        }
        throw new IllegalStateException("SocketAttach connector not found");
    }

    /**
     * Resolves the destination and passes its numeric address to SocketAttach. For unspecified
     * addresses, applies the socket's local-host substitution before connecting and keying.
     * If the local hostname is also unspecified, uses loopback in that address family.
     */
    static SocketAttachment attachSocket(AttachingConnector connector, String host, int port)
        throws IOException, IllegalConnectorArgumentsException {
        final String unbracketed = stripHostBrackets(host);
        final InetAddress resolved = unbracketed.isEmpty() || "*".equals(unbracketed)
            ? InetAddress.getLoopbackAddress() : InetAddress.getByName(unbracketed);
        final boolean wildcard = resolved.isAnyLocalAddress();
        InetAddress address = wildcard ? InetAddress.getLocalHost() : resolved;
        if (address.isAnyLocalAddress()) {
            address = InetAddress.getByName(address instanceof Inet4Address ? "127.0.0.1" : "::1");
        }
        final String numericHost = address.getHostAddress();
        final String endpointHost = wildcard ? numericHost : unbracketed;
        final Map<String, Connector.Argument> args = connector.defaultArguments();
        Objects.requireNonNull(args.get("hostname"), "SocketAttach connector missing 'hostname' argument")
            .setValue(numericHost);
        Objects.requireNonNull(args.get("port"), "SocketAttach connector missing 'port' argument")
            .setValue(String.valueOf(port));
        return new SocketAttachment(connector.attach(args),
            new TargetEndpoint(endpointHost, port, address.isLoopbackAddress()));
    }

    /** Removes one pair of address brackets without changing the enclosed address or its zone. */
    private static String stripHostBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /**
     * Verifies the VM connection is alive, attempting a single reconnect using the host/port from the
     * last successful {@link #connect}. Throws with a "use jdwp_connect first" hint if no prior
     * connection was ever attempted (cached host/port are null/0).
     */
    private synchronized void ensureConnected() throws Exception {
        if (vm == null || !isVMAlive()) {
            if (lastHost != null && lastPort != 0) {
                connect(lastHost, lastPort);
            } else {
                throw new Exception("Not connected to JDWP server. Use jdwp_connect first.");
            }
        }
    }

    /**
     * Disconnects from the JDWP server. Also stops the event listener and clears all session state
     * (breakpoints, watchers, marks, object cache, event history, reconnect seed). The returned
     * {@link DisconnectResult} snapshots what was cleared <em>before</em> {@link #cleanupSessionState()}
     * zeroes it, so the tool layer can report it to the agent and steer toward {@code jdwp_reconnect}
     * when breakpoints/watchers were worth preserving.
     *
     * @return a {@link DisconnectResult} describing what was cleared; {@code wasConnected() == false}
     *         when there was no live VM (a no-op)
     */
    public synchronized DisconnectResult disconnect() {
        if (vm == null) {
            final VmExit exit = lastExit;
            lastExit = null;
            final LocalJdwpTarget target = exit != null ? LocalJdwpTarget.await(targetLookup) : null;
            targetLookup = null;
            return DisconnectResult.notConnected(exit, target);
        }
        // Snapshot the state about to be wiped, before cleanupSessionState() zeroes it.
        final DisconnectResult result = new DisconnectResult(
            true, lastHost, lastPort,
            breakpointTracker.getAllBreakpoints().size() + breakpointTracker.getAllPendingBreakpoints().size(),
            breakpointTracker.getAllExceptionBreakpoints().size() + breakpointTracker.getAllPendingExceptionBreakpoints().size(),
            breakpointTracker.getAllFieldBreakpoints().size() + breakpointTracker.getAllPendingFieldBreakpoints().size(),
            watcherManager.getAllWatchers().size(),
            markedInstances.size(),
            objectCache.size(),
            eventHistory.size(), null, LocalJdwpTarget.await(targetLookup));
        cleanupSessionState();
        return result;
    }

    /**
     * Snapshots what the target's exit takes with it into {@link #lastExit}. The listener calls it
     * when the target exits — after reading the in-target probes, before closing the session, which
     * wakes a {@code jdwp_resume_until_event} waiter — so that waiter's {@code [VM_DEATH]} reply, and
     * a later {@code jdwp_reset} or {@code jdwp_disconnect}, can report it; {@link #notifyVmDied}
     * drops the state right after. Tags the snapshot with the exiting session's epoch. Takes no
     * lock: the listener must not wait behind a tool call.
     */
    void captureExit(SuspensionSession session) {
        if (eventHistory.currentEpoch() != session.epoch() || breakpointTracker.suspensionSession() != session) {
            return;
        }
        final List<VmExit.FieldWatch> watches = new ArrayList<>();
        for (Map.Entry<Integer, BreakpointTracker.FieldBreakpointInfo> entry
            : breakpointTracker.getAllFieldBreakpoints().entrySet()) {
            final BreakpointTracker.FieldBreakpointSpec spec = entry.getValue().getSpec();
            watches.add(new VmExit.FieldWatch(entry.getKey(), spec.className() + '.' + spec.fieldName(),
                spec.mode().name().toLowerCase(Locale.ROOT), spec.logOnly(), entry.getValue().hits()));
        }
        watches.sort(Comparator.comparingInt(VmExit.FieldWatch::id));
        final FastLogpointService flps = fastLogpoints;
        final VmExit exit = new VmExit(Instant.now(), lastHost, lastPort,
            breakpointTracker.getAllBreakpoints().size() + breakpointTracker.getAllPendingBreakpoints().size(),
            breakpointTracker.getAllExceptionBreakpoints().size() + breakpointTracker.getAllPendingExceptionBreakpoints().size(),
            breakpointTracker.getAllFieldBreakpoints().size() + breakpointTracker.getAllPendingFieldBreakpoints().size(),
            flps != null ? flps.ownCount() : 0,
            watcherManager.getAllWatchers().size(),
            List.copyOf(watches), session.epoch());
        if (eventHistory.currentEpoch() == session.epoch() && breakpointTracker.suspensionSession() == session) {
            lastExit = exit;
        }
    }

    /**
     * Runs post-mortem cleanup when a caller reports that the current connection has died.
     * Idempotent and best-effort: preserves {@link #eventHistory} (the listener's VM_DEATH entry
     * stays queryable) and {@link #lastHost} / {@link #lastPort} (so {@link #ensureConnected()}
     * can auto-reconnect on the next restart cycle). Marked-instance pins are released as a side
     * effect — the underlying JDI references are gone with the VM, so keeping them would only
     * leak dangling entries into the next session.
     */
    public synchronized void notifyVmDied() {
        final VirtualMachine current = vm;
        if (current != null) {
            notifyVmDied(current);
        }
    }

    /**
     * Runs post-mortem cleanup for the VM named by the listener's death hook. The hook can run
     * on the listener thread or the caller of {@link JdiEventListener#stop()}. A delayed callback
     * for an old VM leaves its replacement untouched.
     */
    private synchronized void notifyVmDied(VirtualMachine deadVm) {
        if (vm != deadVm) {
            return;
        }
        eventListener.cancelLocalClasspathDiscovery(deadVm);
        try {
            deadVm.dispose();
        } catch (Exception ignored) {
            // VM already gone — nothing to dispose.
        }
        vm = null;
        // The instrumented classes died with the VM — nothing to restore, only state to drop.
        final FastLogpointService flps = fastLogpoints;
        if (flps != null) {
            flps.forgetAll();
        }
        breakpointTracker.reset();
        watcherManager.clearAll();
        markedInstances.clearAll();
        objectCache.clear();
        classpathDiscovery = null;
        discoveredJdkPath = null;
        targetMajorVersion = 0;
        healthMonitor.stop();
    }

    /**
     * Releases all session-bound state held by the MCP server: JDI event requests, object cache,
     * watchers, marked instances, classpath cache, event history, and the VM reference itself. Best-effort —
     * tolerates a dead VM (falls back to in-memory {@code reset()} when JDI calls would fail).
     * Also clears the auto-reconnect seed ({@link #lastHost}/{@link #lastPort}) so a subsequent
     * tool call cannot silently re-attach to the just-released target — this is the
     * user-initiated semantics. For VM-death cleanup invoked by the event listener (which must
     * preserve {@link #eventHistory} and the reconnect target so the next restart cycle can
     * re-attach) see {@link #notifyVmDied()}.
     */
    private void cleanupSessionState() {
        // Restore rewritten bytecode while the VM handle still exists — vm.dispose() does not revert
        // class redefinitions. Must precede eventListener.stop(): stop() may run the VM-death hook
        // (notifyVmDied) on this thread, which disposes the VM and forgets the instrumentation.
        // Bounded, so a dead or wedged VM cannot stall the teardown.
        final FastLogpointService flps = fastLogpoints;
        if (flps != null) {
            if (vm != null) {
                flps.disarmAll("session teardown");
            } else {
                flps.forgetAll();
            }
        }

        eventListener.stop();

        if (vm == null) {
            breakpointTracker.reset();
        } else {
            try {
                breakpointTracker.clearAll(vm.eventRequestManager());
            } catch (Exception e) {
                // VM died mid-session — JDI calls would fail, so zero the in-memory state instead.
                breakpointTracker.reset();
            }
        }

        watcherManager.clearAll();
        markedInstances.clearAll();
        objectCache.clear();
        classpathDiscovery = null;
        discoveredJdkPath = null;
        targetMajorVersion = 0;
        eventHistory.clear();

        if (vm != null) {
            try {
                vm.dispose();
            } catch (Exception ignored) {
                // VM may already be disconnected.
            }
            vm = null;
        }

        targetLookup = null;
        lastHost = null;
        lastPort = 0;
        targetEndpoint = null;
        lastExit = null;
        healthMonitor.stop();
    }

    /**
     * Recovers from a wedged JDI connection without losing breakpoint specs. Snapshots every
     * breakpoint spec, condition, logpoint expression, chain edge, and watcher; disposes the
     * (possibly wedged) VM; re-attaches to the last successful host:port; replays the snapshot
     * so synthetic breakpoint IDs stay stable; then registers a {@link ClassPrepareRequest} per
     * unique pending class and runs the opportunistic promoter so classes already loaded by the
     * fresh VM bind immediately.
     *
     * <p><b>What survives the reconnect</b>: synthetic breakpoint IDs, line/exception/field BP
     * specs, conditions, logpoint expressions, chain edges, watchers, event history.
     * <b>What is lost</b> (cannot survive {@code vm.dispose()}): marked instances, object cache,
     * last suspended thread context, classpath discovery cache, any in-flight {@code invokeMethod}.
     * The target VM resumes — re-suspending threads after a reattach is not possible in JDI.
     *
     * @return a structured {@link ReconnectResult} describing what was restored and what was lost;
     *         callers (the {@code jdwp_reconnect} tool) format this for the agent
     * @throws Exception when no prior successful attach exists — fresh-target requests must be
     *                   routed through {@code jdwp_connect} so an incident-time recovery cannot
     *                   accidentally swap VMs — or when the fresh attach itself fails
     */
    public synchronized ReconnectResult reconnectPreservingSpecs() throws Exception {
        if (lastHost == null || lastPort == 0) {
            throw new IllegalStateException(
                "No prior successful attach — call jdwp_connect or jdwp_wait_for_attach first.");
        }
        final String host = lastHost;
        final int port = lastPort;
        targetLookup = null;

        final BreakpointTracker.ReconnectSnapshot snapshot = breakpointTracker.snapshotForReconnect();
        final int markedCount = markedInstances.list().size();
        final int objectCacheCount = objectCache.size();

        // Detach the VM-death hook ONLY around the intentional eventListener.stop(). The hook is
        // wired to notifyVmDied(), which calls watcherManager.clearAll() — keeping it attached
        // during the stop would silently wipe the watchers that the reconnect contract promises
        // to preserve. Re-attaching immediately after stop() returns means a genuine VM death
        // AFTER the fresh attach still routes through notifyVmDied(); a wider window (e.g.
        // detach for the whole method body) would silently swallow a real disconnect of the
        // fresh VM in the gap between fresh-attach and method exit.
        eventListener.setVmDeathHook(null);
        try {
            // Tear down anything JDI-bound that cannot survive vm.dispose(). Done before the
            // dispose itself so the listener does not drain spurious events on the dying queue.
            eventListener.stop();
        } finally {
            eventListener.setVmDeathHook(this::notifyVmDied);
        }

        healthMonitor.stop();
        // Fast logpoints are not carried across a reconnect: their instrumentation is reverted here
        // (bounded — reconnect exists for wedged VMs) and consent never transfers to the fresh attach.
        final FastLogpointService flps = fastLogpoints;
        if (flps != null) {
            // Their watchers go with them.
            for (List<Integer> ids : flps.breakpointIdsByLocation().values()) {
                for (int id : ids) {
                    watcherManager.deleteWatchersForBreakpoint(id);
                }
            }
            flps.disarmAll("jdwp_reconnect");
        }
        final int watcherCount = watcherManager.getAllWatchers().size();
        if (vm != null) {
            try {
                vm.dispose();
            } catch (Exception ignored) {
                // VM may already be wedged or dead — the dispose is best-effort.
            }
            vm = null;
        }
        markedInstances.clearAll();
        objectCache.clear();
        classpathDiscovery = null;
        discoveredJdkPath = null;
        targetMajorVersion = 0;

        // Restore the tracker into pure-pending state BEFORE the fresh attach. After dispose
        // the active maps are holding JDI request handles tied to the dead VM — any later
        // snapshot/inspection on those would throw VMDisconnectedException. Putting the
        // tracker into "everything pending, no JDI handles" state up front means that if the
        // reattach itself fails, the agent can safely re-call jdwp_reconnect (or fall back
        // to jdwp_connect, which calls cleanupSessionState anyway).
        breakpointTracker.restoreFromSnapshotAsPending(snapshot);

        // Fresh attach to the last known target — no host/port input from the caller so a
        // mid-incident operator cannot accidentally swap targets while assuming their BPs survive.
        lastConnectAttempt = Instant.now();
        lastConnectAttemptHost = host;
        lastConnectAttemptPort = port;
        try {
            final SocketAttachment attached = attachSocket(socketConnector(), host, port);
            vm = attached.vm();
            targetEndpoint = attached.endpoint();
        } catch (Exception e) {
            lastConnectError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // Tracker is already in pending state from the restore above — the agent's BPs
            // are recoverable via a retry. No further bookkeeping needed here beyond
            // surfacing the original error.
            throw e;
        }
        lastConnectError = null;
        // Fresh attach → new session epoch (see connect()); the RECONNECT event recorded below and
        // everything after it is tagged as this session, segmented from the dead VM's tail.
        eventHistory.beginNewSession();
        eventListener.start(vm);
        targetLookup = LocalJdwpTarget.startLookup(jvmDiscovery, host, port);
        healthMonitor.start(vm);
        discoverLeftovers(vm);

        // Register a ClassPrepareRequest per unique pending class name so the listener picks
        // up subsequent loads. The pending entries themselves were already restored above;
        // already-loaded classes will bind immediately via tryPromotePending below.
        final EventRequestManager erm = vm.eventRequestManager();
        final Set<String> classesNeedingPrepare = new java.util.LinkedHashSet<>();
        for (BreakpointTracker.LineBreakpointEntry e : snapshot.lineBreakpoints()) {
            classesNeedingPrepare.add(e.className());
        }
        for (BreakpointTracker.ExceptionBreakpointEntry e : snapshot.exceptionBreakpoints()) {
            classesNeedingPrepare.add(e.spec().exceptionClass());
        }
        for (BreakpointTracker.FieldBreakpointEntry e : snapshot.fieldBreakpoints()) {
            classesNeedingPrepare.add(e.spec().className());
        }
        for (String className : classesNeedingPrepare) {
            try {
                final ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                cpr.addClassFilter(className);
                cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                cpr.enable();
                breakpointTracker.registerClassPrepareRequest(className, cpr);
            } catch (Exception cprError) {
                // Best-effort: a CPR failure for one class must not prevent the rest of the
                // reconnect from completing. The opportunistic promoter below will still bind
                // already-loaded classes, and a subsequent class load will simply not auto-promote.
                log.warn("[Reconnect] Failed to register ClassPrepareRequest for {}: {}",
                    className, cprError.getMessage());
            }
        }

        // Promote any pending entries whose class is already loaded in the fresh VM.
        final int promoted;
        try {
            promoted = breakpointTracker.tryPromotePending(this);
        } catch (Exception e) {
            // Defensive — the tracker's safety-net promoter is best-effort even on the regular
            // path; an exception here must not turn a successful re-attach into a failure.
            log.warn("[Reconnect] Opportunistic promotion threw: {}", e.getMessage());
            eventHistory.record(new EventHistory.DebugEvent("RECONNECT",
                String.format("Reconnected to %s:%d; promotion error", host, port),
                Map.of("host", host, "port", String.valueOf(port))));
            return new ReconnectResult(
                host, port,
                0,
                snapshot.lineBreakpoints().size(),
                snapshot.lineBreakpoints().size(),
                snapshot.exceptionBreakpoints().size(),
                snapshot.fieldBreakpoints().size(),
                watcherCount,
                markedCount,
                objectCacheCount
            );
        }

        eventHistory.record(new EventHistory.DebugEvent("RECONNECT",
            String.format("Reconnected to %s:%d, promoted %d/%d line BPs", host, port,
                promoted, snapshot.lineBreakpoints().size()),
            Map.of("host", host, "port", String.valueOf(port),
                "promoted", String.valueOf(promoted))));

        // breakpointsById is the line-BP map; reading its size after tryPromotePending gives
        // the exact count of line BPs that bound against already-loaded classes. The rest
        // remain pending in pendingBreakpointsById.
        final int activeLines = breakpointTracker.getAllBreakpoints().size();
        final int deferredLines = snapshot.lineBreakpoints().size() - activeLines;

        return new ReconnectResult(
            host, port,
            activeLines, Math.max(0, deferredLines),
            snapshot.lineBreakpoints().size(),
            snapshot.exceptionBreakpoints().size(),
            snapshot.fieldBreakpoints().size(),
            watcherCount, markedCount, objectCacheCount
        );
    }

    /**
     * Structured outcome of {@link #reconnectPreservingSpecs} for the {@code jdwp_reconnect} MCP
     * tool to render. Counts capture the "what survived / what was lost" contract:
     * BPs and watchers survive ({@code restoredLineActive} + {@code restoredLineDeferred} sum to
     * {@code restoredLineTotal}), while marked instances and the object cache are wiped (the
     * pre-reconnect counts are echoed so the agent knows what they need to re-establish).
     *
     * @param host                    target host the fresh attach succeeded against
     * @param port                    target port the fresh attach succeeded against
     * @param restoredLineActive      line BPs immediately bound (class already loaded)
     * @param restoredLineDeferred    line BPs still pending (class not yet loaded)
     * @param restoredLineTotal       total line BPs replayed (active + deferred)
     * @param restoredExceptionTotal  exception BPs replayed
     * @param restoredFieldTotal      field watchpoints replayed
     * @param watchersPreserved       watchers carried over from the previous session
     * @param markedInstancesLost     marked instances dropped (cannot survive vm.dispose)
     * @param objectCacheLost         object cache entries dropped (likewise)
     */
    public record ReconnectResult(
        String host, int port,
        int restoredLineActive, int restoredLineDeferred, int restoredLineTotal,
        int restoredExceptionTotal, int restoredFieldTotal,
        int watchersPreserved, int markedInstancesLost, int objectCacheLost
    ) {}

    /**
     * Structured outcome of {@link #disconnect()} so the {@code jdwp_disconnect} tool can tell the
     * agent exactly what session state the call discarded — a vanished breakpoint set should never
     * be a silent surprise — and nudge it toward {@code jdwp_reconnect} when specs worth keeping
     * were cleared. Counts are snapshotted under the service monitor immediately before
     * {@link #cleanupSessionState()} zeroes them, so they reflect what this call actually threw away.
     * {@code wasConnected == false} is the no-op case (no live VM); all counts are then zero.
     *
     * @param wasConnected         {@code true} when a live VM was attached and state was cleared
     * @param host                 last attached host ({@code null} only in the not-connected case)
     * @param port                 last attached port ({@code 0} in the not-connected case)
     * @param lineBreakpoints      line BPs discarded (active + deferred)
     * @param exceptionBreakpoints exception BPs discarded (active + deferred)
     * @param fieldBreakpoints     field watchpoints discarded (active + deferred)
     * @param watchers             watchers discarded
     * @param markedInstances      marked instances released
     * @param objectCacheEntries   cached object references dropped
     * @param eventHistoryEntries  event-history entries cleared
     * @param exit                 in the not-connected case, the target's exit that ended the session
     *                             (or {@code null})
     */
    public record DisconnectResult(
        boolean wasConnected,
        @Nullable String host,
        int port,
        int lineBreakpoints,
        int exceptionBreakpoints,
        int fieldBreakpoints,
        int watchers,
        int markedInstances,
        int objectCacheEntries,
        int eventHistoryEntries,
        @Nullable VmExit exit,
        @Nullable LocalJdwpTarget target
    ) {
        public DisconnectResult(boolean wasConnected, @Nullable String host, int port, int lineBreakpoints,
                                int exceptionBreakpoints, int fieldBreakpoints, int watchers, int markedInstances,
                                int objectCacheEntries, int eventHistoryEntries) {
            this(wasConnected, host, port, lineBreakpoints, exceptionBreakpoints, fieldBreakpoints, watchers,
                markedInstances, objectCacheEntries, eventHistoryEntries, null, null);
        }

        static DisconnectResult notConnected() {
            return notConnected(null, null);
        }

        static DisconnectResult notConnected(@Nullable VmExit exit, @Nullable LocalJdwpTarget target) {
            return new DisconnectResult(false, null, 0, 0, 0, 0, 0, 0, 0, 0, exit, target);
        }

        String describeTargetProcess() {
            if (target == null) {
                return "";
            }
            return exit != null ? target.describeAt(exit.at()) : target.describeAfterDisconnect(port);
        }

        /** Total breakpoints across line/exception/field, active + deferred. */
        public int totalBreakpoints() {
            return lineBreakpoints + exceptionBreakpoints + fieldBreakpoints;
        }

        /** {@code true} when the call discarded anything an agent might miss. */
        public boolean clearedAnything() {
            return totalBreakpoints() + watchers + markedInstances + objectCacheEntries + eventHistoryEntries > 0;
        }
    }

    /**
     * The target VM's exit, and the session state it took with it: {@link #notifyVmDied} drops the
     * breakpoints, probes and watchers of a VM that is gone, and the tools that run afterwards report
     * this instead of an empty session.
     *
     * @param at                   when the server noticed the exit
     * @param host                 host of the attach that ended ({@code null} when unknown)
     * @param port                 port of that attach
     * @param lineBreakpoints      line breakpoints and logpoints dropped (active + deferred)
     * @param exceptionBreakpoints exception breakpoints dropped (active + deferred)
     * @param fieldBreakpoints     field breakpoints dropped (active + deferred)
     * @param probes               fast logpoints and fast breakpoints dropped
     * @param watchers             watchers dropped
     * @param fieldWatches         the active field breakpoints and how often each fired
     * @param sessionEpoch        the history epoch of the exiting connection
     */
    public record VmExit(Instant at, @Nullable String host, int port, int lineBreakpoints, int exceptionBreakpoints,
                         int fieldBreakpoints, int probes, int watchers, List<FieldWatch> fieldWatches,
                         int sessionEpoch) {

        /**
         * An active field breakpoint at the exit.
         *
         * @param id      its ID
         * @param field   {@code declaring.Class.field}
         * @param mode    {@code access}, {@code modification} or {@code both}
         * @param logOnly whether it is a field logpoint
         * @param hits    watchpoint events it received
         */
        public record FieldWatch(int id, String field, String mode, boolean logOnly, long hits) {
        }

        /**
         * "the target VM at host:port exited at 18:42:10" — the clock of the server's time zone.
         */
        public String describe() {
            final String target = host != null ? "the target VM at " + host + ':' + port : "the target VM";
            return target + " exited at " + formatServerTime(at);
        }

        /**
         * "2 breakpoints, 1 field breakpoint and 1 in-target probe", or {@code ""} when nothing was set.
         */
        public String droppedState() {
            final List<String> parts = new ArrayList<>(5);
            addCount(parts, lineBreakpoints, "breakpoint");
            addCount(parts, exceptionBreakpoints, "exception breakpoint");
            addCount(parts, fieldBreakpoints, "field breakpoint");
            addCount(parts, probes, "in-target probe");
            addCount(parts, watchers, "watcher");
            if (parts.size() <= 1) {
                return parts.isEmpty() ? "" : parts.get(0);
            }
            return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        }

        private static void addCount(List<String> parts, int count, String noun) {
            if (count > 0) {
                parts.add(count + " " + noun + (count == 1 ? "" : "s"));
            }
        }
    }

    /**
     * "18:42:10 CET" — a time of day in the server's time zone, the format every reply uses.
     */
    static String formatServerTime(Instant at) {
        return DateTimeFormatter.ofPattern("HH:mm:ss z").format(at.atZone(ZoneId.systemDefault()));
    }

    /**
     * The exit that ended the last session, while no new attach followed it; {@code null} otherwise.
     */
    @Nullable
    public VmExit lastExit() {
        return lastExit;
    }

    /**
     * Returns the connected VirtualMachine, auto-reconnecting if the connection has dropped.
     *
     * <p><b>Concurrency:</b> the connect / auto-reconnect work runs under the service monitor (so
     * a concurrent {@code disconnect()} cannot race with an in-flight reconnect), but the
     * opportunistic pending-breakpoint retry runs <i>outside</i> the monitor. The retry path may
     * park inside a JDI {@code invokeMethod} (force-loading a deferred class), which can only
     * complete once our event listener drains the resulting events; holding this monitor across
     * that round-trip would block every other {@code getVM()} caller — and thus every other MCP
     * tool call — for the duration of the invoke.
     *
     * @return the live {@link VirtualMachine} instance
     * @throws Exception if not connected and reconnection fails
     */
    public VirtualMachine getVM() throws Exception {
        final VirtualMachine current;
        synchronized (this) {
            ensureConnected();
            current = vm;
        }
        // Opportunistic pending-breakpoint retry — binds a class that loaded while no
        // ClassPrepareRequest was watching for it (e.g. between a tool's lookup and its CPR enable).
        // Every class loaded after the attach, bootstrap classes included, does deliver a
        // ClassPrepareEvent; this is only the safety net. Best-effort; failures are swallowed. Runs
        // outside the service monitor (see class-level Javadoc for the rationale).
        try {
            breakpointTracker.tryPromotePending(this);
        } catch (Exception e) {
            log.debug("[JDI] Pending promotion failed: {}", e.getMessage());
        }
        return Objects.requireNonNull(current);
    }

    /**
     * Returns the raw VM reference without triggering opportunistic promotion. Used by
     * {@link BreakpointTracker#tryPromotePending(JDIConnectionService)} to avoid recursion. Reads
     * the {@code vm} field directly without taking the service monitor — the field is written under
     * the monitor and read here as a plain reference; a stale read returns a recently-disposed
     * {@link VirtualMachine} whose JDI calls throw {@link com.sun.jdi.VMDisconnectedException},
     * which the caller already handles.
     */
    @Nullable
    VirtualMachine getRawVM() {
        return vm;
    }

    /**
     * Stores an ObjectReference in the cache for later cross-call inspection. Thread-safe, null-safe.
     */
    public void cacheObject(@Nullable ObjectReference obj) {
        if (obj != null) {
            objectCache.put(obj.uniqueID(), obj);
        }
    }

    /**
     * Renders the fields/elements of a previously cached object. The rendering branches on type:
     * - Arrays: first 100 elements via {@link #getArrayElements}.
     * - Recognised `java.util` collections (`ArrayList`, `LinkedList`, `HashMap`, `LinkedHashMap`,
     * `HashSet`, `TreeMap`, `TreeSet`): "smart view" with size, first 50 elements/entries, and the
     * raw internal fields, via {@link #getCollectionView}.
     * - Anything else: a flat list of all fields (including inherited).
     * <p>
     * Reads only — does not mutate target VM state. Relies on JDI's own thread-safety for
     * concurrent frame inspection.
     *
     * @param objectId unique ID of a previously cached {@link ObjectReference}
     * @return formatted string listing fields/elements, or an `[ERROR]` message if the object is not in cache
     */
    public synchronized String getObjectFields(long objectId) throws Exception {
        ensureConnected();

        final ObjectReference obj = objectCache.get(objectId);
        if (obj == null) {
            return String.format("""
                    [ERROR] Object #%d not found in cache
                    
                    This object was not previously discovered.
                    Use jdwp_get_locals() to discover objects in the current scope.""",
                objectId);
        }

        try {
            if (obj instanceof ArrayReference arr) {
                return getArrayElements(arr, objectId);
            }

            final ReferenceType refType = obj.referenceType();
            final String typeName = refType.name();

            if (typeName.startsWith("java.util.") && isCollection(typeName)) {
                return getCollectionView(obj, objectId, typeName);
            }

            final StringBuilder result = new StringBuilder();
            result.append(String.format("Object #%d (%s):\n\n", objectId, refType.name()));

            final List<Field> fields = refType.allFields();

            for (Field field : fields) {
                final Value value = obj.getValue(field);
                final String valueStr = formatFieldValue(value);

                result.append(String.format("%s %s = %s\n",
                    field.typeName(),
                    field.name(),
                    valueStr));
            }

            return result.toString();
        } catch (Exception e) {
            // Never surface the useless literal "Error: null": some JDI exceptions carry no detail
            // message. Same fallback idiom this class already uses for connect() failures.
            return "Error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Renders a "smart view" for one of the supported collection types: prints the {@code size}
     * field, dispatches to the type-specific element/entry walker, and then dumps the raw
     * internal fields for completeness. Dispatch is by exact-name classification via
     * {@link #collectionKind(String)}.
     */
    private String getCollectionView(ObjectReference obj, long objectId, String typeName) {
        final StringBuilder result = new StringBuilder();
        result.append(String.format("Object #%d (%s):\n\n", objectId, typeName));

        try {
            final Field sizeField = obj.referenceType().fieldByName("size");
            if (sizeField != null) {
                final Value sizeValue = obj.getValue(sizeField);
                if (!(sizeValue instanceof IntegerValue iv)) {
                    result.append("Size: unknown (size field is not an integer)\n\n");
                    return result.toString();
                }
                final int size = iv.value();
                result.append(String.format("Size: %d\n\n", size));

                switch (collectionKind(typeName)) {
                    case LIST -> result.append(getListElements(obj, size));
                    case MAP -> result.append(getMapEntries(obj, size));
                    case SET -> result.append(getSetElements(obj, size));
                    case UNKNOWN -> { /* allow-list filtered by isCollection — unreachable */ }
                }
            }

            result.append("\n--- Internal fields ---\n\n");
            for (Field field : obj.referenceType().allFields()) {
                final Value value = obj.getValue(field);
                final String valueStr = formatFieldValue(value);
                result.append(String.format("%s %s = %s\n",
                    field.typeName(), field.name(), valueStr));
            }

        } catch (Exception e) {
            result.append("Error inspecting collection: ").append(e.getMessage());
        }

        return result.toString();
    }

    /**
     * Renders the first 50 elements of a List. Handles {@link java.util.ArrayList} via its internal
     * {@code elementData} Object[] and {@link java.util.LinkedList} via its {@code first}/{@code next}
     * node chain (reading each node's {@code item} field). Limited to 50 elements for performance
     * and human readability.
     */
    private String getListElements(ObjectReference list, int size) {
        final StringBuilder result = new StringBuilder(128);
        result.append("Elements:\n");

        try {
            // ArrayList stores elements in an internal Object[] named "elementData".
            final Field elementDataField = list.referenceType().fieldByName("elementData");
            if (elementDataField != null) {
                final ArrayReference array = (ArrayReference) list.getValue(elementDataField);
                if (array != null) {
                    final int limit = Math.min(size, COLLECTION_VIEW_LIMIT);
                    for (int i = 0; i < limit; i++) {
                        final Value value = array.getValue(i);
                        result.append(String.format("  [%d] = %s\n", i, formatFieldValue(value)));
                    }
                    if (size > COLLECTION_VIEW_LIMIT) {
                        result.append(String.format("  ... (%d more elements)\n", size - COLLECTION_VIEW_LIMIT));
                    }
                    return result.toString();
                }
            }

            // LinkedList stores elements in a "first" → "next" Node chain. Each Node has an "item" field.
            final Field firstField = list.referenceType().fieldByName("first");
            if (firstField != null) {
                ObjectReference node = (ObjectReference) list.getValue(firstField);
                int index = 0;
                while (node != null && index < COLLECTION_VIEW_LIMIT) {
                    final Field itemField = node.referenceType().fieldByName("item");
                    if (itemField != null) {
                        final Value item = node.getValue(itemField);
                        result.append(String.format("  [%d] = %s\n", index, formatFieldValue(item)));
                    }
                    final Field nextField = node.referenceType().fieldByName("next");
                    if (nextField == null) {
                        break;
                    }
                    node = (ObjectReference) node.getValue(nextField);
                    index++;
                }
                if (size > COLLECTION_VIEW_LIMIT) {
                    result.append(String.format("  ... (%d more elements)\n", size - COLLECTION_VIEW_LIMIT));
                }
            }
        } catch (Exception e) {
            result.append("  Error: ").append(e.getMessage()).append('\n');
        }

        return result.toString();
    }

    /**
     * Renders the first 50 entries of a Map. Handles three layouts:
     * <ul>
     *   <li>{@link java.util.LinkedHashMap} — walks the doubly-linked {@code head} → {@code after} chain.</li>
     *   <li>{@link java.util.HashMap} — walks the {@code table[]} bucket array following each
     *       {@code Node.next} chain.</li>
     *   <li>{@link java.util.TreeMap} — walks the red-black tree rooted at {@code root} via
     *       in-order {@code left}/{@code right} traversal.</li>
     * </ul>
     * Limited to 50 entries for performance.
     */
    private String getMapEntries(ObjectReference map, int size) {
        final StringBuilder result = new StringBuilder(256);
        result.append("Entries:\n");

        try {
            final ReferenceType mapType = map.referenceType();

            // LinkedHashMap path: doubly-linked "head" → "after" insertion-order chain.
            final Field headField = mapType.fieldByName("head");
            if (headField != null) {
                ObjectReference entry = (ObjectReference) map.getValue(headField);
                int count = 0;
                while (entry != null && count < COLLECTION_VIEW_LIMIT) {
                    appendMapEntry(result, entry);
                    Field nextField = entry.referenceType().fieldByName("after");
                    if (nextField == null) {
                        nextField = entry.referenceType().fieldByName("next");
                    }
                    if (nextField == null) {
                        break;
                    }
                    entry = (ObjectReference) entry.getValue(nextField);
                    count++;
                }
                appendOverflowFooter(result, size, "entries");
                return result.toString();
            }

            // HashMap path: table[] of Node buckets, each bucket is a "next" chain.
            final Field tableField = mapType.fieldByName("table");
            if (tableField != null) {
                final ArrayReference table = (ArrayReference) map.getValue(tableField);
                if (table != null) {
                    int rendered = 0;
                    final int length = table.length();
                    for (int i = 0; i < length && rendered < COLLECTION_VIEW_LIMIT; i++) {
                        ObjectReference bucket = (ObjectReference) table.getValue(i);
                        while (bucket != null && rendered < COLLECTION_VIEW_LIMIT) {
                            appendMapEntry(result, bucket);
                            rendered++;
                            final Field nextField = bucket.referenceType().fieldByName("next");
                            if (nextField == null) {
                                break;
                            }
                            bucket = (ObjectReference) bucket.getValue(nextField);
                        }
                    }
                    appendOverflowFooter(result, size, "entries");
                    return result.toString();
                }
            }

            // TreeMap path: in-order traversal from "root" using "left"/"right" children.
            final Field rootField = mapType.fieldByName("root");
            if (rootField != null) {
                final ObjectReference root = (ObjectReference) map.getValue(rootField);
                final int[] counter = new int[]{0};
                walkTreeMapInOrder(root, counter, result);
                appendOverflowFooter(result, size, "entries");
            }
        } catch (Exception e) {
            result.append("  Error: ").append(e.getMessage()).append('\n');
        }

        return result.toString();
    }

    /**
     * In-order traversal of a TreeMap entry tree. Stops once {@link #COLLECTION_VIEW_LIMIT} entries
     * have been rendered or {@link #MAX_TREE_DEPTH} is exceeded (guards against corrupted/circular trees).
     * A {@code null} node (empty TreeMap or missing child) is a benign no-op so an empty map renders
     * as "Entries:" rather than an "Error: NullPointerException" line.
     */
    private void walkTreeMapInOrder(@Nullable ObjectReference node, int[] counter, StringBuilder out) {
        walkTreeMapInOrder(node, counter, out, 0);
    }

    // Right-child traversal is iterative (tail-call optimization) to limit stack depth on right-leaning trees
    private void walkTreeMapInOrder(@Nullable ObjectReference node, int[] counter, StringBuilder out, int depth) {
        while (true) {
            if (node == null || counter[0] >= COLLECTION_VIEW_LIMIT || depth >= MAX_TREE_DEPTH) {
                return;
            }
            final ReferenceType type = node.referenceType();
            final Field leftField = type.fieldByName("left");
            final Field rightField = type.fieldByName("right");

            if (leftField != null && node.getValue(leftField) instanceof ObjectReference left) {
                walkTreeMapInOrder(left, counter, out, depth + 1);
            }
            if (counter[0] >= COLLECTION_VIEW_LIMIT) {
                return;
            }

            appendMapEntry(out, node);
            counter[0]++;

            if (rightField != null && node.getValue(rightField) instanceof ObjectReference right) {
                node = right;
                depth++;
                continue;
            }
            return;
        }
    }

    /**
     * Appends a single {@code key = value} row to {@code out} for the given map entry node.
     */
    private void appendMapEntry(StringBuilder out, ObjectReference entry) {
        final ReferenceType type = entry.referenceType();
        final Field keyField = type.fieldByName("key");
        final Field valueField = type.fieldByName("value");
        if (keyField == null || valueField == null) {
            return;
        }
        final Value key = entry.getValue(keyField);
        final Value value = entry.getValue(valueField);
        out.append(String.format("  %s = %s\n", formatFieldValue(key), formatFieldValue(value)));
    }

    /**
     * Renders the elements of a Set by following its internal backing-map field. {@link java.util.HashSet}
     * uses {@code map}; {@link java.util.TreeSet} uses {@code m}. Once the backing map is located the
     * set elements are read from its keys via {@link #getMapEntries}.
     */
    private String getSetElements(ObjectReference set, int size) {
        final StringBuilder result = new StringBuilder(128);
        result.append("Elements:\n");

        try {
            // HashSet delegates to an internal HashMap stored in a field named "map".
            // TreeSet delegates to a TreeMap stored in a field named "m" (single letter).
            Field mapField = set.referenceType().fieldByName("map");
            if (mapField == null) {
                mapField = set.referenceType().fieldByName("m");
            }
            if (mapField != null) {
                final ObjectReference map = (ObjectReference) set.getValue(mapField);
                if (map != null) {
                    // Extract keys from the map (values are dummy PRESENT objects for HashSet).
                    result.append(getMapEntries(map, size));
                }
            }
        } catch (Exception e) {
            result.append("  Error: ").append(e.getMessage()).append('\n');
        }

        return result.toString();
    }

    /**
     * Renders the first 100 elements of a JDI array reference. Limit is hardcoded to keep responses
     * bounded for the MCP client; longer arrays are summarised with a "more elements" footer.
     */
    private String getArrayElements(ArrayReference array, long arrayId) {
        final StringBuilder result = new StringBuilder();
        final int length = array.length();
        final String typeName = array.type().name();

        result.append(String.format("Array #%d (%s) - %d elements:\n\n", arrayId, typeName, length));

        final int limit = Math.min(length, 100);

        for (int i = 0; i < limit; i++) {
            final Value value = array.getValue(i);
            final String valueStr = formatFieldValue(value);
            result.append(String.format("[%d] = %s\n", i, valueStr));
        }

        if (length > 100) {
            result.append(String.format("\n... (%d more elements)\n", length - 100));
        }

        return result.toString();
    }

    /**
     * Formats a JDI {@link Value} for human-readable display. Caches any encountered
     * {@link ObjectReference} as a side effect so it can be inspected in subsequent calls.
     *
     * @param value the JDI value to format (may be null)
     * @return formatted string representation
     */
    public String formatFieldValue(@Nullable Value value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof StringReference strRef) {
            return '"' + strRef.value() + '"';
        }

        if (value instanceof PrimitiveValue) {
            return value.toString();
        }

        if (value instanceof ArrayReference arr) {
            cacheObject(arr);
            return String.format("Array#%d (%s[%d])",
                arr.uniqueID(), arr.type().name(), arr.length());
        }

        if (value instanceof ObjectReference obj) {
            final ReferenceType type = obj.referenceType();
            final String typeName = type.name();
            final String unboxed = tryUnboxPrimitive(obj, type, typeName);
            if (unboxed != null) {
                return unboxed;
            }
            cacheObject(obj);
            return String.format("Object#%d (%s)", obj.uniqueID(), typeName);
        }

        return value.toString();
    }

    /** Discovers the context classpath without supplying a frame defining loader. */
    public @Nullable String discoverClasspath(@Nullable ThreadReference thread) {
        return discoverClasspath(thread, null);
    }

    /** Checks whether a compile needs to extend or retry the current connection's walk. */
    public boolean classpathNeedsDiscovery(@Nullable ClassLoaderReference definingLoader) {
        final RemoteClasspath current = classpathDiscovery;
        return current == null || current.discovery().needsWalk(definingLoader);
    }

    /** Reports incomplete reads and pending getters whose threads are still alive. */
    public @Nullable String getClasspathDiscoveryProblem() {
        final RemoteClasspath current = classpathDiscovery;
        return current == null ? null : current.discovery().problem();
    }

    /** Runs tool discovery outside the compiler monitor so a getter cannot block the dispatcher. */
    public @Nullable String discoverClasspathForTool(ThreadReference thread,
                                                      @Nullable ClassLoaderReference definingLoader) {
        return discoverClasspath(thread, definingLoader, InvocationRunner.isWorkerFor(thread));
    }

    /**
     * A published classpath and the discovery snapshot that produced it.
     *
     * @param vm the connection that owns the snapshot
     * @param path the joined entries when any are available
     * @param discovery the classpath entries, JDK information and loader state
     */
    public record RemoteClasspath(VirtualMachine vm, @Nullable String path, DiscoveryResult discovery) {}

    /** Reads the published classpath without taking the connection monitor. */
    public @Nullable RemoteClasspath remoteClasspathFor(@Nullable VirtualMachine expected) {
        final RemoteClasspath current = classpathDiscovery;
        return current != null && current.vm() == expected ? current : null;
    }

    /** Discovers the frame's loader using the inherited context field during dispatcher evaluation. */
    public @Nullable String discoverClasspath(@Nullable ThreadReference thread,
                                               @Nullable ClassLoaderReference definingLoader) {
        return discoverClasspath(thread, definingLoader, false);
    }

    /**
     * Extends the remote classpath for a frame's defining loader and retries incomplete reads.
     * Walks outside the connection monitor so the listener can drain events raised by an accessor.
     * Publishes only against the VM and previous result that the walk started from.
     */
    private @Nullable String discoverClasspath(@Nullable ThreadReference thread,
                                               @Nullable ClassLoaderReference definingLoader, boolean tool) {
        if (thread == null) {
            log.error("[JDI] discoverClasspath() requires a suspended compilation thread");
            return null;
        }

        final long startTime = System.currentTimeMillis();
        try {
            final VirtualMachine currentVm = thread.virtualMachine();
            final ClasspathDiscoverer discoverer = new ClasspathDiscoverer(currentVm, evaluationGuard);
            while (true) {
                final RemoteClasspath previous;
                synchronized (this) {
                    if (vm != currentVm) {
                        return null;
                    }
                    previous = classpathDiscovery;
                    if (previous != null && !previous.discovery().needsWalk(definingLoader)
                        && !(tool && (previous.discovery().hasPendingContext(thread) || discoverer.hasContextOutcome()))) {
                        return previous.path();
                    }
                }
                if (previous == null && getVM() != currentVm) {
                    return null;
                }

                final DiscoveryResult result = discoverer.discoverClasspath(thread, definingLoader,
                    previous == null ? null : previous.discovery(), tool);
                final Set<String> entries = result.applicationClasspath();
                final String resolved;
                if (entries.isEmpty()) {
                    resolved = null;
                } else {
                    final String first = entries.iterator().next();
                    final String separator = first.contains("\\") ? ";" : ":";
                    resolved = String.join(separator, entries);
                }
                synchronized (this) {
                    if (vm != currentVm) {
                        return null;
                    }
                    // Rebase the walk on the result published while discovery was in flight.
                    if (classpathDiscovery != previous) {
                        continue;
                    }
                    discoveredJdkPath = result.localJdkPath();
                    targetMajorVersion = result.targetMajorVersion();
                    classpathDiscovery = new RemoteClasspath(currentVm, resolved, result);
                }
                log.info("[JDI] {} classpath ({} entries) in {}ms",
                    result.problem() == null ? "Discovered" : "Using partial",
                    entries.size(), System.currentTimeMillis() - startTime);
                return resolved;
            }
        } catch (JdkNotFoundException e) {
            log.error("[JDI] {} (after {}ms)", e.getMessage(), System.currentTimeMillis() - startTime);
            return null;
        } catch (Exception e) {
            log.error("[JDI] Failed to discover classpath after {}ms", System.currentTimeMillis() - startTime, e);
            return null;
        }
    }

    /**
     * Local JDK path matching the target JVM version, populated as a side effect of
     * {@link #discoverClasspath}.
     *
     * @return absolute path to the local JDK, or {@code null} until {@link #discoverClasspath} runs
     */
    @Nullable
    public String getDiscoveredJdkPath() {
        return discoveredJdkPath;
    }

    /**
     * Returns the target JVM's major Java version (e.g., 8, 11, 17, 21).
     */
    public int getTargetMajorVersion() {
        return targetMajorVersion;
    }

    /**
     * Returns a previously cached ObjectReference, or null if not in cache.
     */
    @Nullable
    public ObjectReference getCachedObject(long objectId) {
        return objectCache.get(objectId);
    }

    /**
     * Clears the entire object reference cache. Called by {@code jdwp_reset} to wipe per-session
     * state without dropping the VM connection. Does NOT touch breakpoints, watchers, or event
     * history — those are owned by their respective services and reset separately.
     */
    public void clearObjectCache() {
        objectCache.clear();
    }

    /**
     * Convenience overload that lets the implementation pick the force-load thread. Prefer the
     * two-arg form when a known-good thread is already in hand (e.g. the current breakpoint
     * thread); it avoids a fallback scan of {@code allThreads()}.
     *
     * @see #findOrForceLoadClass(String, ThreadReference)
     * @throws ForceLoadFailure when target code throws during {@code Class.forName}
     */
    @Nullable
    public ReferenceType findOrForceLoadClass(String className) {
        return findOrForceLoadClass(className, null);
    }

    /**
     * Passive class lookup — checks whether {@code className} is already loaded in the target VM
     * via two side-effect-free probes ({@link VirtualMachine#classesByName} and a full
     * {@code allClasses()} scan) and returns the matching {@link ReferenceType} or {@code null}
     * if the class is not yet loaded.
     *
     * <p>Unlike {@link #findOrForceLoadClass}, this method <i>never</i> invokes
     * {@code Class.forName} in the target VM, so it cannot trigger {@code <clinit>}, cannot
     * cascade-load dependencies, and cannot park the calling thread inside a target-VM
     * {@code invokeMethod}. This is the default lookup used by every set-breakpoint code path —
     * a debugger should observe class loads, not cause them. Callers that genuinely need to make
     * a class appear (a breakpoint the user wants bound at once, or expression / watcher evaluation
     * that needs a specific helper class) must opt in via {@link #findOrForceLoadClass}. Exception
     * breakpoints on JDK classes do not: their class's load is reported like any other.
     */
    @Nullable
    public ReferenceType findLoadedClass(String className) {
        final VirtualMachine vmRef;
        synchronized (this) {
            if (vm == null) {
                return null;
            }
            vmRef = vm;
        }
        return findLoadedClass(vmRef, className);
    }

    @Nullable
    ReferenceType findLoadedClass(VirtualMachine vmRef, String className) {
        try {
            final List<ReferenceType> existing = vmRef.classesByName(className);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            // Fallback: full scan — sometimes catches bootstrap classes the indexed lookup misses.
            return vmRef.allClasses().stream()
                .filter(rt -> rt.name().equals(className))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            log.debug("[JDI] Passive lookup for '{}' failed: {}", className, e.getMessage());
            return null;
        }
    }

    /**
     * Locates a class in the target VM, force-loading it via {@code Class.forName(name)} if not yet
     * visible. Classes like {@code java.lang.IllegalStateException} are not visible to
     * {@link VirtualMachine#classesByName} until first referenced; a force-load makes them appear at
     * once. A breakpoint does not need that — their {@link com.sun.jdi.event.ClassPrepareEvent} is
     * delivered when they load, bootstrap classes included. Returns {@code null} if the class cannot
     * be found and the debugger could not perform the load, for example at VM start when no thread
     * can run {@code Class.forName}.
     *
     * <p><b>This method has observable side effects on the target application</b> — running
     * {@code <clinit>} triggers static-field init, cascade-loads dependencies, may register
     * listeners and open connections, and can mask the very lazy-load diagnostics users attach a
     * debugger to investigate. It is therefore <i>opt-in</i>: every set-breakpoint / set-logpoint
     * MCP tool defaults to the passive {@link #findLoadedClass} path; callers must explicitly pass
     * {@code forceLoad=true} (or call this method directly from a non-breakpoint code path such as
     * expression evaluation) to accept the trade-off.
     *
     * <p>{@code preferredThread}, if non-null, must be suspended at a JDI method-invocation event
     * (breakpoint, step, exception, class prepare) — JDI cannot invoke methods on threads
     * suspended via {@code vm.suspend()} (e.g., the VMStart-suspended state). If unusable or
     * {@code null}, the method falls back to {@link #findSuspendedThread}.
     *
     * <p><b>Concurrency:</b> the method is intentionally not {@code synchronized}. Phase 1 (cheap
     * lookups via {@link VirtualMachine#classesByName} and the {@code allClasses()} fallback, plus
     * pre-invoke preparation) runs under the {@code JDIConnectionService} monitor and captures a
     * local {@code vmRef}. Phase 2 ({@code ClassType.invokeMethod} into the target VM) runs
     * <i>outside</i> the monitor because it can only complete once our JDI event listener drains
     * the events produced by running {@code <clinit>}. Holding this monitor across the invoke would
     * re-introduce the deferred-class-load deadlock that {@link BreakpointTracker#tryPromotePending}
     * also avoids — any listener path that touches the {@code JDIConnectionService} monitor would
     * block on a worker parked here. Phase 3 (post-invoke {@code classesByName} lookup) reuses the
     * captured {@code vmRef} rather than re-reading {@code this.vm}: a concurrent {@code disconnect}
     * may have nulled the field, and a {@link com.sun.jdi.VMDisconnectedException} from the disposed
     * reference is already handled here, whereas a {@code null} re-read would silently discard a
     * successful force-load.
     *
     * @throws ForceLoadFailure when target code throws during {@code Class.forName}, carrying the
     *         formatted exception and the class if the post-invocation lookup still finds it
     */
    @Nullable
    public ReferenceType findOrForceLoadClass(String className, @Nullable ThreadReference preferredThread) {
        return findOrForceLoadClass(className, preferredThread, null);
    }

    /** With an expected VM, force-load only in the JVM selected by the calling tool. */
    @Nullable
    ReferenceType findOrForceLoadClass(String className, @Nullable ThreadReference preferredThread,
                                       @Nullable VirtualMachine expected) {
        // Phase 1: cheap lookups + force-load preflight, all under the monitor.
        final VirtualMachine vmRef;
        final ThreadReference thread;
        final ClassType classClass;
        final Method forName;
        synchronized (this) {
            if (vm == null || (expected != null && vm != expected)) {
                return null;
            }
            vmRef = vm;

            // Fast path: already visible via the indexed lookup
            final List<ReferenceType> existing = vmRef.classesByName(className);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }

            // Fallback 1: full scan of allClasses() — sometimes bootstrap classes appear here
            // even when classesByName misses them.
            final ReferenceType scanned = vmRef.allClasses().stream()
                .filter(rt -> rt.name().equals(className))
                .findFirst()
                .orElse(null);
            if (scanned != null) {
                log.info("[JDI] Found '{}' via allClasses() scan (not in classesByName index)", className);
                return scanned;
            }

            // Fallback 2: invoke Class.forName(name) in the target VM to force a load.
            // JDI requires the thread to be suspended at a method-invocation event AND have frames.
            thread = preferredThread != null && isUsableForInvoke(preferredThread)
                ? preferredThread : findSuspendedThread();
            if (thread == null) {
                log.debug("[JDI] Cannot force-load '{}' — no thread suspended at a method-invocation event", className);
                return null;
            }

            final List<ReferenceType> classClassList = vmRef.classesByName("java.lang.Class");
            if (classClassList.isEmpty()) {
                log.warn("[JDI] java.lang.Class not visible in target VM — cannot force-load");
                return null;
            }
            classClass = (ClassType) classClassList.get(0);
            forName = classClass.concreteMethodByName("forName", "(Ljava/lang/String;)Ljava/lang/Class;");
            if (forName == null) {
                log.warn("[JDI] Class.forName(String) method not found");
                return null;
            }
        }

        // Phase 2: invokeMethod OUTSIDE the monitor.
        try {
            log.info("[JDI] Attempting to force-load '{}' via thread '{}' (suspended={}, frames={})",
                className, thread.name(), thread.isSuspended(), tryFrameCount(thread));
            // Reentrancy guard: forcing Class.forName runs the target class's <clinit>, which
            // may hit a user breakpoint. Without the guard the listener would re-suspend the
            // thread we are driving and the outer invokeMethod would hang.
            try (EvaluationGuard.Lease lease = evaluationGuard.lease(thread);
                 PinnedMirrors pins = new PinnedMirrors()) {
                classClass.invokeMethod(thread, forName, List.of(pins.string(vmRef, className)),
                    ClassType.INVOKE_SINGLE_THREADED);
            }
            log.info("[JDI] Force-loaded class '{}' via Class.forName", className);
        } catch (InvocationException e) {
            final String description = RemoteCodeExecutor.describe(e);
            log.warn("[JDI] Could not force-load class '{}': {}", className, description);
            final ReferenceType visibleType = findAfterForceLoad(vmRef, className);
            throw new ForceLoadFailure(description, visibleType, e);
        } catch (Exception e) {
            log.warn("[JDI] Could not force-load class '{}': {} ({})",
                className, e.getMessage(), e.getClass().getSimpleName());
            return null;
        }

        return findAfterForceLoad(vmRef, className);
    }

    /** Phase 3 looks up the class on the captured VM, including when initialization failed. */
    private @Nullable ReferenceType findAfterForceLoad(VirtualMachine vmRef, String className) {
        try {
            final List<ReferenceType> afterForceLoad = vmRef.classesByName(className);
            return afterForceLoad.isEmpty() ? null : afterForceLoad.get(0);
        } catch (Exception e) {
            log.debug("[JDI] Phase 3 lookup for '{}' failed after force-load: {}", className, e.getMessage());
            return null;
        }
    }

    /** The target answered with an exception. A failed initializer can still leave a visible class. */
    static final class ForceLoadFailure extends RuntimeException {
        private final @Nullable ReferenceType visibleType;

        /** Carries target text for both immediate replies and late invocation events. */
        ForceLoadFailure(String description, @Nullable ReferenceType visibleType, InvocationException cause) {
            super("Class.forName threw " + description, cause);
            this.visibleType = visibleType;
        }

        /** Returns the class found after the invocation, if the VM still reports it. */
        @Nullable ReferenceType visibleType() {
            return visibleType;
        }
    }

    /**
     * Picks any thread suitable for JDI `invokeMethod` (force-load, watcher evaluation, classpath
     * discovery). Two-step fallback:
     * 1. The thread that most recently fired a suspending event (recorded by {@link BreakpointTracker});
     * this is the preferred choice because it's known to be at a JDI method-invocation event.
     * 2. Any other suspended thread with frames, excluding JVM-internal threads (Reference Handler,
     * Finalizer, etc.) which are suspended in native waits and would fail JDI's invoke check.
     */
    @Nullable
    private ThreadReference findSuspendedThread() {
        if (vm == null) {
            return null;
        }
        try {
            // First preference: the thread of the current stop — suspended at its own event, which
            // is what JDI requires for invokeMethod (issue #45).
            final SuspensionSession session = breakpointTracker.suspensionSession();
            final BreakpointTracker.LastBreakpoint current = session != null ? session.currentStop() : null;
            final ThreadReference lastBp = session != null
                ? (current != null ? current.thread() : null)
                : breakpointTracker.getLastBreakpointThread();
            if (lastBp != null && isUsableForInvoke(lastBp)) {
                return lastBp;
            }
            // Fallback: any suspended thread with frames. Skip JVM-internal threads (Reference
            // Handler, Finalizer, etc.) which are suspended in native waits, not at JDI events.
            return vm.allThreads().stream()
                .filter(t -> isUsableForInvoke(t) && !isJvmInternalThread(t))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Smart-collection-view dispatch tag — see {@link #collectionKind(String)}.
     */
    private enum CollectionKind {LIST, MAP, SET, UNKNOWN}

}
