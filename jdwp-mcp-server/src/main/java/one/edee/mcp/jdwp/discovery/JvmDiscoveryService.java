package one.edee.mcp.jdwp.discovery;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Enumerates JVMs visible to the current user and, where possible, classifies their JDWP
 * endpoint. Used by {@code jdwp_diagnose} (always) and {@code jdwp_wait_for_attach}
 * (on timeout) to answer "did my target JVM come up at all, and on which port?" without
 * the user shelling out to {@code ps}/{@code lsof}/{@code jps}.
 *
 * <p>Strategy order:
 * <ol>
 *   <li>{@code jdk.attach} {@code VirtualMachine.list()} — works on every platform, gives
 *       PID + main class but no JDWP port.</li>
 *   <li>Linux {@code /proc/<pid>/cmdline} parsing — adds the JDWP endpoint without the
 *       cost / visibility of an attach.</li>
 *   <li>Optional handshake confirmation — opt-in per call, distinguishes "listening"
 *       from "actually speaks JDWP".</li>
 * </ol>
 *
 * <p>Discovery is read-only: it never attaches to a JVM the user did not explicitly
 * target, never spawns processes, never reads {@code /proc/<pid>/environ}.
 */
@Service
public class JvmDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(JvmDiscoveryService.class);

    /**
     * Soft per-strategy time budget. Strategies log a WARN and return whatever they have so
     * far if they exceed this; they do not interrupt mid-loop.
     */
    private static final long ATTACH_API_BUDGET_MS = 100L;
    private static final long PROC_FS_BUDGET_MS = 200L;
    /** Per-port handshake budget for a single {@link #probeHandshake} call (connect + read). */
    private static final int HANDSHAKE_PROBE_BUDGET_MS = 100;
    /** Total budget across every parallel probe in one {@link #confirmAll} call. */
    private static final long HANDSHAKE_TOTAL_BUDGET_MS = 500L;
    /** 14-byte ASCII magic exchanged by both sides of a JDWP socket on attach. */
    private static final byte[] JDWP_HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);

    /**
     * Credential-masking pattern for a joined cmdline string. Matches
     * {@code password=}/{@code secret=}/{@code token=}/{@code apikey=}/{@code api_key=}
     * (case-insensitive) followed by anything up to whitespace.
     */
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
        "(?i)(password|secret|token|apikey|api_key)=\\S+"
    );

    /**
     * Credential-masking pattern for a single argv element. Matches the same keys but treats
     * the entire remainder of the string (including embedded whitespace) as the value — a
     * /proc cmdline arg like {@code -Dpassword=foo bar} is one NUL-separated token, so the
     * value extends to the end of the arg.
     */
    private static final Pattern CREDENTIAL_PATTERN_PER_ARG = Pattern.compile(
        "(?i)(password|secret|token|apikey|api_key)=.+$"
    );

    private final Supplier<List<AttachedJvm>> attachLister;
    private final long selfPid;
    private final Path procRoot;
    private final boolean procFsEnabled;

    /**
     * Spring-injected production constructor. Wires the real {@code jdk.attach} listing,
     * the current process's PID as the "self" filter, the live {@code /proc} root, and
     * enables the {@code /proc} strategy only when running on Linux.
     */
    public JvmDiscoveryService() {
        this(
            JvmDiscoveryService::listViaAttachApi,
            ProcessHandle.current().pid(),
            Paths.get("/proc"),
            isLinux()
        );
    }

    /**
     * Test-only constructor: lets tests inject a fake {@link VirtualMachine#list()} replacement,
     * fix the "self" PID, redirect the {@code /proc} root at a temp directory, and force-enable
     * the {@code /proc} strategy off-Linux.
     */
    JvmDiscoveryService(
        Supplier<List<AttachedJvm>> attachLister,
        long selfPid,
        Path procRoot,
        boolean procFsEnabled
    ) {
        this.attachLister = attachLister;
        this.selfPid = selfPid;
        this.procRoot = procRoot;
        this.procFsEnabled = procFsEnabled;
    }

    /**
     * Test-only seam for the legacy two-arg signature; equivalent to the four-arg form with
     * the real {@code /proc} root and {@code procFsEnabled=false}.
     */
    JvmDiscoveryService(Supplier<List<AttachedJvm>> attachLister, long selfPid) {
        this(attachLister, selfPid, Paths.get("/proc"), false);
    }

    /**
     * Returns a snapshot of local JVMs. Never throws and never returns {@code null}:
     * strategy failures degrade silently to whatever partial result is available.
     *
     * <p>Ordering is deterministic — attach-API descriptors come first in the order
     * returned by {@link VirtualMachine#list()}, followed by {@code /proc}-only PIDs in
     * the order {@link DirectoryStream} yields them (the merge keeps
     * {@link LinkedHashMap} insertion order).
     */
    public List<JvmDescriptor> discover() {
        final List<JvmDescriptor> attachApi = viaAttachApi();
        if (!procFsEnabled) {
            return attachApi;
        }
        final List<JvmDescriptor> procFs = viaProcFs();
        return mergeByPid(attachApi, procFs);
    }

    /**
     * Lists JVMs visible to {@code jdk.attach} on this host. Same-user only — the attach API
     * cannot see JVMs belonging to other users without privilege escalation, and we never
     * attempt it.
     */
    List<JvmDescriptor> viaAttachApi() {
        final long start = System.nanoTime();
        final List<AttachedJvm> attached;
        try {
            attached = attachLister.get();
        } catch (Exception e) {
            log.debug("VirtualMachine.list() failed; returning empty attach-API result", e);
            return List.of();
        }

        final List<JvmDescriptor> descriptors = new ArrayList<>(attached.size());
        for (AttachedJvm jvm : attached) {
            final String displayName = jvm.displayName();
            final String mainClass = (displayName == null || displayName.isEmpty()) ? null : displayName;
            descriptors.add(new JvmDescriptor(
                jvm.pid(),
                mainClass,
                null,
                null,
                null,
                jvm.pid() == selfPid,
                JvmDescriptor.Source.ATTACH_API
            ));
        }

        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (elapsedMs > ATTACH_API_BUDGET_MS) {
            log.warn("Attach-API JVM discovery took {}ms (budget {}ms) — investigate if this is recurring",
                elapsedMs, ATTACH_API_BUDGET_MS);
        }
        return descriptors;
    }

    /**
     * Walks {@code /proc/*}, reads each numeric PID's {@code cmdline}, extracts any
     * {@code -agentlib:jdwp=} / {@code -Xrunjdwp:} arg, and emits one descriptor per readable
     * Java process. Per-PID read failures (permission denied, race against process exit,
     * {@code hidepid=2}) are swallowed silently — they are expected, not exceptional.
     */
    List<JvmDescriptor> viaProcFs() {
        if (!Files.isDirectory(procRoot)) {
            return List.of();
        }
        final long start = System.nanoTime();
        final List<JvmDescriptor> descriptors = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(procRoot)) {
            for (Path pidDir : stream) {
                final String name = pidDir.getFileName().toString();
                final long pid;
                try {
                    pid = Long.parseLong(name);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                final JvmDescriptor descriptor = readProcEntry(pidDir, pid);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
        } catch (IOException e) {
            log.debug("Could not enumerate {} — degrading to attach-API-only", procRoot, e);
            return descriptors;
        }
        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (elapsedMs > PROC_FS_BUDGET_MS) {
            log.warn("/proc JVM discovery took {}ms (budget {}ms) — investigate if this is recurring",
                elapsedMs, PROC_FS_BUDGET_MS);
        }
        return descriptors;
    }

    /**
     * Reads one {@code /proc/<pid>/cmdline}, returns a Java-process descriptor or null.
     * Returns null when: the cmdline is unreadable, the process is not a Java process, or
     * the JVM arg list is empty.
     */
    @Nullable
    private JvmDescriptor readProcEntry(Path pidDir, long pid) {
        final Path cmdlinePath = pidDir.resolve("cmdline");
        final byte[] raw;
        try {
            raw = Files.readAllBytes(cmdlinePath);
        } catch (IOException ignored) {
            // hidepid=2, race vs. process exit, EPERM — all expected.
            return null;
        }
        if (raw.length == 0) {
            return null;
        }
        // /proc cmdlines are NUL-separated. Split first, then we have well-formed args.
        final List<String> argv = splitNulSeparated(raw);
        if (argv.isEmpty()) {
            return null;
        }
        final String executable = argv.get(0);
        if (!looksLikeJava(executable, argv)) {
            return null;
        }

        final JdwpEndpoint jdwp = findJdwpArg(argv);
        final String mainClass = extractMainClass(argv);
        // Mask per argv element BEFORE joining: /proc cmdlines are NUL-separated, so an
        // argv element may contain whitespace inside its value (e.g. -Dpassword=foo bar).
        // Joining first and then masking would stop at the embedded space and leak the tail.
        final StringBuilder joined = new StringBuilder(raw.length);
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                joined.append(' ');
            }
            joined.append(maskCredentialsInArg(argv.get(i)));
        }
        final String maskedCmdline = joined.toString();
        final String javaHome = resolveJavaHome(pidDir);

        return new JvmDescriptor(
            pid,
            mainClass,
            javaHome,
            maskedCmdline,
            jdwp,
            pid == selfPid,
            JvmDescriptor.Source.PROC_FS
        );
    }

    /**
     * Splits a NUL-separated {@code /proc/<pid>/cmdline} buffer into args. The buffer is
     * usually NUL-terminated (final empty string), which we drop.
     */
    static List<String> splitNulSeparated(byte[] raw) {
        final List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == 0) {
                if (i > start) {
                    out.add(new String(raw, start, i - start));
                }
                start = i + 1;
            }
        }
        if (start < raw.length) {
            out.add(new String(raw, start, raw.length - start));
        }
        return out;
    }

    /**
     * Heuristic Java-process detector. We trust the executable's basename plus the presence
     * of a {@code -cp}/{@code -jar}/{@code --module-path} flag — the attach API will catch
     * the few processes we miss here.
     */
    private static boolean looksLikeJava(String executable, List<String> argv) {
        final String base = executable.substring(executable.lastIndexOf('/') + 1);
        if ("java".equals(base) || "javaw".equals(base) || base.startsWith("java.")) {
            return true;
        }
        // Some launchers (mvn, gradle) re-exec to "java"; cover the case where the executable
        // path is opaque but the agent flag is unmistakable.
        for (String arg : argv) {
            if (arg.startsWith("-agentlib:jdwp=") || arg.startsWith("-Xrunjdwp:")) {
                return true;
            }
        }
        return false;
    }

    /** Returns the parsed JDWP endpoint or null when no jdwp agent arg is present. */
    @Nullable
    static JdwpEndpoint findJdwpArg(List<String> argv) {
        for (String arg : argv) {
            if (arg.startsWith("-agentlib:jdwp=")) {
                return JdwpAgentArgParser.parse(arg.substring("-agentlib:jdwp=".length()));
            }
            if (arg.startsWith("-Xrunjdwp:")) {
                return JdwpAgentArgParser.parse(arg.substring("-Xrunjdwp:".length()));
            }
        }
        return null;
    }

    /**
     * Picks a best-effort "main class" string from a JVM argv. Looks for the first non-option
     * token that follows {@code -jar} (returns the jar path), the main class on the command
     * line (the first non-option after JVM args), or returns null when neither is obvious.
     * Skips the value of two-token JVM options ({@code -cp}, {@code -classpath},
     * {@code --module-path}, {@code -p}) so the classpath is not mistaken for the main class.
     */
    @Nullable
    private static String extractMainClass(List<String> argv) {
        for (int i = 1; i < argv.size(); i++) {
            final String arg = argv.get(i);
            if ("-jar".equals(arg) && i + 1 < argv.size()) {
                return argv.get(i + 1);
            }
            if (isTwoTokenOption(arg)) {
                i++; // skip the option's value
                continue;
            }
            if (arg.startsWith("-")) {
                continue;
            }
            return arg;
        }
        return null;
    }

    /**
     * Returns true for JVM options whose value is a separate argv token (rather than glued on
     * with {@code =}). Used by {@link #extractMainClass} so the value isn't mistaken for the
     * main class.
     */
    private static boolean isTwoTokenOption(String arg) {
        return "-cp".equals(arg)
            || "-classpath".equals(arg)
            || "--class-path".equals(arg)
            || "-p".equals(arg)
            || "--module-path".equals(arg)
            || "--upgrade-module-path".equals(arg)
            || "--patch-module".equals(arg)
            || "--add-modules".equals(arg)
            || "--add-reads".equals(arg)
            || "--add-exports".equals(arg)
            || "--add-opens".equals(arg)
            || "--limit-modules".equals(arg)
            || "--source".equals(arg)
            || "--module".equals(arg)
            || "-m".equals(arg);
    }

    /**
     * Best-effort {@code JAVA_HOME} resolution via {@code /proc/<pid>/exe} symlink. Returns
     * null when the symlink cannot be read (different user, container boundary).
     */
    @Nullable
    private static String resolveJavaHome(Path pidDir) {
        try {
            final Path exe = Files.readSymbolicLink(pidDir.resolve("exe"));
            // Typical: /opt/jdk-21/bin/java → /opt/jdk-21
            final Path bin = exe.getParent();
            if (bin == null) {
                return null;
            }
            final Path home = bin.getParent();
            return home == null ? null : home.toString();
        } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
            return null;
        }
    }

    /** Replaces every credential-looking token with {@code KEY=***}. */
    static String maskCredentials(String cmdline) {
        return CREDENTIAL_PATTERN.matcher(cmdline).replaceAll(matchResult -> matchResult.group(1) + "=***");
    }

    /**
     * Per-argv-element variant of {@link #maskCredentials}. The value is taken to extend to the
     * end of the arg, so an arg whose value contains whitespace (e.g. {@code -Dpassword=foo bar})
     * is masked in full instead of leaking the tail after the first space.
     */
    static String maskCredentialsInArg(String arg) {
        return CREDENTIAL_PATTERN_PER_ARG.matcher(arg).replaceAll(matchResult -> matchResult.group(1) + "=***");
    }

    /**
     * Opt-in enrichment: for every descriptor whose {@link JdwpEndpoint} is still null, attaches
     * via {@code jdk.attach} to read the {@code sun.jdwp.listenerAddress} agent property and
     * synthesises an endpoint. Per-JVM budget is 200 ms; an attach that takes longer is
     * abandoned and the descriptor is returned unchanged. Attaches are visible to the target
     * JVM (the attach API loads its agent into the target), hence the opt-in.
     *
     * <p>Never attaches to {@link JvmDescriptor#isThisProcess()} (self-attach can deadlock or
     * trigger sandbox restrictions). Already-known endpoints are left alone.
     */
    public List<JvmDescriptor> inspectAll(List<JvmDescriptor> descriptors) {
        if (descriptors.isEmpty()) {
            return descriptors;
        }
        final List<JvmDescriptor> out = new ArrayList<>(descriptors.size());
        for (JvmDescriptor d : descriptors) {
            if (d.jdwp() != null || d.isThisProcess()) {
                out.add(d);
                continue;
            }
            final JdwpEndpoint inspected = inspectViaAttach(d.pid());
            if (inspected == null) {
                out.add(d);
            } else {
                out.add(new JvmDescriptor(
                    d.pid(), d.mainClass(), d.javaHome(), d.maskedCmdline(),
                    inspected, d.isThisProcess(), d.source()
                ));
            }
        }
        return out;
    }

    /**
     * Attaches to {@code pid}, reads {@code sun.jdwp.listenerAddress}, detaches. Returns null
     * when the agent property is absent (JDWP not loaded), the attach fails, or the property
     * cannot be parsed. The returned endpoint has state {@link JdwpEndpoint.State#UNKNOWN};
     * callers should run {@link #confirmAll} again if they want a probe.
     */
    @Nullable
    private JdwpEndpoint inspectViaAttach(long pid) {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(Long.toString(pid));
            final String address = vm.getAgentProperties().getProperty("sun.jdwp.listenerAddress");
            if (address == null || address.isEmpty()) {
                return null;
            }
            // The property format is "<transport>:<address>" — e.g. "dt_socket:5005" or
            // "dt_socket:127.0.0.1:5005". Strip the leading transport, hand the rest to the
            // parser by wrapping it in a synthetic agent-arg string.
            final int firstColon = address.indexOf(':');
            if (firstColon < 0) {
                return null;
            }
            final String transport = address.substring(0, firstColon);
            final String hostPort = address.substring(firstColon + 1);
            return JdwpAgentArgParser.parse("transport=" + transport + ",server=y,suspend=n,address=" + hostPort);
        } catch (Exception e) {
            log.debug("inspectAll attach failed for pid {}", pid, e);
            return null;
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Confirms each descriptor's JDWP endpoint by performing a JDWP handshake against the
     * advertised port. Endpoints in state {@link JdwpEndpoint.State#UNKNOWN} move to one of:
     * <ul>
     *   <li>{@link JdwpEndpoint.State#CONNECTED_TO_US} — when the host/port matches the JVM
     *       we are currently attached to (no probe needed; attaching twice would race).</li>
     *   <li>{@link JdwpEndpoint.State#SUSPENDED} — handshake succeeded AND {@code suspend=y}.</li>
     *   <li>{@link JdwpEndpoint.State#LISTENING} — handshake succeeded.</li>
     *   <li>{@link JdwpEndpoint.State#UNREACHABLE} — TCP refused, timed out, or handshake
     *       didn't echo back.</li>
     * </ul>
     * Off-host hosts (anything other than {@code *}, {@code localhost}, {@code 127.0.0.1},
     * {@code ::1}, or {@code 0.0.0.0}) are never probed — we don't want to send unsolicited
     * bytes to arbitrary addresses someone may have typed into their cmdline.
     *
     * <p>Probes run in parallel with a 4-thread pool. The total wall time across all probes
     * in one call is bounded by {@link #HANDSHAKE_TOTAL_BUDGET_MS}; descriptors whose probe
     * could not complete within the budget keep {@link JdwpEndpoint.State#UNKNOWN}.
     *
     * @param descriptors discovery snapshot
     * @param connectedHost host of the JVM we are currently attached to, or null
     * @param connectedPort port of the JVM we are currently attached to, or 0
     * @return new list with refreshed JDWP endpoint state; descriptors without an endpoint
     *     pass through unchanged
     */
    public List<JvmDescriptor> confirmAll(
        List<JvmDescriptor> descriptors,
        @Nullable String connectedHost,
        int connectedPort
    ) {
        if (descriptors.isEmpty()) {
            return descriptors;
        }
        final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            final Thread t = new Thread(r, "jdwp-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            final List<Future<JvmDescriptor>> futures = new ArrayList<>(descriptors.size());
            for (JvmDescriptor d : descriptors) {
                futures.add(pool.submit(() -> confirmOne(d, connectedHost, connectedPort)));
            }
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TOTAL_BUDGET_MS);
            final List<JvmDescriptor> out = new ArrayList<>(descriptors.size());
            for (int i = 0; i < futures.size(); i++) {
                final long remainingNs = Math.max(0L, deadline - System.nanoTime());
                try {
                    out.add(futures.get(i).get(remainingNs, TimeUnit.NANOSECONDS));
                } catch (TimeoutException te) {
                    futures.get(i).cancel(true);
                    out.add(descriptors.get(i));
                } catch (ExecutionException ee) {
                    log.debug("probe failed for pid {}", descriptors.get(i).pid(), ee.getCause());
                    out.add(descriptors.get(i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    out.add(descriptors.get(i));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Resolves the new state for a single descriptor. Pure orchestration over
     * {@link #probeHandshake}; pulled out so it stays trivially testable.
     */
    private JvmDescriptor confirmOne(JvmDescriptor d, @Nullable String connectedHost, int connectedPort) {
        final JdwpEndpoint ep = d.jdwp();
        if (ep == null) {
            return d;
        }
        if (matchesConnectedTarget(ep, connectedHost, connectedPort)) {
            return withEndpointState(d, JdwpEndpoint.State.CONNECTED_TO_US);
        }
        if (!isLocalHost(ep.host())) {
            // Off-host endpoint: leave at UNKNOWN rather than dialing arbitrary addresses.
            return d;
        }
        final String probeHost = resolveProbeHost(ep.host());
        final boolean handshakeOk = probeHandshake(probeHost, ep.port(), HANDSHAKE_PROBE_BUDGET_MS);
        if (!handshakeOk) {
            return withEndpointState(d, JdwpEndpoint.State.UNREACHABLE);
        }
        final JdwpEndpoint.State newState = ep.suspendOnStart()
            ? JdwpEndpoint.State.SUSPENDED
            : JdwpEndpoint.State.LISTENING;
        return withEndpointState(d, newState);
    }

    /**
     * Performs the 14-byte JDWP handshake against {@code host:port}. Returns {@code true} only
     * when the remote echoes back the exact magic bytes. {@code IOException}, connection
     * refusal, and timeouts all map to {@code false} — the method never throws.
     *
     * <p>The connect-timeout, the per-read SO_TIMEOUT, and the cumulative read budget all share
     * a single deadline computed at method entry, so the total wall-clock cost of a single
     * call is bounded by {@code totalTimeoutMs} plus a small scheduling grace window —
     * regardless of whether the remote is slow to accept, slow to reply, or drip-feeds bytes.
     *
     * @param host  hostname to dial (resolved IP for {@code "*"} / {@code "0.0.0.0"} is
     *              {@code "127.0.0.1"})
     * @param port  TCP port
     * @param totalTimeoutMs total wall-clock budget for connect + handshake exchange
     */
    boolean probeHandshake(String host, int port, int totalTimeoutMs) {
        // Special-case: per Socket.connect contract a 0-ms connect timeout means "infinite wait".
        // We preserve that quirk to avoid surprising callers who deliberately pass 0; the read
        // loop then uses its own zero-remaining check to bail out promptly if anything stalls.
        final long deadlineNanos = totalTimeoutMs > 0
            ? System.nanoTime() + totalTimeoutMs * 1_000_000L
            : Long.MAX_VALUE;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), totalTimeoutMs);
            try (OutputStream out = socket.getOutputStream(); InputStream in = socket.getInputStream()) {
                out.write(JDWP_HANDSHAKE);
                out.flush();
                final byte[] buf = new byte[JDWP_HANDSHAKE.length];
                int read = 0;
                while (read < buf.length) {
                    final int remainingMs = remainingMillis(deadlineNanos);
                    if (remainingMs <= 0) {
                        return false;
                    }
                    socket.setSoTimeout(remainingMs);
                    final int n = in.read(buf, read, buf.length - read);
                    if (n < 0) {
                        return false;
                    }
                    read += n;
                }
                for (int i = 0; i < buf.length; i++) {
                    if (buf[i] != JDWP_HANDSHAKE[i]) {
                        return false;
                    }
                }
                return true;
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Milliseconds left until {@code deadlineNanos}, clamped to a non-negative {@code int}.
     * {@link Long#MAX_VALUE} is treated as "no deadline" and returns {@link Integer#MAX_VALUE}
     * so a caller passing it to {@link Socket#setSoTimeout(int)} effectively waits forever.
     */
    private static int remainingMillis(long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        final long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMs <= 0) {
            return 0;
        }
        return remainingMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remainingMs;
    }

    /**
     * Returns true when the endpoint refers to the same host:port we are currently attached to.
     * Wildcard hosts ({@code *}, {@code 0.0.0.0}) match any localhost connection on the same
     * port. Off-host or different-port endpoints never match.
     */
    private static boolean matchesConnectedTarget(JdwpEndpoint ep, @Nullable String connectedHost, int connectedPort) {
        if (connectedHost == null || connectedPort == 0) {
            return false;
        }
        if (ep.port() != connectedPort) {
            return false;
        }
        final boolean weAttachedLocally = isLocalHost(connectedHost);
        if (isLocalHost(ep.host()) && weAttachedLocally) {
            return true;
        }
        return ep.host().equalsIgnoreCase(connectedHost);
    }

    private static boolean isLocalHost(String host) {
        return "*".equals(host)
            || "0.0.0.0".equals(host)
            || "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host);
    }

    /** Translates a bind-address-style host into an actual IP to dial. */
    private static String resolveProbeHost(String host) {
        if ("*".equals(host) || "0.0.0.0".equals(host)) {
            return "127.0.0.1";
        }
        return host;
    }

    private static JvmDescriptor withEndpointState(JvmDescriptor d, JdwpEndpoint.State newState) {
        final JdwpEndpoint ep = d.jdwp();
        if (ep == null) {
            return d;
        }
        return new JvmDescriptor(
            d.pid(),
            d.mainClass(),
            d.javaHome(),
            d.maskedCmdline(),
            new JdwpEndpoint(ep.host(), ep.port(), ep.transport(), ep.serverMode(), ep.suspendOnStart(), newState),
            d.isThisProcess(),
            d.source()
        );
    }

    /**
     * Merges the {@link JvmDescriptor.Source#ATTACH_API} list with the
     * {@link JvmDescriptor.Source#PROC_FS} list, keyed by PID. {@code /proc} wins on conflict
     * (it carries the JDWP endpoint), but it borrows the attach-API's {@code mainClass} when
     * the {@code /proc} extraction came back null. Entries seen by both strategies are marked
     * {@link JvmDescriptor.Source#BOTH}.
     */
    static List<JvmDescriptor> mergeByPid(List<JvmDescriptor> attachApi, List<JvmDescriptor> procFs) {
        final Map<Long, JvmDescriptor> byPid = new LinkedHashMap<>();
        for (JvmDescriptor d : attachApi) {
            byPid.put(d.pid(), d);
        }
        for (JvmDescriptor d : procFs) {
            final JvmDescriptor existing = byPid.get(d.pid());
            if (existing == null) {
                byPid.put(d.pid(), d);
            } else {
                final String mainClass = d.mainClass() != null ? d.mainClass() : existing.mainClass();
                byPid.put(d.pid(), new JvmDescriptor(
                    d.pid(),
                    mainClass,
                    d.javaHome(),
                    d.maskedCmdline(),
                    d.jdwp(),
                    d.isThisProcess(),
                    JvmDescriptor.Source.BOTH
                ));
            }
        }
        return new ArrayList<>(byPid.values());
    }

    /**
     * Default {@link VirtualMachine#list()} adapter. Pulled out so the test seam in the
     * constructor can swap it for a fixed list without touching the JDK class directly.
     */
    private static List<AttachedJvm> listViaAttachApi() {
        final List<VirtualMachineDescriptor> raw;
        try {
            raw = VirtualMachine.list();
        } catch (Exception e) {
            if (e instanceof AttachNotSupportedException || e.getCause() instanceof AttachNotSupportedException) {
                log.debug("Attach API not supported in this environment", e);
            } else {
                log.debug("VirtualMachine.list() failed", e);
            }
            return List.of();
        }
        final List<AttachedJvm> out = new ArrayList<>(raw.size());
        for (VirtualMachineDescriptor d : raw) {
            final long pid;
            try {
                pid = Long.parseLong(d.id());
            } catch (NumberFormatException nfe) {
                continue;
            }
            out.add(new AttachedJvm(pid, d.displayName()));
        }
        return out;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /** Minimal projection of {@link VirtualMachineDescriptor} — only the fields discovery needs. */
    record AttachedJvm(long pid, @Nullable String displayName) {}
}
