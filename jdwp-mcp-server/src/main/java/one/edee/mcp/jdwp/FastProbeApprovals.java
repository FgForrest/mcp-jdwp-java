package one.edee.mcp.jdwp;

import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService.Kind;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Standing approvals for fast logpoints and fast breakpoints (issue #61). A user's explicit
 * {@code userApproved=true} for one exact probe at one endpoint is remembered here, so the same
 * probe can be armed on later JVMs at that host:port without asking again. Owned by the MCP tool
 * singleton: the grants live as long as the MCP server process and survive JVM exits, disconnects,
 * reconnects and resets. Never persisted. Safe for concurrent tool calls.
 */
final class FastProbeApprovals {

    /**
     * What one approval covers. Every field must match exactly for a grant to apply; a different
     * expression, condition, suspend policy, stop mode or endpoint is a different probe.
     *
     * @param kind          fast logpoint or fast breakpoint
     * @param className     fully qualified class of the probed line
     * @param line          the probed line
     * @param expression    the logpoint's expression; {@code null} for a fast breakpoint
     * @param condition     the condition, or {@code null} when the probe has none
     * @param suspendPolicy JDI suspend policy of the probe's stop
     * @param stopOnce      fast breakpoint stop mode (once per generation, or every hit)
     * @param endpoint      normalized host:port the approval was given for
     */
    record Key(Kind kind, String className, int line, @Nullable String expression, @Nullable String condition,
               int suspendPolicy, boolean stopOnce, JDIConnectionService.TargetEndpoint endpoint) {
    }

    /** Grant time per approved probe; the first grant wins. */
    private final ConcurrentMap<Key, Instant> grants = new ConcurrentHashMap<>();

    /**
     * When the user approved exactly this probe, or {@code null} when no standing approval exists.
     */
    @Nullable
    Instant grantedAt(Key key) {
        return this.grants.get(key);
    }

    /**
     * Records the grant after the probe was accepted and renders the reply note. An explicit approval
     * creates a grant unless one exists already (repeated or concurrent approvals keep the original
     * time); a call armed under an existing grant only reports it.
     *
     * @param key                the armed probe
     * @param explicitlyApproved whether this call carried {@code userApproved=true}
     * @param standingApproval   the grant time found before arming, or {@code null}
     * @return a one-line note naming the standing approval that was used, or {@code ""} for a new grant
     */
    String afterArming(Key key, boolean explicitlyApproved, @Nullable Instant standingApproval) {
        final Instant usedGrant = standingApproval == null && explicitlyApproved
            ? this.grants.putIfAbsent(key, Instant.now())
            : standingApproval;
        return usedGrant == null ? ""
            : "\nStanding approval used (granted at " + JDIConnectionService.formatServerTime(usedGrant) + ").";
    }

    /**
     * An immutable copy of all grants, for {@code jdwp_overview}.
     */
    Map<Key, Instant> snapshot() {
        return Map.copyOf(this.grants);
    }
}
