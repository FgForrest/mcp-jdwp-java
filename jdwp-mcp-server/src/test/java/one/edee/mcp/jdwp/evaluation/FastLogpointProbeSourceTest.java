package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compiles generated fast-logpoint probes with the production compiler (Eclipse JDT) and runs them
 * in-process: the entry format the server parses, condition filtering, error capture, the kill
 * switch, ring wrap-around and value rendering.
 */
@DisplayName("FastLogpointProbeSource")
class FastLogpointProbeSourceTest {

	private static final List<FastLogpointProbeSource.Parameter> PARAMETERS = List.of(
		new FastLogpointProbeSource.Parameter("i", "int"),
		new FastLogpointProbeSource.Parameter("label", "java.lang.String"));

	private InMemoryJavaCompiler compiler;

	@BeforeEach
	void setUp() {
		compiler = new InMemoryJavaCompiler();
		compiler.configure(System.getProperty("java.home"), "", 17);
	}

	@Test
	@DisplayName("a hit records seq, wall clock, nanoTime, kind, thread and value")
	void recordsEntry() throws Exception {
		final Probe probe = compile("\"i=\" + i + \" label=\" + label", null, 16);
		final long before = System.currentTimeMillis();
		probe.hit(3, "x");

		assertThat(probe.seq()).isEqualTo(1);
		final String[] fields = probe.slot(0).split("\0", -1);
		assertThat(fields).hasSize(6);
		assertThat(fields[0]).isEqualTo("0");
		assertThat(Long.parseLong(fields[1])).isBetween(before, System.currentTimeMillis());
		assertThat(Long.parseLong(fields[2])).isPositive();
		assertThat(fields[3]).isEqualTo("V");
		assertThat(fields[4]).isEqualTo(Thread.currentThread().getName());
		assertThat(fields[5]).isEqualTo("i=3 label=x");
	}

	@Test
	@DisplayName("a false condition records nothing; a true one records")
	void filtersByCondition() throws Exception {
		final Probe probe = compile("i", "i % 2 == 0", 16);
		probe.hit(1, "a");
		assertThat(probe.seq()).isZero();
		probe.hit(2, "b");
		assertThat(probe.seq()).isEqualTo(1);
		assertThat(probe.value(0)).isEqualTo("2");
	}

	@Test
	@DisplayName("block syntax works for condition and expression")
	void supportsBlocks() throws Exception {
		final Probe probe = compile("{ int twice = i * 2; return twice; }", "{ return label.length() > 1; }", 16);
		probe.hit(4, "a");
		probe.hit(5, "ab");
		assertThat(probe.seq()).isEqualTo(1);
		assertThat(probe.value(0)).isEqualTo("10");
	}

	@Test
	@DisplayName("exceptions are recorded as E / C entries and never propagate")
	void capturesFailures() throws Exception {
		final Probe failingExpression = compile("10 / i", null, 16);
		failingExpression.hit(0, "x");
		assertThat(failingExpression.kind(0)).isEqualTo("E");
		assertThat(failingExpression.value(0)).contains("ArithmeticException");

		final Probe failingCondition = compile("i", "label.isEmpty()", 16);
		failingCondition.hit(1, null);
		assertThat(failingCondition.kind(0)).isEqualTo("C");
		assertThat(failingCondition.value(0)).contains("NullPointerException");
	}

	@Test
	@DisplayName("a non-boolean condition is a C entry, not a pass")
	void rejectsNonBooleanCondition() throws Exception {
		final Probe probe = compile("i", "{ return label; }", 16);
		probe.hit(1, "yes");
		assertThat(probe.seq()).isEqualTo(1);
		assertThat(probe.kind(0)).isEqualTo("C");
		assertThat(probe.value(0)).contains("did not evaluate to a boolean").contains("yes");
	}

	@Test
	@DisplayName("the kill switch stops all evaluation")
	void honoursKillSwitch() throws Exception {
		final Probe probe = compile("10 / i", null, 16);
		probe.setEnabled(false);
		probe.hit(0, "x");
		assertThat(probe.seq()).isZero();
	}

