package one.edee.mcp.jdwp.discovery;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static java.util.stream.Collectors.joining;

/** Best-effort process identity captured while the JDWP socket is connected. */
public final class LocalJdwpTarget {
    private static final Logger log = LoggerFactory.getLogger(LocalJdwpTarget.class);
    private static final int LOOKUP_BUDGET_MS = 250;
    // DNS and filesystem calls can outlast a cancellation. Bound the workers as well as the wait.
    private static final ThreadPoolExecutor LOOKUPS = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> {
            final Thread thread = new Thread(task, "jdwp-target-lookup");
            thread.setDaemon(true);
            return thread;
        });
    /** No local endpoint was established, so a local port-owner hint would be misleading. */
    private static final LocalJdwpTarget UNKNOWN = new LocalJdwpTarget(List.of(), List.of(), false, false);
    /** A local endpoint was established without enough evidence to name a PID. */
    private static final LocalJdwpTarget LOCAL_UNIDENTIFIED = new LocalJdwpTarget(List.of(), List.of(), true, false);

    /** JVMs suggested by discovery, or the single candidate confirmed as the target. */
    private final List<Candidate> candidates;
    /** Established TCP peers that could not be verified as the debugged JVM. */
    private final List<Long> tcpPeers;
    /** Whether the endpoint was established as local even if its PID is unknown. */
    private final boolean local;
    /** Whether one socket owner is a JVM with matching JDWP server options. */
    private final boolean verifiedTarget;

    /** Captures the evidence available for an attachment. */
    private LocalJdwpTarget(List<Candidate> candidates, List<Long> tcpPeers, boolean local, boolean verifiedTarget) {
        this.candidates = candidates;
        this.tcpPeers = tcpPeers;
        this.local = local;
        this.verifiedTarget = verifiedTarget;
    }

    /** A bounded lookup that retains local endpoint and socket evidence if discovery times out. */
    public static final class Lookup {
        /** Completes when the background process inventory and socket scan both finish. */
        private final FutureTask<LocalJdwpTarget> task;
        /** Identity evidence collected before the complete result is available. */
        private volatile LocalJdwpTarget partial;

        /** Recognizes common loopback addresses even if the worker is busy or interrupted. */
        private Lookup(JvmDiscoveryService discovery, String host, int port) {
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host)) {
                partial = LOCAL_UNIDENTIFIED;
            } else {
                partial = UNKNOWN;
            }
            task = new FutureTask<>(() -> identify(discovery, Path.of("/proc"), host, port,
                result -> partial = result));
        }
    }

    /**
     * Starts identifying the peer of an established JDWP connection in the background.
     * Only one lookup runs at a time. If the worker is busy, the lookup retains any locality
     * already established by a common loopback address.
     */
    public static Lookup startLookup(JvmDiscoveryService discovery, String host, int port) {
        final Lookup lookup = new Lookup(discovery, host, port);
        try {
            LOOKUPS.execute(lookup.task);
        } catch (RuntimeException e) {
            lookup.task.cancel(false);
            log.debug("Could not start the JDWP process lookup", e);
        }
        return lookup;
    }

    /**
     * Waits at most {@value #LOOKUP_BUDGET_MS} ms and returns any evidence already available
     * if the full lookup fails or is cancelled.
     */
    public static LocalJdwpTarget await(@Nullable Lookup lookup) {
        if (lookup == null) {
            return UNKNOWN;
        }
        if (lookup.task.isCancelled()) {
            return lookup.partial;
        }
        try {
            return lookup.task.get(LOOKUP_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while identifying the JDWP process", e);
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            log.debug("Could not identify the JDWP process", e);
        }
        lookup.task.cancel(true);
        return lookup.partial;
    }

    /** Runs the lookup synchronously against a replaceable proc filesystem. */
    static LocalJdwpTarget identify(JvmDiscoveryService discovery, Path proc, String host, int port) throws IOException {
        return identify(discovery, proc, host, port, ignored -> { });
    }

    /** Resolves locality, then checks TCP ownership before considering JVM inventory matches. */
    private static LocalJdwpTarget identify(JvmDiscoveryService discovery, Path proc, String host, int port,
                                            Consumer<LocalJdwpTarget> partial) throws IOException {
        final Set<InetAddress> localAddresses = new HashSet<>();
        final InetAddress[] resolved = InetAddress.getAllByName(host);
        for (InetAddress address : resolved) {
            if (address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null) {
                localAddresses.add(address);
            }
        }
        if (localAddresses.isEmpty()) {
            return UNKNOWN;
        }

        final boolean localOnly = localAddresses.containsAll(List.of(resolved));
        if (localOnly) {
            partial.accept(LOCAL_UNIDENTIFIED);
        }
        Set<Long> owners = Set.of();
        try {
            owners = socketOwners(proc, localAddresses, port);
        } catch (IOException | RuntimeException e) {
            log.debug("JDWP socket ownership unavailable", e);
        }
        if (!owners.isEmpty()) {
            final LocalJdwpTarget peer = new LocalJdwpTarget(List.of(), owners.stream().sorted().toList(), true, false);
            partial.accept(peer);
            return identifyPeer(discovery, proc, localAddresses, port, peer);
        }
        // For names that resolve to both local and remote addresses, require socket evidence.
        if (!localOnly) {
            return UNKNOWN;
        }
        if (Thread.currentThread().isInterrupted()) {
            return LOCAL_UNIDENTIFIED;
        }
        final List<Candidate> candidates = discovery.discover().stream()
            .filter(jvm -> !jvm.isThisProcess() && matchesPort(advertisedEndpoint(jvm), localAddresses, port))
            .sorted(Comparator.comparingLong(JvmDescriptor::pid))
            .map(Candidate::new)
            .toList();
        return new LocalJdwpTarget(candidates, List.of(), true, false);
    }

    /** Promotes a unique peer to a verified target only with matching JVM and JDWP evidence. */
    private static LocalJdwpTarget identifyPeer(JvmDiscoveryService discovery, Path proc, Set<InetAddress> addresses,
                                                int port, LocalJdwpTarget peer) {
        if (peer.tcpPeers.size() != 1 || Thread.currentThread().isInterrupted()) {
            return peer;
        }
        final long pid = peer.tcpPeers.get(0);
        for (JvmDescriptor jvm : discovery.discover()) {
            if (jvm.pid() == pid && !jvm.isThisProcess() && matchesPort(advertisedEndpoint(jvm), addresses, port)
                && hasJvmEvidence(jvm, proc)) {
                return new LocalJdwpTarget(List.of(new Candidate(jvm)), List.of(), true, true);
            }
        }
        return peer;
    }

    /**
     * Proc-based inventory can recognize a wrapper from its argv alone. Require the actual Java
     * launcher executable or the independent Attach API inventory before trusting that match.
     */
    private static boolean hasJvmEvidence(JvmDescriptor jvm, Path proc) {
        if (jvm.source() != JvmDescriptor.Source.PROC_FS) {
            return true;
        }
        try {
            final Path executable = Files.readSymbolicLink(proc.resolve(Long.toString(jvm.pid())).resolve("exe"));
            return JvmDiscoveryService.looksLikeJava(executable.toString());
        } catch (IOException | RuntimeException e) {
            log.debug("Could not verify the executable for JDWP peer PID {}", jvm.pid(), e);
            return false;
        }
    }

    /** Returns the JDWP endpoint advertised in the JVM options when readable. */
    @Nullable
    private static JdwpEndpoint advertisedEndpoint(JvmDescriptor jvm) {
        final JdwpEndpoint endpoint = jvm.jdwp();
        if (endpoint != null || jvm.source() != JvmDescriptor.Source.ATTACH_API) {
            return endpoint;
        }
        // The Attach API inventory supplies PIDs on platforms without /proc. If process argv
        // is readable there, use the same parser without starting an Attach API session.
        return processLaunch(ProcessHandle.of(jvm.pid()).orElse(null)).jdwp();
    }

    private static JvmDiscoveryService.JavaLaunch processLaunch(@Nullable ProcessHandle process) {
        if (process != null) {
            final String[] args = process.info().arguments().orElse(null);
            if (args != null) {
                return JvmDiscoveryService.parseLaunchArguments(List.of(args));
            }
        }
        return new JvmDiscoveryService.JavaLaunch(null, null);
    }

    private static boolean matchesPort(@Nullable JdwpEndpoint endpoint, Set<InetAddress> addresses, int port) {
        if (endpoint == null || !endpoint.serverMode() || endpoint.port() != port
            || !"dt_socket".equals(endpoint.transport())) {
            return false;
        }
        if ("*".equals(endpoint.host())) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(endpoint.host())) {
                if (address.isAnyLocalAddress() || addresses.contains(address)) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.debug("Could not resolve a discovered JDWP bind address", e);
        }
        return false;
    }

    private static Set<Long> socketOwners(Path proc, Set<InetAddress> addresses, int port) throws IOException {
        final Set<String> ourSockets = new HashSet<>();
        readSocketLinks(proc.resolve("self/fd"), ourSockets);
        if (ourSockets.isEmpty()) {
            return Set.of();
        }
        final List<TcpSocket> sockets = new ArrayList<>();
        for (String table : List.of("tcp", "tcp6")) {
            final Path path = proc.resolve("net").resolve(table);
            if (Files.isReadable(path)) {
                readTcpSockets(path, port, sockets);
            }
        }

        // Use the full reversed four-tuple of a socket we own. A listening socket or another
        // client's connection on the same port cannot identify the peer of this attach.
        final Set<String> peerSockets = new HashSet<>();
        for (TcpSocket socket : sockets) {
            if (socket.remote().port() != port || !addresses.contains(socket.remote().address())
                || !ourSockets.contains(socket.inode())) {
                continue;
            }
            for (TcpSocket peer : sockets) {
                if (peer.local().equals(socket.remote()) && peer.remote().equals(socket.local())) {
                    peerSockets.add(peer.inode());
                }
            }
        }
        if (peerSockets.isEmpty()) {
            return Set.of();
        }
        final Set<Long> owners = new HashSet<>();
        try (var processes = Files.newDirectoryStream(proc, "[0-9]*")) {
            for (Path process : processes) {
                if (Thread.currentThread().isInterrupted()) {
                    return Set.of();
                }
                final Set<String> links = new HashSet<>();
                readSocketLinks(process.resolve("fd"), links);
                if (!Collections.disjoint(links, peerSockets)) {
                    owners.add(Long.parseLong(process.getFileName().toString()));
                }
            }
        }
        return owners;
    }

    private static void readSocketLinks(Path fdDir, Set<String> sockets) {
        try (var entries = Files.newDirectoryStream(fdDir)) {
            for (Path entry : entries) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    final String link = Files.readSymbolicLink(entry).toString();
                    if (link.startsWith("socket:[")) {
                        sockets.add(link);
                    }
                } catch (IOException ignored) {
                    // Descriptors can close during enumeration.
                }
            }
        } catch (IOException ignored) {
            // Expected for processes that have exited or belong to another user.
        }
    }

    private static void readTcpSockets(Path table, int port, List<TcpSocket> sockets) throws IOException {
        try (var rows = Files.newBufferedReader(table)) {
            rows.readLine(); // header
            String row;
            while (!Thread.currentThread().isInterrupted() && (row = rows.readLine()) != null) {
                final String[] fields = row.strip().split("\\s+", -1);
                if (fields.length < 10 || !"01".equals(fields[3]) || "0".equals(fields[9])) {
                    continue;
                }
                final int localPort = parsePort(fields[1]);
                final int remotePort = parsePort(fields[2]);
                if (localPort == port || remotePort == port) {
                    sockets.add(new TcpSocket(new TcpEndpoint(parseAddress(fields[1]), localPort),
                        new TcpEndpoint(parseAddress(fields[2]), remotePort), "socket:[" + fields[9] + ']'));
                }
            }
        }
    }

    private static int parsePort(String endpoint) {
        return Integer.parseInt(endpoint.substring(endpoint.indexOf(':') + 1), 16);
    }

    private static InetAddress parseAddress(String endpoint) throws IOException {
        final int length = endpoint.indexOf(':');
        final ByteBuffer address = ByteBuffer.allocate(length / 2).order(ByteOrder.nativeOrder());
        // Linux prints native-endian 32-bit words for both IPv4 and IPv6.
        for (int offset = 0; offset < length; offset += 8) {
            address.putInt(Integer.parseUnsignedInt(endpoint, offset, offset + 8, 16));
        }
        return InetAddress.getByAddress(address.array());
    }

    private record TcpEndpoint(InetAddress address, int port) {}
    private record TcpSocket(TcpEndpoint local, TcpEndpoint remote, String inode) {}

    /**
     * The identity line for a reply, with the uptime measured now; {@code ""} when unknown.
     */
    public String describe() {
        return describeAt(Instant.now());
    }

    /**
     * Describes the verified JVM, suggested JVM candidates, or TCP peer ownership at the given
     * time. Returns {@code ""} when no PID was identified.
     */
    public String describeAt(Instant at) {
        if (tcpPeers.size() == 1) {
            return "\nTCP peer: PID " + tcpPeers.get(0)
                + " owns the other end of the connection and is probably a port forwarder.";
        }
        if (!tcpPeers.isEmpty()) {
            return "\nTCP peer ownership is ambiguous (PIDs "
                + tcpPeers.stream().map(String::valueOf).collect(joining(", ")) + ").";
        }
        if (candidates.isEmpty()) {
            return "";
        }
        if (candidates.size() > 1) {
            return "\nTarget process is ambiguous (unverified candidates): "
                + candidates.stream().map(candidate -> candidate.describe(at)).collect(joining("; "));
        }
        final String label = verifiedTarget ? "\nTarget process (verified socket owner): " : "\nTarget process: probably ";
        return label + candidates.get(0).describe(at);
    }

    /**
     * A verified live target can reclaim the port. For other local results, including a known
     * TCP peer or a missing PID, explain how to check the listening socket.
     */
    public String describeAfterDisconnect(int port) {
        final String identity = describe();
        if (verifiedTarget && candidates.get(0).isAlive()) {
            return identity + "\nThe process is still alive and keeps running. With server=y it listens again on port "
                + port + ", so a new run cannot bind that port until this process ends. If this is your previous test JVM, use kill "
                + candidates.get(0).jvm().pid() + '.';
        }
        if (!verifiedTarget && local) {
            return identity + "\nVerify the port owner with ss -ltnp 'sport = :" + port + "' or lsof -nP -iTCP:"
                + port + " -sTCP:LISTEN.";
        }
        return identity;
    }

    private record Candidate(JvmDescriptor jvm, @Nullable ProcessHandle process, @Nullable Instant started,
                             @Nullable String selector) {
        Candidate(JvmDescriptor jvm) {
            this(jvm, ProcessHandle.of(jvm.pid()).orElse(null));
        }

        private Candidate(JvmDescriptor jvm, @Nullable ProcessHandle process) {
            this(jvm, process, process != null ? process.info().startInstant().orElse(null) : null,
                jvm.mainClass() == null && jvm.source() == JvmDescriptor.Source.ATTACH_API
                    ? processLaunch(process).selector() : jvm.mainClass());
        }

        boolean isAlive() {
            try {
                return process != null && process.isAlive();
            } catch (RuntimeException e) {
                log.debug("Could not check whether PID {} is still alive", jvm.pid(), e);
                return false;
            }
        }

        String describe(Instant at) {
            final StringBuilder out = new StringBuilder("PID ").append(jvm.pid());
            if (started != null) {
                out.append(", started ").append(DateTimeFormatter.ofPattern("HH:mm:ss z")
                    .format(started.atZone(ZoneId.systemDefault())))
                    .append(" (").append(DiagnoseReportRenderer.humanDuration(Duration.between(started, at)))
                    .append(" uptime)");
            }
            if (selector != null && !selector.isBlank()) {
                final String singleLine = JvmDiscoveryService.maskCredentials(selector).replaceAll("\\R", " ");
                out.append(" — ").append(singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160) + "…");
            }
            return out.toString();
        }
    }
}
