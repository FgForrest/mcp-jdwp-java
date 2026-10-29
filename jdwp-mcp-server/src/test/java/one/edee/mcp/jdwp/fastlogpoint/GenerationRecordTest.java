package one.edee.mcp.jdwp.fastlogpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The install record a server stores in the target for a later session: it must survive the round
 * trip exactly, reject anything that is not a record, and compare live code byte for byte.
 */
@DisplayName("GenerationRecord")
class GenerationRecordTest {

	private static final GenerationRecord RECORD = new GenerationRecord("com.acme.Foo", new byte[]{(byte) 0xCA, 0x0F, 0x00},
		List.of("p.McpFastLogpoint_b", "p.McpFastLogpoint_a"),
		Map.of("compute(I)I", new byte[]{0x1A, (byte) 0xAC}, "<init>()V", new byte[]{0x2A, (byte) 0xB1}));

	@Test
	@DisplayName("encode and decode round-trip; probes come back sorted")
	void roundTrips() throws Exception {
		final GenerationRecord decoded = GenerationRecord.decode(RECORD.encode());
		assertThat(decoded.targetClass()).isEqualTo("com.acme.Foo");
		assertThat(decoded.original()).containsExactly(0xCA, 0x0F, 0x00);
		assertThat(decoded.probes()).containsExactly("p.McpFastLogpoint_a", "p.McpFastLogpoint_b");
		assertThat(decoded.installed()).containsOnlyKeys("compute(I)I", "<init>()V");
		assertThat(decoded.installed().get("compute(I)I")).containsExactly(0x1A, 0xAC);
		assertThat(decoded.encode()).isEqualTo(RECORD.encode());
	}

	@Test
	@DisplayName("live code matches only with the same methods and identical bytes")
	void matchesExactly() {
		assertThat(RECORD.matches(Map.of("compute(I)I", new byte[]{0x1A, (byte) 0xAC}, "<init>()V", new byte[]{0x2A, (byte) 0xB1})))
			.isTrue();
		assertThat(RECORD.matches(Map.of("compute(I)I", new byte[]{0x1A, (byte) 0xAC}))).as("method missing").isFalse();
		assertThat(RECORD.matches(Map.of("compute(I)I", new byte[]{0x1A, (byte) 0xAD}, "<init>()V", new byte[]{0x2A, (byte) 0xB1})))
			.as("one byte differs").isFalse();
		assertThat(RECORD.matches(Map.of("compute(I)I", new byte[]{0x1A, (byte) 0xAC}, "<init>()V", new byte[]{0x2A, (byte) 0xB1},
			"extra()V", new byte[]{(byte) 0xB1}))).as("method added").isFalse();
	}

	@Test
	@DisplayName("anything that is not a complete record of this version is rejected")
	void rejectsCorruptInput() {
		final byte[] encoded = RECORD.encode();
		assertThatThrownBy(() -> GenerationRecord.decode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("not a fast-logpoint generation record");
		assertThatThrownBy(() -> GenerationRecord.decode(Arrays.copyOf(encoded, encoded.length - 1)))
			.isInstanceOf(FastLogpointException.class);
		assertThatThrownBy(() -> GenerationRecord.decode(Arrays.copyOf(encoded, encoded.length + 1)))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("trailing");
		final byte[] newerVersion = encoded.clone();
		newerVersion[7] = 9;
		assertThatThrownBy(() -> GenerationRecord.decode(newerVersion))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("version 9");
		final byte[] hugeLength = encoded.clone();
		// The original's length field follows magic, version and the UTF-8 class name.
		final int lengthOffset = 8 + 2 + "com.acme.Foo".length();
		hugeLength[lengthOffset] = 0x7F;
		assertThatThrownBy(() -> GenerationRecord.decode(hugeLength))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("corrupt");
	}
}
