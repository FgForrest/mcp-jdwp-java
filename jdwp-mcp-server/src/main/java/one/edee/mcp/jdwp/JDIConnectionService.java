package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import one.edee.mcp.jdwp.evaluation.ClasspathDiscoverer;
import one.edee.mcp.jdwp.evaluation.ClasspathDiscoverer.DiscoveryResult;
import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import one.edee.mcp.jdwp.evaluation.JdkDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdkDiscoveryService.JdkNotFoundException;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * and {@link JdkDiscoveryService} on the first breakpoint hit and caches
 * the result in {@link #cachedClasspath} / {@link #discoveredJdkPath} until disconnect.
 * - Post-mortem cleanup driven by {@link JdiEventListener}'s VM-death hook (see
 * {@link #notifyVmDied()}), distinct from the user-initiated {@link #disconnect()} path.
 * <p>
 * Thread-safety: all public mutators are `synchronized` on the service instance; the object cache
 * is a {@link ConcurrentHashMap}; the classpath/JDK fields are `volatile` for cross-thread reads.
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
     * Server-wide registry of agent-labelled JDI object references. Held here so its lifecycle is
     * gated by the connection: pinned object references must be released both on VM death (see
     * {@link #notifyVmDied()}) and on explicit session teardown (see {@link #cleanupSessionState()})
     * — otherwise stale pins would survive a reconnect and leak across sessions.
     */
    private final MarkedInstanceRegistry markedInstances;
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
    /**
     * Cached path-separated classpath of the target JVM. Populated by {@link #discoverClasspath}
     * on the first successful call and reused thereafter; cleared on disconnect.
     */
    @Nullable
    private volatile String cachedClasspath;
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
                                EvaluationGuard evaluationGuard, MarkedInstanceRegistry markedInstances) {
        this.eventListener = eventListener;
        this.breakpointTracker = breakpointTracker;
        this.eventHistory = eventHistory;
        this.watcherManager = watcherManager;
        this.evaluationGuard = evaluationGuard;
        this.markedInstances = markedInstances;
        // Wire post-mortem cleanup so the listener can null the VM the moment the target dies.
        eventListener.setVmDeathHook(this::notifyVmDied);
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
    private static String tryUnboxPrimitive(ObjectReference obj) {
        final String typeName = obj.referenceType().name();
        if (!isBoxedPrimitiveType(typeName)) {
            return null;
        }
        final Field valueField = obj.referenceType().fieldByName("value");
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
     * Pure liveness probe — issues {@code vm.name()} and returns false on any exception. Does NOT
     * mutate the {@link #vm} field, so diagnostic callers like {@link #getConnectionStatus()} can
     * read connection state without an observable side effect. Stale-state cleanup is handled by
     * the connect/reconnect paths via {@link #cleanupSessionState()}.
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
        lastConnectAttempt = Instant.now();
        lastConnectAttemptHost = host;
        lastConnectAttemptPort = port;
        if (vm != null && isVMAlive()) {
            if (host.equals(lastHost) && port == lastPort) {
                lastConnectError = null;
                return "Already connected to " + vm.name();
            }
            // Deliberate host/port change — release current session so the fresh attach below
            // does not leak breakpoints / watchers / ObjectReferences across targets.
            log.info("[JDI] Switching target from {}:{} to {}:{} — releasing current session",
                lastHost, lastPort, host, port);
            cleanupSessionState();
        } else if (vm != null) {
            // Probe said dead but the field is still non-null — wipe stale session caches
            // before the fresh attach so they cannot leak across attachments.
            log.info("[JDI] Clearing stale state from previous (dead) VM connection");
            cleanupSessionState();
        }

        final VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        AttachingConnector connector = null;

        for (AttachingConnector ac : vmm.attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(ac.name())) {
                connector = ac;
                break;
            }
        }

        if (connector == null) {
            lastConnectError = "SocketAttach connector not found";
            throw new RuntimeException("SocketAttach connector not found");
        }

        final Map<String, Connector.Argument> args = connector.defaultArguments();
        Objects.requireNonNull(args.get("hostname"), "SocketAttach connector missing 'hostname' argument").setValue(host);
        Objects.requireNonNull(args.get("port"), "SocketAttach connector missing 'port' argument").setValue(String.valueOf(port));

        try {
            vm = connector.attach(args);
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

        eventListener.start(vm);

        return String.format("Connected to %s (version %s)", vm.name(), vm.version());
    }

    /**
     * Read-only snapshot of the connection target used by {@code jdwp_diagnose}. The
     * {@code connected} field runs a fresh liveness probe ({@code vm.name()}) on every call,
     * so callers must not cache the returned status — the underlying VM can die between calls.
     */
    public synchronized ConnectionStatus getConnectionStatus() {
        return new ConnectionStatus(
            vm != null && isVMAlive(),
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
     * Disconnects from the JDWP server. Also stops the event listener and resets the breakpoint tracker.
     *
     * @return status message ("Disconnected" or "Not connected")
     */
    public synchronized String disconnect() {
        if (vm == null) {
            return "Not connected";
        }
        cleanupSessionState();
        return "Disconnected";
    }

    /**
     * Post-mortem cleanup invoked by {@link JdiEventListener} on VMDeath / VMDisconnect.
     * Idempotent and best-effort: preserves {@link #eventHistory} (the listener's VM_DEATH entry
     * stays queryable) and {@link #lastHost} / {@link #lastPort} (so {@link #ensureConnected()}
     * can auto-reconnect on the next restart cycle). Marked-instance pins are released as a side
     * effect — the underlying JDI references are gone with the VM, so keeping them would only
     * leak dangling entries into the next session.
     */
    public synchronized void notifyVmDied() {
        if (vm == null) {
            return;
        }
        try {
            vm.dispose();
        } catch (Exception ignored) {
            // VM already gone — nothing to dispose.
        }
        vm = null;
        breakpointTracker.reset();
        watcherManager.clearAll();
        markedInstances.clearAll();
        objectCache.clear();
        cachedClasspath = null;
        discoveredJdkPath = null;
        targetMajorVersion = 0;
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
        cachedClasspath = null;
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

        lastHost = null;
        lastPort = 0;
    }

    /**
     * Returns the connected VirtualMachine, auto-reconnecting if the connection has dropped.
     *
     * @return the live {@link VirtualMachine} instance
     * @throws Exception if not connected and reconnection fails
     */
    public synchronized VirtualMachine getVM() throws Exception {
        ensureConnected();
        // Opportunistic pending-breakpoint retry — handles bootstrap classes whose ClassPrepareEvent
        // is never delivered. Best-effort; failures are swallowed.
        try {
            breakpointTracker.tryPromotePending(this, null);
        } catch (Exception e) {
            log.debug("[JDI] Pending promotion failed: {}", e.getMessage());
        }
        return Objects.requireNonNull(vm);
    }

    /**
     * Returns the raw VM reference without triggering opportunistic promotion. Used by
     * {@link BreakpointTracker#tryPromotePending(JDIConnectionService, ThreadReference)} to avoid
     * recursion. Callers must already hold the connection service monitor.
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
            return "Error: " + e.getMessage();
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
            final String unboxed = tryUnboxPrimitive(obj);
            if (unboxed != null) {
                return unboxed;
            }
            cacheObject(obj);
            return String.format("Object#%d (%s)", obj.uniqueID(), obj.referenceType().name());
        }

        return value.toString();
    }

    /**
     * Discovers and caches the full classpath of the target JVM, including JARs loaded dynamically
     * by Tomcat / container classloaders that don't appear in `java.class.path`. Result is cached
     * after the first successful call and reused until {@link #cleanupSessionState}.
     * <p>
     * Side effects: also populates {@link #discoveredJdkPath} and {@link #targetMajorVersion} via
     * {@link JdkDiscoveryService} so the JDT compiler can be configured.
     * <p>
     * Returns `null` (and logs at error level) on any failure, including when no matching local JDK
     * can be found — the {@link JdkDiscoveryService.JdkNotFoundException} is
     * caught here and never bubbles out.
     *
     * @param suspendedThread thread already suspended at a JDI method-invocation event (breakpoint
     *                        or step); plain `vm.suspend()` is not enough because the discovery uses
     *                        `INVOKE_SINGLE_THREADED` which requires a usable invocation thread
     * @return classpath string (separator inferred from the first entry), or `null` on any failure
     */
    @Nullable
    public String discoverClasspath(@Nullable ThreadReference suspendedThread) {
        if (cachedClasspath != null) {
            return cachedClasspath;
        }

        if (suspendedThread == null) {
            log.error("[JDI] discoverClasspath() requires a suspended thread from a breakpoint");
            return null;
        }

        try {
            // Route through synchronized getVM() to avoid a race with disconnect().
            final VirtualMachine currentVm = getVM();

            log.info("[JDI] Discovering full classpath using breakpoint thread '{}'", suspendedThread.name());

            final ClasspathDiscoverer discoverer = new ClasspathDiscoverer(currentVm);
            final DiscoveryResult result = discoverer.discoverFullClasspath(suspendedThread);

            discoveredJdkPath = result.localJdkPath();
            targetMajorVersion = result.targetMajorVersion();
            log.info("[JDI] Using local JDK: {} (Java {})", discoveredJdkPath, targetMajorVersion);

            final Set<String> classpathEntries = result.applicationClasspath();

            if (classpathEntries.isEmpty()) {
                log.warn("[JDI] No classpath entries discovered");
                return null;
            }

            // Separator inferred from the first entry — Windows uses ';', Unix ':'.
            final String separator = classpathEntries.stream()
                .findFirst()
                .map(path -> path.contains("\\") ? ";" : ":")
                .orElse(File.pathSeparator);

            cachedClasspath = String.join(separator, classpathEntries);

            log.info("[JDI] Full classpath discovered ({} entries)", classpathEntries.size());

            return cachedClasspath;

        } catch (JdkNotFoundException e) {
            log.error("[JDI] {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("[JDI] Failed to discover classpath", e);
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
     * Locates a class in the target VM, force-loading it via {@code Class.forName(name)} if not
     * yet visible. The force-load attempt requires a suspended thread (typically the main thread
     * paused at VMStart, or any thread suspended at a breakpoint). Returns null if the class
     * cannot be found or force-loaded.
     *
     * <p>This solves the bootstrap-class problem: classes like {@code java.lang.IllegalStateException}
     * are not visible to {@link VirtualMachine#classesByName(String)} until first referenced, and
     * their {@link com.sun.jdi.event.ClassPrepareEvent} is not delivered to JDI clients. Forcing
     * the load via {@code Class.forName} bypasses both issues.
     */
    @Nullable
    public synchronized ReferenceType findOrForceLoadClass(String className) {
        return findOrForceLoadClass(className, null);
    }

    /**
     * Same as {@link #findOrForceLoadClass(String)} but allows passing a preferred thread for the
     * force-load step. This must be a thread that is suspended at a JDI method-invocation event
     * (breakpoint, step, exception, class prepare) — JDI cannot invoke methods on threads
     * suspended via vm.suspend() (e.g., the VMStart-suspended state).
     */
    @Nullable
    public synchronized ReferenceType findOrForceLoadClass(String className, @Nullable ThreadReference preferredThread) {
        if (vm == null) {
            return null;
        }

        // Fast path: already visible via the indexed lookup
        final List<ReferenceType> existing = vm.classesByName(className);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        // Fallback 1: full scan of allClasses() — sometimes bootstrap classes appear here
        // even when classesByName misses them.
        final ReferenceType scanned = vm.allClasses().stream()
            .filter(rt -> rt.name().equals(className))
            .findFirst()
            .orElse(null);
        if (scanned != null) {
            log.info("[JDI] Found '{}' via allClasses() scan (not in classesByName index)", className);
            return scanned;
        }

        // Fallback 2: invoke Class.forName(name) in the target VM to force a load.
        // JDI requires the thread to be suspended at a method-invocation event AND have frames.
        final ThreadReference thread = preferredThread != null && isUsableForInvoke(preferredThread)
            ? preferredThread : findSuspendedThread();
        if (thread == null) {
            log.debug("[JDI] Cannot force-load '{}' — no thread suspended at a method-invocation event", className);
            return null;
        }

        try {
            log.info("[JDI] Attempting to force-load '{}' via thread '{}' (suspended={}, frames={})",
                className, thread.name(), thread.isSuspended(), tryFrameCount(thread));

            final List<ReferenceType> classClassList = vm.classesByName("java.lang.Class");
            if (classClassList.isEmpty()) {
                log.warn("[JDI] java.lang.Class not visible in target VM — cannot force-load");
                return null;
            }
            final ClassType classClass = (ClassType) classClassList.get(0);
            final Method forName = classClass.concreteMethodByName("forName", "(Ljava/lang/String;)Ljava/lang/Class;");
            if (forName == null) {
                log.warn("[JDI] Class.forName(String) method not found");
                return null;
            }

            final StringReference nameRef = vm.mirrorOf(className);
            // Reentrancy guard: forcing Class.forName runs the target class's <clinit>, which
            // may hit a user breakpoint. Without the guard the listener would re-suspend the
            // thread we are driving and the outer invokeMethod would hang. Capture the id up
            // front so a thread death during <clinit> does not leak a guard entry.
            final long guardedThreadId = thread.uniqueID();
            evaluationGuard.enter(guardedThreadId);
            try {
                classClass.invokeMethod(thread, forName, List.of(nameRef), ClassType.INVOKE_SINGLE_THREADED);
            } finally {
                evaluationGuard.exit(guardedThreadId);
            }
            log.info("[JDI] Force-loaded class '{}' via Class.forName", className);

            final List<ReferenceType> retry = vm.classesByName(className);
            return retry.isEmpty() ? null : retry.get(0);
        } catch (Exception e) {
            log.warn("[JDI] Could not force-load class '{}': {} ({})",
                className, e.getMessage(), e.getClass().getSimpleName());
            return null;
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
            // First preference: the thread that most recently hit a breakpoint — known to be
            // suspended at a method-invocation event, which is what JDI requires for invokeMethod.
            final ThreadReference lastBp = breakpointTracker.getLastBreakpointThread();
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
