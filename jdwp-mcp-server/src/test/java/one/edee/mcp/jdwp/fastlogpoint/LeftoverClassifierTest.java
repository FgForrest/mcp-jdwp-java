package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.evaluation.FastBreakpointProbeSource;
import one.edee.mcp.jdwp.evaluation.FastLogpointProbeSource;
import one.edee.mcp.jdwp.fastlogpoint.LeftoverClassifier.Kind;
import one.edee.mcp.jdwp.fastlogpoint.LeftoverClassifier.Probe;
import one.edee.mcp.jdwp.fastlogpoint.LeftoverClassifier.Result;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification of the probes a fresh session finds — one test per failure window of the proposal
 * (§ 5). The invariant under test: a probe the class may call is never hidden, and RECOVERED (the
 * only state that can be restored) needs a record that matches the live code and names exactly the
 * probes the code calls.
 */
@DisplayName("LeftoverClassifier")
class LeftoverClassifierTest {

	private static final String TARGET = "com.acme.Foo";
	private static final String A = "com.acme.McpFastLogpoint_a";
	private static final String B = "com.acme.McpFastLogpoint_b";
	private static final Map<String, byte[]> INSTALLED = Map.of("compute(I)I", new byte[]{0x1A, (byte) 0xB8, 0, 9, (byte) 0xAC});
	private static final Map<String, byte[]> CHANGED = Map.of("compute(I)I", new byte[]{0x00, 0x1A, (byte) 0xB8, 0, 9, (byte) 0xAC});
	private static final Object RECORD_KEY = 1L;

	@Test
	@DisplayName("armed when the server died: RECOVERED and still evaluating")
	void armedThenKilled() {
		final Result result = classify(List.of(probe(A, true, RECORD_KEY)), INSTALLED, Set.of(A), record(A));
		assertThat(kind(result, A)).isEqualTo(Kind.RECOVERED);
		assertThat(result.record()).isNotNull();
	}

	@Test
	@DisplayName("kill switch set, class not yet restored: RECOVERED, disabled — still wired, so listed")
	void killedBeforeRestore() {
		assertThat(kind(classify(List.of(probe(A, false, RECORD_KEY)), INSTALLED, Set.of(A), record(A)), A))
			.isEqualTo(Kind.RECOVERED);
	}

	@Test
	@DisplayName("several disabled probes in one class are all listed")
	void severalDisabledProbes() {
		final Result result = classify(List.of(probe(A, false, RECORD_KEY), probe(B, false, RECORD_KEY)), INSTALLED,
			Set.of(A, B), record(A, B));
		assertThat(kind(result, A)).isEqualTo(Kind.RECOVERED);
		assertThat(kind(result, B)).isEqualTo(Kind.RECOVERED);
	}

	@Test
	@DisplayName("the class changed after the install: STRANDED, never restorable")
	void classChanged() {
		final Result result = classify(List.of(probe(A, true, RECORD_KEY)), CHANGED, Set.of(A), record(A));
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(result.verdicts().get(A).reason()).isEqualTo(LeftoverClassifier.CHANGED);
		assertThat(result.record()).isNull();
	}

	@Test
	@DisplayName("died after the first redefinition, before the record: wired without a record is STRANDED")
	void wiredWithoutRecord() {
		final Result result = classify(List.of(probe(A, false, null)), INSTALLED, Set.of(A), Map.of());
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(result.verdicts().get(A).reason()).contains("no install was recorded");
	}

	@Test
	@DisplayName("defined but never installed, or cleanly removed: inert and hidden")
	void neverInstalled() {
		assertThat(kind(classify(List.of(probe(A, false, null)), INSTALLED, Set.of(), Map.of()), A)).isEqualTo(Kind.HIDDEN);
	}

	@Test
	@DisplayName("removed from the class but still enabled: listed as uncalled, provably unwired")
	void unwiredButEnabled() {
		final Result result = classify(List.of(probe(A, true, null)), INSTALLED, Set.of(), Map.of());
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(result.verdicts().get(A).reason()).isEqualTo(LeftoverClassifier.UNWIRED_ENABLED);
		assertThat(result.verdicts().get(A).provenUnwired()).isTrue();
	}

	@Test
	@DisplayName("a verified class proves a disabled probe outside its record unwired: hidden")
	void verifiedClassHidesUnwiredProbe() {
		final Result result = classify(List.of(probe(A, true, RECORD_KEY), probe(B, false, null)), INSTALLED, Set.of(A),
			record(A));
		assertThat(kind(result, A)).isEqualTo(Kind.RECOVERED);
		assertThat(kind(result, B)).isEqualTo(Kind.HIDDEN);
	}

	@Test
	@DisplayName("a transformer kept a removed call: the code calls a probe the record does not name — STRANDED")
	void codeCallsMoreThanRecorded() {
		final Result result = classify(List.of(probe(A, true, RECORD_KEY), probe(B, false, null)), INSTALLED, Set.of(A, B),
			record(A));
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(kind(result, B)).isEqualTo(Kind.STRANDED);
	}