	@Test
	@DisplayName("entry s lives in slot s & (size - 1)")
	void wrapsAround() throws Exception {
		final Probe probe = compile("i", null, 16);
		for (int i = 0; i < 40; i++) {
			probe.hit(i, "x");
		}
		assertThat(probe.seq()).isEqualTo(40);
		assertThat(probe.slot(39 & 15)).startsWith("39\0");
		assertThat(probe.value(39 & 15)).isEqualTo("39");
	}

	@Test
	@DisplayName("values render without calling toString on arbitrary objects")
	void rendersWithoutToString() throws Exception {
		final Probe nullValue = compile("null", null, 16);
		nullValue.hit(0, "x");
		assertThat(nullValue.value(0)).isEqualTo("null");

		final Probe enumValue = compile("java.util.concurrent.TimeUnit.SECONDS", null, 16);
		enumValue.hit(0, "x");
		assertThat(enumValue.value(0)).isEqualTo(TimeUnit.SECONDS.name());

		final Probe objectValue = compile("new java.util.ArrayList<String>(java.util.List.of(label))", null, 16);
		objectValue.hit(0, "x");
		assertThat(objectValue.value(0)).matches("java\\.util\\.ArrayList@[0-9a-f]+");

		final Probe explicitToString = compile("java.util.List.of(label).toString()", null, 16);
		explicitToString.hit(0, "x");
		assertThat(explicitToString.value(0)).isEqualTo("[x]");
	}

