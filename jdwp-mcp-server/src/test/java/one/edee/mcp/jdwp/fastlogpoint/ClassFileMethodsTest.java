package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.fastlogpoint.fixture.InstrumentationFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the class-file walk behind the fast-logpoint bytecode guard: every method with a body is
 * keyed by name + descriptor, abstract and native methods are absent, and every constant-pool
 * entry kind (including the two-slot {@code Long} / {@code Double}) is skipped correctly.
 */
@DisplayName("ClassFileMethods")
class ClassFileMethodsTest {

	@Test
	@DisplayName("keys every method body by name + descriptor")
	void readsFixtureMethods() throws Exception {
		final Map<String, byte[]> code = ClassFileMethods.codeByMethod(classFile(InstrumentationFixture.class));
		assertThat(code).containsOnlyKeys("<init>(Ljava/lang/String;)V", "mix(IJLjava/lang/String;)J", "scale(D[I)D");
		// Constructor: aload_0, invokespecial Object.<init>
		assertThat(Arrays.copyOf(code.get("<init>(Ljava/lang/String;)V"), 2)).containsExactly(0x2A, 0xB7);
	}

	@Test
	@DisplayName("skips native methods and survives Long/Double constants")
	void readsPlatformClasses() throws Exception {
		final Map<String, byte[]> object = ClassFileMethods.codeByMethod(classFile(Object.class));
		assertThat(object).containsKey("equals(Ljava/lang/Object;)Z").doesNotContainKey("hashCode()I");

		final Map<String, byte[]> longs = ClassFileMethods.codeByMethod(classFile(Long.class));
		assertThat(longs).containsKey("parseLong(Ljava/lang/String;)J");
		final Map<String, byte[]> doubles = ClassFileMethods.codeByMethod(classFile(Double.class));
		assertThat(doubles).containsKey("isNaN(D)Z");
	}

	@Test
	@DisplayName("rejects bytes that are not a class file")
	void rejectsGarbage() {
		assertThatThrownBy(() -> ClassFileMethods.codeByMethod(new byte[]{1, 2, 3, 4, 5}))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ClassFileMethods.codeByMethod(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static byte[] classFile(Class<?> type) throws Exception {
		try (InputStream in = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class")) {
			return Objects.requireNonNull(in).readAllBytes();
		}
	}
}
