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
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final LocalJdwpTarget UNKNOWN = new LocalJdwpTarget(List.of());

    private final List<Candidate> candidates;

    private LocalJdwpTarget(List<Candidate> candidates) {
        this.candidates = candidates;
    }

    /**
     * Starts identifying the process behind a JDWP connection that was just established, in the
     * background, so the attach itself never waits for it. Only one lookup runs at a time.
     *
     * @return the pending lookup, or {@code null} when it could not be started (another lookup is running)
     */
    @Nullable
    public static Future<LocalJdwpTarget> startLookup(JvmDiscoveryService discovery, String host, int port) {
        try {
            return LOOKUPS.submit(() -> identify(discovery, Path.of("/proc"), host, port));
        } catch (RuntimeException e) {
            log.debug("Could not start the JDWP process lookup", e);
            return null;
        }
    }

    /**
     * The lookup's result, waiting at most {@value #LOOKUP_BUDGET_MS} ms. A lookup that fails or
     * takes longer is cancelled and yields an unknown target, which describes itself as {@code ""}.
     */
    public static LocalJdwpTarget await(@Nullable Future<LocalJdwpTarget> lookup) {
        if (lookup == null || lookup.isCancelled()) {
            return UNKNOWN;
        }
        try {
            return lookup.get(LOOKUP_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while identifying the JDWP process", e);
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            log.debug("Could not identify the JDWP process", e);
        }
        lookup.cancel(true);
        return UNKNOWN;
    }

    /**
     * Identifies the JVM behind {@code host:port} when the host is local. Prefers the exact owner of
     * the peer end of this server's own TCP connection (from {@code /proc}); without it, falls back to
     * the discovered JVMs that advertise a JDWP server on that port, which can be several.
     *
     * @param proc the {@code /proc} root, replaceable in tests
     */
    static LocalJdwpTarget identify(JvmDiscoveryService discovery, Path proc, String host, int port) throws IOException {
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

        Set<Long> owners = Set.of();
        try {
            owners = socketOwners(proc, localAddresses, port);
        } catch (IOException | RuntimeException e) {
            log.debug("JDWP socket ownership unavailable", e);
        }
        // A name can resolve to both local and remote IPs. Require socket evidence for such names.
        if ((owners.isEmpty() && !localAddresses.containsAll(List.of(resolved)))
            || Thread.currentThread().isInterrupted()) {
            return UNKNOWN;
        }
        final Set<Long> socketOwners = owners;
        final List<Candidate> candidates = discovery.discover().stream()
            .filter(jvm -> !jvm.isThisProcess())
            .filter(jvm -> socketOwners.isEmpty()
                ? matchesPort(advertisedEndpoint(jvm), localAddresses, port) : socketOwners.contains(jvm.pid()))
            .sorted(Comparator.comparingLong(JvmDescriptor::pid))
            .map(Candidate::new)
            .toList();
        return new LocalJdwpTarget(candidates);
    }

    @Nullable
    private static JdwpEndpoint advertisedEndpoint(JvmDescriptor jvm) {
        final JdwpEndpoint endpoint = jvm.jdwp();
        if (endpoint != null) {
            return endpoint;
        }
        // The Attach API inventory supplies PIDs on platforms without /proc. If process argv
        // is readable there, use the same parser without starting an Attach API session.
        return ProcessHandle.of(jvm.pid()).flatMap(process -> process.info().arguments())
            .map(args -> JvmDiscoveryService.findJdwpArg(List.of(args))).orElse(null);
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
     * The identity line for a reply, with the uptime measured at {@code at} (e.g. the target's exit);
     * lists every candidate when the match is ambiguous, {@code ""} when unknown.
     */
    public String describeAt(Instant at) {
        if (candidates.isEmpty()) {
            return "";
        }
        if (candidates.size() > 1) {
            return "\nTarget process is ambiguous: " + candidates.stream().map(candidate -> candidate.describe(at)).collect(joining("; "));
        }
        return "\nTarget process: " + candidates.get(0).describe(at);
    }

    /**
     * The identity line for a disconnect reply, plus a warning when the single identified process is
     * still alive: a {@code server=y} JVM listens on its port again and keeps a new run from binding it.
     */
    public String describeAfterDisconnect(int port) {
        final String identity = describe();
        if (candidates.size() == 1 && candidates.get(0).isAlive()) {
            return identity + "\nThe process is still alive and keeps running. With server=y it listens again on port "
                + port + ", so a new run cannot bind that port until this process ends.";
        }
        return identity;
    }

    private record Candidate(JvmDescriptor jvm, @Nullable ProcessHandle process, @Nullable Instant started) {
        Candidate(JvmDescriptor jvm) {
            this(jvm, ProcessHandle.of(jvm.pid()).orElse(null));
        }

        private Candidate(JvmDescriptor jvm, @Nullable ProcessHandle process) {
            this(jvm, process, process != null ? process.info().startInstant().orElse(null) : null);
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
            final String command = jvm.mainClass() != null ? jvm.mainClass() : jvm.maskedCmdline();
            if (command != null && !command.isBlank()) {
                final String singleLine = JvmDiscoveryService.maskCredentials(command).replaceAll("\\R", " ");
                out.append(" — ").append(singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 160) + "…");
            }
            return out.toString();
        }
    }
}
