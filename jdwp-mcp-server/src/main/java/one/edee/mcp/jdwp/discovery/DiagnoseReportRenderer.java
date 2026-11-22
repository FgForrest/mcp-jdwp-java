package one.edee.mcp.jdwp.discovery;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Pure formatting helpers for the three-block {@code jdwp_diagnose} report
 * (MCP-server status / JDWP connection / Local JVMs). Kept separate from {@code JDWPTools}
 * because that class is already large; everything here is stateless and easy to unit-test.
 */
public final class DiagnoseReportRenderer {

    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAIN_CLASS_COL_WIDTH = 38;

    private DiagnoseReportRenderer() {}

    /**
     * Top-of-report banner. Bracketed by box-drawing chars on the dividers (matches the
     * existing register used elsewhere in {@code JDWPTools}).
     */
    public static String renderHeader() {
        return """
            ═══════════════════════════════════════════════════════════════
             MCP JDWP Inspector — Diagnostic Report
            ═══════════════════════════════════════════════════════════════

            """;
    }

    /**
     * Renders the "MCP server" block. The section assumes the caller already knows static
     * facts about its own process; we just format them.
     */
    public static String renderMcpServerBlock(
        long pid,
        Duration uptime,
        String javaVersion,
        int toolCount,
        String configuredHost,
        int configuredPort,
        String workingDir
    ) {
        final StringBuilder out = new StringBuilder();
        out.append("▸ MCP server\n");
        out.append(String.format("  Status:        Running (PID %d, uptime %s)%n", pid, humanDuration(uptime)));
        out.append(String.format("  Java:          %s%n", javaVersion));
        out.append(String.format("  Tools:         %d registered%n", toolCount));
        out.append(String.format("  Configured:    target host=%s port=%d%n", configuredHost, configuredPort));
        out.append(String.format("  Working dir:   %s%n", workingDir));
        return out.toString();
    }

    /**
     * Renders the "JDWP connection" header block. When connected, returns a one-line summary
     * the caller should follow with the existing breakpoint diagnostic. When disconnected,
     * returns the last-attempt + suggestion block.
     *
     * @param defaultPort the configured target port, used only in the "launch with
     *                    {@code -agentlib:jdwp=...,address=*:<port>}" hint — not a live port
     */
    public static String renderConnectionBlock(JDIConnectionStatusView status, int defaultPort) {
        final StringBuilder out = new StringBuilder();
        out.append("\n▸ JDWP connection\n");
        if (status.connected()) {
            final String host = status.lastHost() == null ? "<unknown>" : status.lastHost();
            out.append(String.format("  ✓ Connected to %s:%d%n", host, status.lastPort()));
        } else {
            out.append("  ⚠ Not connected\n");
            if (status.lastConnectAttempt() != null) {
                final String when = HUMAN_TS.format(LocalDateTime.ofInstant(status.lastConnectAttempt(), ZoneId.systemDefault()));
                final String why = status.lastConnectError() == null ? "succeeded" : '"' + status.lastConnectError() + '"';
                out.append(String.format("  Last attempt:  %s → %s%n", when, why));
            } else {
                out.append("  Last attempt:  never\n");
            }
            out.append(String.format("  Suggestion:    Launch target with -agentlib:jdwp=...,address=*:%d%n", defaultPort));
            out.append("                 then jdwp_connect (or jdwp_wait_for_attach to poll).\n");
        }
        return out.toString();
    }

    /**
     * Renders the "VM capabilities" block — only meaningful when connected. Surfaces the field
     * watchpoint capabilities the live VM advertises plus a perf warning since field watchpoints
     * fire on every read/write of the watched field and can dominate target-VM CPU for hot fields.
     * <p>
     * Returns the empty string when both capabilities are {@code false} AND we are disconnected —
     * there is nothing actionable to print and an empty section would confuse the report layout.
     * If even one capability is {@code true} we render the block so the caller knows the channel
     * is available.
     */
    public static String renderVmCapabilitiesBlock(boolean canWatchFieldAccess,
                                                   boolean canWatchFieldModification) {
        if (!canWatchFieldAccess && !canWatchFieldModification) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        out.append("\n▸ VM capabilities (field watchpoints)\n");
        out.append(String.format("  canWatchFieldAccess:       %s%n",
            canWatchFieldAccess ? "yes" : "no"));
        out.append(String.format("  canWatchFieldModification: %s%n",
            canWatchFieldModification ? "yes" : "no"));
        out.append("  ⚠ Field watchpoints fire on EVERY access/write of the field — for hot fields\n");
        out.append("    they can dominate target-VM CPU. Prefer narrow filters (threadFilterId,\n");
        out.append("    objectFilterId, condition) or restrict to short-lived debugging sessions.\n");
        return out.toString();
    }