	@Test
	@DisplayName("probes of one class referencing different records: STRANDED")
	void differentRecords() {
		final Result result = classify(List.of(probe(A, true, RECORD_KEY), probe(B, true, 2L)), INSTALLED, Set.of(A, B),
			Map.of(RECORD_KEY, record(A, B).get(RECORD_KEY), 2L, record(A, B).get(RECORD_KEY)));
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(result.verdicts().get(A).reason()).contains("different install records");
	}

	@Test
	@DisplayName("an unreadable record: STRANDED")
	void unreadableRecord() {
		final Result result = classify(List.of(probe(A, true, RECORD_KEY)), INSTALLED, Set.of(A), Map.of());
		assertThat(result.verdicts().get(A).reason()).contains("unreadable");
	}

	@Test
	@DisplayName("wiring unobservable: a matching record still verifies; otherwise nothing is hidden")
	void wiringUnknown() {
		assertThat(kind(classify(List.of(probe(A, false, RECORD_KEY)), INSTALLED, null, record(A)), A))
			.isEqualTo(Kind.RECOVERED);
		final Result unverified = classify(List.of(probe(A, false, RECORD_KEY), probe(B, false, null)), CHANGED, null,
			record(A));
		assertThat(kind(unverified, A)).isEqualTo(Kind.STRANDED);
		assertThat(kind(unverified, B)).as("disabled without a record is also what a crash after the first redefinition "
			+ "leaves — without wiring it is not proven uncalled").isEqualTo(Kind.STRANDED);
	}

	@Test
	@DisplayName("crash right after the first redefinition, wiring unobservable: the wired, recordless probe is listed")
	void firstArmCrashWithoutWiring() {
		assertThat(kind(classify(List.of(probe(A, false, null)), INSTALLED, null, Map.of()), A)).isEqualTo(Kind.STRANDED);
	}

	@Test
	@DisplayName("target class unreadable: every probe is STRANDED with the reason")
	void targetUnreadable() {
		final Result result = LeftoverClassifier.classify(TARGET, List.of(probe(A, true, null), probe(B, false, null)),
			null, null, "its target class could not be read: boom", Map.of());
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(result.verdicts().get(A).reason()).contains("boom");
		assertThat(kind(result, B)).isEqualTo(Kind.STRANDED);
	}

	@Test
	@DisplayName("a probe of an unknown format the class calls: STRANDED, kill switch only")
	void unknownFormat() {
		final Result result = classify(List.of(new Probe(A, FastLogpointProbeSource.FORMAT + 1, true, RECORD_KEY,
				FastLogpointProbeSource.KIND)),
			INSTALLED, Set.of(A), record(A));
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(result.verdicts().get(A).reason()).contains("probe format 2");
	}

	@Test
	@DisplayName("a probe of an unknown kind in a matching install: STRANDED, never offered for restore")
	void unknownKind() {
		final Result result = classify(List.of(new Probe(A, FastLogpointProbeSource.FORMAT, true, RECORD_KEY,
				"WATCHPOINT")),
			INSTALLED, Set.of(A), record(A));
		assertThat(kind(result, A)).isEqualTo(Kind.STRANDED);
		assertThat(result.verdicts().get(A).reason()).contains("probe kind WATCHPOINT");
		assertThat(result.record()).as("the class does not verify, so nothing can be restored").isNull();
	}

	@Test
	@DisplayName("a fast breakpoint's probe in a matching install: RECOVERED like a fast logpoint's")
	void breakpointKindRecovers() {
		final Result result = classify(List.of(new Probe(A, FastLogpointProbeSource.FORMAT, true, RECORD_KEY,
				FastBreakpointProbeSource.KIND), probe(B, true, RECORD_KEY)),
			INSTALLED, Set.of(A, B), record(A, B));
		assertThat(kind(result, A)).isEqualTo(Kind.RECOVERED);
		assertThat(kind(result, B)).isEqualTo(Kind.RECOVERED);
	}

	private static Result classify(List<Probe> probes, Map<String, byte[]> live, @Nullable Set<String> wired,
	                               Map<Object, GenerationRecord> records) {
		return LeftoverClassifier.classify(TARGET, probes, live, wired, null, records);
	}

	private static Probe probe(String name, boolean enabled, @Nullable Object imageKey) {
		return new Probe(name, FastLogpointProbeSource.FORMAT, enabled, imageKey, FastLogpointProbeSource.KIND);
	}

	private static Map<Object, GenerationRecord> record(String... probes) {
		return Map.of(RECORD_KEY, new GenerationRecord(TARGET, new byte[]{1}, List.of(probes), INSTALLED));
	}

	private static Kind kind(Result result, String probe) {
		return result.verdicts().get(probe).kind();
	}
}
