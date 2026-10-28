package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, for the probes a fresh session finds for one target class, which of them to list and
 * whether the class is exactly as the arming session recorded it. Pure: the caller reads the facts
 * from the VM. See {@code PROPOSAL-fast-logpoint-registry.md} § 3.5.
 * <p>
 * Wiring is observed ({@link ProbeCallScanner}), not inferred from bookkeeping. A probe is hidden
 * only when it is inert: the class's code provably does not call it and its kill switch is off.
 * When the wiring cannot be observed, every probe that might matter is listed as
 * {@link Kind#STRANDED}.
 */
final class LeftoverClassifier {
    /**
     * Shown for a class whose code no longer matches its install record.
     */
    static final String CHANGED = "the class's bytecode differs from what the arming session recorded (changed since "
        + "— e.g. a HotSwap or another agent — or that session died in the middle of changing it); restoring would "
        + "undo that, so only the kill switch is offered";
    /**
     * Shown for a probe the class provably no longer calls but whose kill switch is still on.
     */
    static final String UNWIRED_ENABLED = "no longer called by the class's current code, but still enabled — a "
        + "thread still running the pre-redefinition version of the method can call it";

    private LeftoverClassifier() {
    }

    /**
     * Classifies the probes found for one target class.
     *
     * @param targetClass binary name the probes name as their target
     * @param probes      every probe found for that target in the target's class loader
     * @param live        live code per method of the target, or {@code null} when it could not be read
     * @param wired       probe classes the live code calls ({@link ProbeCallScanner}), or {@code null}
     *                    when that could not be determined
     * @param unreadable  why {@code live} is {@code null}, when it is
     * @param records     decoded install records by image key; a key missing here was unreadable
     */
    static Result classify(String targetClass, List<Probe> probes, @Nullable Map<String, byte[]> live,
                           @Nullable Set<String> wired, @Nullable String unreadable,
                           Map<Object, GenerationRecord> records) {
        final Map<String, Verdict> verdicts = new LinkedHashMap<>();
        if (live == null) {
            final String reason = unreadable != null ? unreadable : "its target class is not loaded by the probe's class loader";
            for (Probe probe : probes) {
                verdicts.put(probe.name(), probe.enabled() || probe.imageKey() != null
                    ? new Verdict(Kind.STRANDED, reason, false) : Verdict.HIDDEN);
            }
            return new Result(verdicts, null);
        }

        // The class verifies when the probes it calls share one install record that names exactly
        // them and whose snapshot equals the live code.
        final Set<String> candidates = new HashSet<>();
        final Set<Object> imageKeys = new LinkedHashSet<>();
        for (Probe probe : probes) {
            if (probe.format() == FastLogpointProbeSource.FORMAT && probe.imageKey() != null
                && (wired == null || wired.contains(probe.name()))) {
                candidates.add(probe.name());
                imageKeys.add(probe.imageKey());
            }
        }
        GenerationRecord verified = null;
        final String reason;
        if (imageKeys.size() == 1) {
            final GenerationRecord record = records.get(imageKeys.iterator().next());
            final Set<String> recorded = record != null ? new HashSet<>(record.probes()) : Set.of();
            if (record == null) {
                reason = "its install record is unreadable";
            } else if (!record.targetClass().equals(targetClass) || !record.matches(live)) {
                reason = CHANGED;
            } else if (wired != null && !wired.equals(recorded)) {
                reason = "the class calls other probes than its install record names";
            } else if (!candidates.equals(recorded)) {
                reason = "its probes disagree with their install record";
            } else {
                verified = record;
                reason = "";
            }
        } else if (imageKeys.isEmpty()) {
            reason = wired == null
                ? "its wiring cannot be determined (the VM does not expose constant pools) and no install record was found"
                : "no install was recorded for it — the arming session ended in the middle of installing it";
        } else {
            reason = "the probes of this class reference different install records";
        }

        final Set<String> calls = verified != null ? new HashSet<>(verified.probes()) : wired;
        for (Probe probe : probes) {
            final boolean known = probe.format() == FastLogpointProbeSource.FORMAT;
            if (calls == null) {
                verdicts.put(probe.name(), probe.enabled() || probe.imageKey() != null
                    ? new Verdict(Kind.STRANDED, known ? reason : unknownFormat(probe), false) : Verdict.HIDDEN);
            } else if (calls.contains(probe.name())) {
                verdicts.put(probe.name(), verified != null && known
                    ? new Verdict(Kind.RECOVERED, null, false)
                    : new Verdict(Kind.STRANDED, known ? reason : unknownFormat(probe), false));
            } else if (probe.enabled()) {
                verdicts.put(probe.name(), new Verdict(Kind.STRANDED, UNWIRED_ENABLED, true));
            } else {
                verdicts.put(probe.name(), Verdict.HIDDEN);
            }
        }
        return new Result(verdicts, verified);
    }

    private static String unknownFormat(Probe probe) {
        return "written by another mcp-jdwp-java version (probe format " + probe.format()
            + ") — only its kill switch is understood";
    }

    /**
     * What happens to a found probe.
     */
    enum Kind {
        /**
         * Called by a class that matches its install record exactly: listed; disable freely,
         * restore with the user's approval.
         */
        RECOVERED,
        /**
         * Listed with a reason; only the kill switch is offered.
         */
        STRANDED,
        /**
         * Inert; not listed.
         */
        HIDDEN
    }

    /**
     * Facts read from one probe class.
     *
     * @param name     binary name of the probe class
     * @param format   its {@code MCP_FORMAT}
     * @param enabled  its kill switch
     * @param imageKey identity of the install record its {@code MCP_IMAGE} references, or {@code null}
     */
    record Probe(String name, int format, boolean enabled, @Nullable Object imageKey) {
    }

    /**
     * The decision for one probe.
     *
     * @param provenUnwired the class's code was observed not to call it
     */
    record Verdict(Kind kind, @Nullable String reason, boolean provenUnwired) {
        static final Verdict HIDDEN = new Verdict(Kind.HIDDEN, null, false);
    }

    /**
     * Verdicts per probe name, plus the matching record when the class verified.
     */
    record Result(Map<String, Verdict> verdicts, @Nullable GenerationRecord record) {
    }
}