	@Test
	@DisplayName("bufferSize must be a power of two")
	void rejectsNonPowerOfTwo() {
		assertThatThrownBy(() -> FastLogpointProbeSource.generate("p.Probe", PARAMETERS, "i", null, 100,
			metadata("i", null)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("a new probe starts disabled: nothing is recorded until the server enables it")
	void startsDisabled() throws Exception {
		final Probe probe = compileDisabled("i", null, metadata("i", null));
		probe.hit(1, "x");
		assertThat(probe.seq()).isZero();
		assertThat(probe.type().getField(FastLogpointProbeSource.IMAGE_FIELD).get(null)).isNull();
	}

	@Test
	@DisplayName("the probe describes itself in constants that keep the user's text verbatim")
	void describesItself() throws Exception {
		// Each of these breaks a naively escaped string literal: quotes, backslashes, a backslash-u
		// sequence the compiler would translate before lexing, line breaks, a NUL followed by digits,
		// a comment terminator and text outside the BMP.
		final String nasty = "a\"b\\c \\u0022 \\\\u0022 x\ny\r\0001 */ \t é 😀 end\\";
		final FastLogpointProbeSource.Metadata metadata = new FastLogpointProbeSource.Metadata("com.acme.Foo",
			"compute", "(I)I", 42, nasty, nasty + "?", 1_234_567_890_123L, "mcp-jdwp-java test, session 0badcafe");
		final Class<?> type = compileDisabled("i", null, metadata).type();

		assertThat(type.getField(FastLogpointProbeSource.FORMAT_FIELD).getInt(null)).isEqualTo(FastLogpointProbeSource.FORMAT);
		assertThat(type.getField(FastLogpointProbeSource.TARGET_CLASS_FIELD).get(null)).isEqualTo("com.acme.Foo");
		assertThat(type.getField(FastLogpointProbeSource.TARGET_METHOD_FIELD).get(null)).isEqualTo("compute");
		assertThat(type.getField(FastLogpointProbeSource.TARGET_DESCRIPTOR_FIELD).get(null)).isEqualTo("(I)I");
		assertThat(type.getField(FastLogpointProbeSource.TARGET_LINE_FIELD).getInt(null)).isEqualTo(42);
		assertThat(type.getField(FastLogpointProbeSource.EXPRESSION_FIELD).get(null)).isEqualTo(nasty);
		assertThat(type.getField(FastLogpointProbeSource.CONDITION_FIELD).get(null)).isEqualTo(nasty + "?");
		assertThat(type.getField(FastLogpointProbeSource.ARMED_AT_FIELD).getLong(null)).isEqualTo(1_234_567_890_123L);
		assertThat(type.getField(FastLogpointProbeSource.ARMED_BY_FIELD).get(null))
			.isEqualTo("mcp-jdwp-java test, session 0badcafe");
		assertThat((String) type.getField(FastLogpointProbeSource.ABOUT_FIELD).get(null)).contains("ENABLED");
	}

	@Test
	@DisplayName("long metadata is cut and marked, never split inside a surrogate pair")
	void boundsMetadata() throws Exception {
		final String emoji = "😀";
		final String longText = "x".repeat(FastLogpointProbeSource.MAX_METADATA_CHARS - 1) + emoji + "tail";
		final Class<?> type = compileDisabled("i", null, metadata(longText, null)).type();
		final String stored = (String) type.getField(FastLogpointProbeSource.EXPRESSION_FIELD).get(null);
		assertThat(stored).endsWith(FastLogpointProbeSource.TRUNCATION_MARKER)
			.startsWith("x".repeat(FastLogpointProbeSource.MAX_METADATA_CHARS - 1))
			.doesNotContain(emoji);
		assertThat(type.getField(FastLogpointProbeSource.CONDITION_FIELD).get(null)).isEqualTo("");
	}

	private static FastLogpointProbeSource.Metadata metadata(String expression, @Nullable String condition) {
		return new FastLogpointProbeSource.Metadata("com.acme.Foo", "compute", "(I)I", 42, expression, condition,
			System.currentTimeMillis(), "mcp-jdwp-java test, session 00000000");
	}

	private Probe compile(String expression, @Nullable String condition, int bufferSize) throws Exception {
		final Probe probe = compile(expression, condition, bufferSize, metadata(expression, condition));
		probe.setEnabled(true);
		return probe;
	}

	private Probe compileDisabled(String expression, @Nullable String condition,
	                              FastLogpointProbeSource.Metadata metadata) throws Exception {
		return compile(expression, condition, 16, metadata);
	}

	private Probe compile(String expression, @Nullable String condition, int bufferSize,
	                      FastLogpointProbeSource.Metadata metadata) throws Exception {
		final String className = "probe.test." + FastLogpointProbeSource.CLASS_PREFIX + System.nanoTime();
		final String source = FastLogpointProbeSource.generate(className, PARAMETERS, expression, condition, bufferSize,
			metadata);
		final Map<String, byte[]> compiled = compiler.compile(className, source);
		assertThat(compiled).as(source).hasSize(1);
		final byte[] bytecode = compiled.values().iterator().next();
		final Class<?> type = new ClassLoader(getClass().getClassLoader()) {
			Class<?> define() {
				return defineClass(className, bytecode, 0, bytecode.length);
			}
		}.define();
		return new Probe(type);
	}

	/**
	 * Reflective handle on a compiled probe class.
	 */
	private record Probe(Class<?> type) {

		void hit(int i, @Nullable String label) throws Exception {
			final Method hit = type.getMethod(FastLogpointProbeSource.HIT_METHOD, int.class, String.class);
			hit.invoke(null, i, label);
		}

		long seq() throws Exception {
			return ((AtomicLong) type.getField(FastLogpointProbeSource.SEQ_FIELD).get(null)).get();
		}

		String slot(int index) throws Exception {
			return ((String[]) type.getField(FastLogpointProbeSource.RING_FIELD).get(null))[index];
		}

		String kind(int index) throws Exception {
			return slot(index).split("\0", -1)[3];
		}

		String value(int index) throws Exception {
			return slot(index).split("\0", 6)[5];
		}

		void setEnabled(boolean enabled) throws Exception {
			final Field field = type.getField(FastLogpointProbeSource.ENABLED_FIELD);
			field.setBoolean(null, enabled);
		}
	}
}
