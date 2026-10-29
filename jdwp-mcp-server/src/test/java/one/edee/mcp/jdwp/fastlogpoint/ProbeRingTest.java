package one.edee.mcp.jdwp.fastlogpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the ring-buffer reading rules a fast-logpoint drain relies on: entry parsing, the
 * ready / overwritten / pending classification of a slot, and the overrun and ingest-cap
 * arithmetic of a drain window.
 */
@DisplayName("Fast-logpoint ring decoding")
class ProbeRingTest {

	private static String entry(long seq, char kind, String thread, String value) {
		return seq + "\0" + 1_700_000_000_000L + "\0" + 123_456_789L + "\0" + kind + "\0" + thread + "\0" + value;
	}

	@Nested
	@DisplayName("parse")
	class Parse {

		@Test
		@DisplayName("splits the six fields and keeps separators inside the value")
		void parsesAllFields() {
			final ProbeRing.Entry parsed = ProbeRing.parse(entry(42, 'V', "worker-1", "a\0b"));
			assertThat(parsed).isNotNull();
			assertThat(parsed.seq()).isEqualTo(42);
			assertThat(parsed.epochMillis()).isEqualTo(1_700_000_000_000L);
			assertThat(parsed.nanoTime()).isEqualTo(123_456_789L);
			assertThat(parsed.kind()).isEqualTo('V');
			assertThat(parsed.thread()).isEqualTo("worker-1");
			assertThat(parsed.value()).isEqualTo("a\0b");
		}

		@Test
		@DisplayName("an empty value is still an entry")
		void keepsEmptyValue() {
			final ProbeRing.Entry parsed = ProbeRing.parse(entry(0, 'E', "main", ""));
			assertThat(parsed).isNotNull();
			assertThat(parsed.value()).isEmpty();
		}

		@Test
		@DisplayName("null and malformed slots parse to null")
		void rejectsMalformed() {
			assertThat(ProbeRing.parse(null)).isNull();
			assertThat(ProbeRing.parse("garbage")).isNull();
			assertThat(ProbeRing.parse("x\01\02\0V\0t\0v")).isNull();
			assertThat(ProbeRing.parse("1\01\02\0VV\0t\0v")).isNull();
		}
	}

	@Nested
	@DisplayName("classify")
	class Classify {

		@Test
		@DisplayName("the expected sequence number is ready")
		void ready() {
			assertThat(ProbeRing.classify(7, ProbeRing.parse(entry(7, 'V', "t", "v"))))
				.isEqualTo(ProbeRing.SlotState.READY);
		}

		@Test
		@DisplayName("a newer sequence number means the entry was overwritten by a later lap")
		void overwritten() {
			assertThat(ProbeRing.classify(7, ProbeRing.parse(entry(7 + 16, 'V', "t", "v"))))
				.isEqualTo(ProbeRing.SlotState.OVERWRITTEN);
		}

		@Test
		@DisplayName("an empty slot or an older entry means the write is still in progress")
		void pending() {
			assertThat(ProbeRing.classify(7, null)).isEqualTo(ProbeRing.SlotState.PENDING);
			assertThat(ProbeRing.classify(23, ProbeRing.parse(entry(7, 'V', "t", "v"))))
				.isEqualTo(ProbeRing.SlotState.PENDING);
		}
	}

	@Nested
	@DisplayName("plan")
	class Plan {

		@Test
		@DisplayName("nothing new reads nothing")
		void nothingNew() {
			final ProbeRing.Window window = ProbeRing.plan(10, 10, 16, 500);
			assertThat(window.to() - window.from()).isZero();
			assertThat(window.overrun()).isZero();
			assertThat(window.skipped()).isZero();
		}

		@Test
		@DisplayName("everything still in the ring is read")
		void withinCapacity() {
			assertThat(ProbeRing.plan(10, 20, 16, 500)).isEqualTo(new ProbeRing.Window(10, 20, 0, 0));
		}

		@Test
		@DisplayName("entries older than one ring length are counted as overrun")
		void overrun() {
			assertThat(ProbeRing.plan(0, 100, 16, 500)).isEqualTo(new ProbeRing.Window(84, 100, 84, 0));
		}

		@Test
		@DisplayName("a backlog beyond the ingest cap skips the oldest readable entries")
		void ingestCap() {
			assertThat(ProbeRing.plan(0, 4096, 4096, 500)).isEqualTo(new ProbeRing.Window(3596, 4096, 0, 3596));
		}

		@Test
		@DisplayName("overrun and ingest cap combine")
		void overrunAndCap() {
			assertThat(ProbeRing.plan(0, 10_000, 4096, 500))
				.isEqualTo(new ProbeRing.Window(9500, 10_000, 10_000 - 4096, 4096 - 500));
		}
	}
}