    /**
     * Renders the "Local JVMs" block. {@code descriptors} can be empty; we still emit a header
     * line so the user sees that discovery ran.
     */
    public static String renderJvmListBlock(List<JvmDescriptor> descriptors, String currentUser) {
        // P1-5: filter out the MCP server's own PID before rendering. Listing ourselves as a
        // (THIS PROCESS) row is pure noise — the agent can never attach the JDWP server to its
        // own JVM, so the row offers no actionable information.
        final List<JvmDescriptor> filtered = descriptors.stream()
            .filter(d -> !d.isThisProcess())
            .toList();
        final StringBuilder out = new StringBuilder();
        out.append(String.format("%n▸ Local JVMs visible to user '%s' (%d found)%n", currentUser, filtered.size()));
        if (filtered.isEmpty()) {
            out.append("  (no JVMs discovered — discovery may have been blocked by sandbox restrictions)\n");
            return out.toString();
        }
        out.append("  PID    Main class / JAR                          JDWP            State\n");
        out.append("  ─────  ────────────────────────────────────────  ──────────────  ──────────\n");
        for (JvmDescriptor d : filtered) {
            out.append("  ");
            out.append(padLeft(String.valueOf(d.pid()), 5));
            out.append("  ");
            out.append(padRight(formatMainClass(d), MAIN_CLASS_COL_WIDTH + 2));
            out.append(padRight(formatJdwp(d.jdwp()), 16));
            out.append(formatState(d.jdwp()));
            out.append('\n');
        }
        out.append("\n  Legend: (s)=suspend=y. LISTENING confirms a JDWP handshake; UNKNOWN means the\n");
        out.append("          endpoint was reported but not probed (off-host or off-budget).\n");
        out.append(renderAttachHints(filtered));
        return out.toString();
    }

    /**
     * Best-effort "💡 to attach: ..." tip. Picks the first SUSPENDED endpoint (most urgent —
     * the JVM is waiting for us), then falls back to LISTENING.
     */
    private static String renderAttachHints(List<JvmDescriptor> descriptors) {
        JvmDescriptor suspended = null;
        JvmDescriptor listening = null;
        for (JvmDescriptor d : descriptors) {
            final JdwpEndpoint ep = d.jdwp();
            if (ep == null || d.isThisProcess()) {
                continue;
            }
            if (ep.state() == JdwpEndpoint.State.SUSPENDED && suspended == null) {
                suspended = d;
            }
            if (ep.state() == JdwpEndpoint.State.LISTENING && listening == null) {
                listening = d;
            }
        }
        final JvmDescriptor target = suspended != null ? suspended : listening;
        if (target == null || target.jdwp() == null) {
            return "";
        }
        return String.format("  💡 To attach: jdwp_wait_for_attach(port=%d) or jdwp_connect%n", target.jdwp().port());
    }

    private static String formatMainClass(JvmDescriptor d) {
        final String name = d.mainClass() != null ? d.mainClass() : "<unknown>";
        final String marker = d.isThisProcess() ? " (THIS PROCESS)" : "";
        // Truncate the class first so the THIS-PROCESS marker is never lost when the name
        // overflows the column — the marker is the most actionable piece of info on that row.
        final int budgetForName = Math.max(1, MAIN_CLASS_COL_WIDTH - marker.length());
        return truncate(name, budgetForName) + marker;
    }

    private static String formatJdwp(@Nullable JdwpEndpoint ep) {
        if (ep == null) {
            return "—";
        }
        final String hostPart = "*".equals(ep.host()) ? "" : ep.host();
        final String suspendFlag = ep.suspendOnStart() ? " (s)" : "";
        return hostPart + ':' + ep.port() + suspendFlag;
    }

    private static String formatState(@Nullable JdwpEndpoint ep) {
        if (ep == null) {
            return "no JDWP";
        }
        return switch (ep.state()) {
            case LISTENING -> "LISTENING";
            case ATTACHABLE -> "ATTACHABLE";
            case SUSPENDED -> "SUSPENDED";
            case CONNECTED_TO_US -> "ATTACHED";
            case UNREACHABLE -> "UNREACHABLE";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private static String truncate(String s, int width) {
        if (s.length() <= width) {
            return s;
        }
        return s.substring(0, width - 1) + '…';
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }

    private static String padLeft(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return " ".repeat(width - s.length()) + s;
    }

    static String humanDuration(Duration d) {
        final long totalSeconds = Math.max(0, d.getSeconds());
        final long hours = totalSeconds / 3600;
        final long minutes = (totalSeconds % 3600) / 60;
        final long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }

    /**
     * Minimal projection of the JDI connection state — keeps {@code DiagnoseReportRenderer}
     * decoupled from {@code JDIConnectionService} so tests don't need to spin one up.
     *
     * @param connected           {@code true} when a live {@code VirtualMachine} exists
     *                            right now (liveness-probed); not "was once connected"
     * @param lastHost            host of the most recent {@code connect()} attempt,
     *                            successful or not; {@code null} when no attempt was ever made
     * @param lastPort            port of the most recent attempt; {@code 0} when no attempt
     *                            was ever made
     * @param lastConnectAttempt  wall-clock time of the most recent attempt; {@code null}
     *                            when no attempt was ever made in this MCP-server lifetime
     * @param lastConnectError    human-readable failure reason from the most recent attempt;
     *                            {@code null} when the last attempt succeeded
     */
    public record JDIConnectionStatusView(
        boolean connected,
        @Nullable String lastHost,
        int lastPort,
        @Nullable Instant lastConnectAttempt,
        @Nullable String lastConnectError
    ) {}
}
