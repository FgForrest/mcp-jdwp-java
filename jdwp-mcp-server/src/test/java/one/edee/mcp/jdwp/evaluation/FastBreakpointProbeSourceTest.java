package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compiles generated fast-breakpoint probes with the production compiler (Eclipse JDT) and runs them
 * in-process: which hits reach {@code stop()} in once and every-hit mode, the true-hit and error
 * gates and their generations, condition failures in the ring, the kill switch and the probe's
 * self-description.
 */
@DisplayName("FastBreakpointProbeSource")
class FastBreakpointProbeSourceTest {

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
	@DisplayName("once mode: only the first true hit of an epoch stops; re-arming opens the next epoch")
	void stopsOncePerEpoch() throws Exception {
		final Probe probe = compile("i % 2 == 0", true);
		probe.hit(1, "x");
		assertThat(probe.stops()).isZero();
		assertThat(probe.trueHits()).isZero();

		probe.hit(2, "x");
		assertThat(probe.stops()).isEqualTo(1);
		assertThat(probe.claimed()).isEqualTo(1);

		// Spent: the condition is not even evaluated any more.
		probe.hit(4, "x");
		assertThat(probe.stops()).isEqualTo(1);
		assertThat(probe.trueHits()).isEqualTo(1);

		probe.setInt(FastBreakpointProbeSource.EPOCH_FIELD, 2);
		probe.hit(6, "x");
		assertThat(probe.stops()).isEqualTo(2);
		assertThat(probe.claimed()).isEqualTo(2);
	}

	@Test
	@DisplayName("every-hit mode: each true hit stops and the true-hit gate is never used")
	void stopsOnEveryTrueHit() throws Exception {
		final Probe probe = compile("i > 0", false);
		probe.hit(0, "x");
		probe.hit(1, "x");
		probe.hit(2, "x");
		assertThat(probe.stops()).isEqualTo(2);
		assertThat(probe.trueHits()).isEqualTo(2);
		assertThat(probe.claimed()).isZero();
	}

	@Test
	@DisplayName("a gate already claimed for a later generation lets no older evaluation through")
	void neverClaimsBackwards() throws Exception {
		final Probe probe = compile("true", true);
		((AtomicInteger) probe.field(FastBreakpointProbeSource.CLAIMED_FIELD)).set(5);
		probe.setInt(FastBreakpointProbeSource.EPOCH_FIELD, 3);
		probe.hit(1, "x");
		assertThat(probe.stops()).isZero();
		assertThat(probe.claimed()).isEqualTo(5);
	}

	@Test
	@DisplayName("a failing condition is recorded; only the first failure of an error generation stops")
	void stopsOnFirstErrorPerGeneration() throws Exception {
		final Probe probe = compile("label.isEmpty()", false);
		probe.hit(1, null);
		assertThat(probe.stops()).isEqualTo(1);
		assertThat(probe.errors()).isEqualTo(1);
		assertThat(probe.int32(FastBreakpointProbeSource.ERROR_CLAIMED_FIELD)).isEqualTo(1);
		assertThat(probe.kind(0)).isEqualTo("C");
		assertThat(probe.value(0)).contains("NullPointerException");

		probe.hit(2, null);
		assertThat(probe.stops()).isEqualTo(1);
		assertThat(probe.errors()).isEqualTo(2);
		assertThat(probe.seq()).isEqualTo(2);

		probe.setInt(FastBreakpointProbeSource.ERROR_EPOCH_FIELD, 2);
		probe.hit(3, null);
		assertThat(probe.stops()).isEqualTo(2);
	}

	@Test
	@DisplayName("a failure of an evaluation that started before a re-arm cannot claim the error generation the re-arm opened")
	void errorClaimsTheGenerationReadBeforeEvaluating() throws Exception {
		// The first evaluation re-arms the error gate (as the server would, concurrently) and then fails.
		final Probe probe = compile("{ if (i == 1) { ERROR_EPOCH = 2; } return label.isEmpty(); }", false);
		probe.hit(1, null);
		assertThat(probe.stops()).isEqualTo(1);
		assertThat(probe.int32(FastBreakpointProbeSource.ERROR_CLAIMED_FIELD)).isEqualTo(1);

		// The generation the re-arm opened is still unclaimed: the next failure stops.
		probe.hit(2, null);
		assertThat(probe.stops()).isEqualTo(2);
		assertThat(probe.int32(FastBreakpointProbeSource.ERROR_CLAIMED_FIELD)).isEqualTo(2);
	}

	@Test
	@DisplayName("stop() is empty: the breakpoint's step into it has nothing to enter")
	void stopMethodIsEmpty() throws Exception {
		final Probe probe = compile("true", false);
		final Method stop = probe.type().getMethod(FastBreakpointProbeSource.STOP_METHOD, int.class, int.class,
			String.class);
		stop.invoke(null, FastBreakpointProbeSource.STOP_CONDITION_TRUE, 1, null);
		assertThat(probe.stops()).as("only hit() counts stops").isZero();

		probe.hit(1, "x");
		assertThat(probe.stops()).isEqualTo(1);
	}

	@Test
	@DisplayName("an error stop does not spend the true-hit allowance of once mode")
	void errorGateIsSeparate() throws Exception {
		final Probe probe = compile("label.length() > 0", true);
		probe.hit(1, null);
		assertThat(probe.stops()).isEqualTo(1);
		assertThat(probe.claimed()).isZero();

		probe.hit(2, "x");
		assertThat(probe.stops()).isEqualTo(2);
		assertThat(probe.claimed()).isEqualTo(1);
	}

	@Test
	@DisplayName("a condition that yields no boolean is a failure, not a pass")
	void rejectsNonBooleanCondition() throws Exception {
		final Probe probe = compile("{ return label; }", true);
		probe.hit(1, "yes");
		assertThat(probe.errors()).isEqualTo(1);
		assertThat(probe.trueHits()).isZero();
		assertThat(probe.claimed()).isZero();
		assertThat(probe.value(0)).contains("did not evaluate to a boolean").contains("yes");
	}

	@Test
	@DisplayName("a thrown JDK exception is recorded with its message and never propagates")
	void containsThrownExceptions() throws Exception {
		final Probe probe = compile("{ if (i > 0) throw new IllegalStateException(\"detail\"); return true; }", true);
		probe.hit(1, "x");
		assertThat(probe.errors()).isEqualTo(1);
		assertThat(probe.value(0)).contains("IllegalStateException").contains("detail");
	}

	@Test
	@DisplayName("the kill switch stops all evaluation, and a new probe starts disabled")
	void honoursKillSwitch() throws Exception {
		final Probe probe = compile("label.isEmpty()", false);
		probe.setEnabled(false);
		probe.hit(1, null);
		assertThat(probe.errors()).isZero();
		assertThat(probe.stops()).isZero();

		final Probe fresh = compileDisabled("true", true);
		fresh.hit(1, "x");
		assertThat(fresh.stops()).isZero();
		assertThat(fresh.field(FastLogpointProbeSource.IMAGE_FIELD)).isNull();
	}

	@Test
	@DisplayName("the probe describes itself as a fast breakpoint")
	void describesItself() throws Exception {
		final Class<?> type = compileDisabled("i > 1", false).type();
		assertThat(type.getField(FastLogpointProbeSource.KIND_FIELD).get(null)).isEqualTo(FastBreakpointProbeSource.KIND);
		assertThat(type.getField(FastBreakpointProbeSource.ONCE_FIELD).getBoolean(null)).isFalse();
		assertThat(type.getField(FastLogpointProbeSource.FORMAT_FIELD).getInt(null)).isEqualTo(FastLogpointProbeSource.FORMAT);
		assertThat(type.getField(FastLogpointProbeSource.CONDITION_FIELD).get(null)).isEqualTo("i > 1");
		assertThat(type.getField(FastLogpointProbeSource.EXPRESSION_FIELD).get(null)).isEqualTo("");
		assertThat((String) type.getField(FastLogpointProbeSource.ABOUT_FIELD).get(null))
			.contains("jdwp_set_fast_breakpoint").contains("stop()");
		final Method stop = type.getMethod(FastBreakpointProbeSource.STOP_METHOD, int.class, int.class, String.class);
		assertThat(stop.getReturnType()).isEqualTo(void.class);
	}

	/** Checks rendering, capture metadata and the stop signature of a compiled probe. */
	@ParameterizedTest
	@CsvSource({
		"i, 7", "Long.valueOf(i), 7", "label, text", "null, null",
		"new Object(), java.lang.Object@", "{ return i + label; }, 7text",
		"java.math.BigInteger.TEN, 10", "Thread.State.NEW, NEW"
	})
	@DisplayName("capture uses the probe's value renderer for expressions and blocks")
	void rendersCapture(String expression, String expected) throws Exception {
		final Probe probe = compileCapture("true", expression, false);
		final String rendered = probe.capture(7, "text");
		if (expected.endsWith("@")) {
			assertThat(rendered).matches("java\\.lang\\.Object@[0-9a-f]+");
		} else {
			assertThat(rendered).isEqualTo(expected);
		}
		assertThat(probe.field(FastLogpointProbeSource.EXPRESSION_FIELD)).isEqualTo(expression);
		assertThat(probe.type().getMethod(FastBreakpointProbeSource.STOP_METHOD, int.class, int.class,
			String.class, String.class).getReturnType()).isEqualTo(void.class);
	}

	/** Checks that the capture display limit preserves surrogate pairs at the cutoff. */
	@Test
	@DisplayName("capture bounds the text without splitting a surrogate pair")
	void boundsCapture() throws Exception {
		final Probe probe = compileCapture("true", "label", false);
		final String prefix = "x".repeat(FastLogpointProbeSource.MAX_METADATA_CHARS - 1);
		assertThat(probe.capture(0, prefix + "😀!"))
			.isEqualTo(prefix + FastLogpointProbeSource.TRUNCATION_MARKER);
	}

	/** Uses a capture side effect to count evaluations in both stop modes. */
	@Test
	@DisplayName("capture runs only after a true hit has passed its stop gate")
	void capturesOnlyAcceptedTrueHits() throws Exception {
		for (boolean once : new boolean[] {true, false}) {
			final Probe probe = compileCapture("i >= 0 && label.isEmpty()",
				"{ STOPS.addAndGet(10); return i; }", once);
			probe.setEnabled(true);
			probe.hit(-1, "");
			assertThat(probe.stops()).isZero();
			probe.hit(0, null);
			assertThat(probe.stops()).isEqualTo(1);
			probe.hit(1, "");
			assertThat(probe.stops()).isEqualTo(12);
			probe.hit(2, "");
			assertThat(probe.stops()).isEqualTo(once ? 12 : 23);
			probe.setInt(FastBreakpointProbeSource.EPOCH_FIELD, 2);
			probe.hit(3, "");
			assertThat(probe.stops()).isEqualTo(once ? 23 : 34);
		}
	}

	/** Checks that advancing the epoch during condition evaluation suppresses the old capture. */
	@Test
	@DisplayName("a once-mode hit superseded during its condition skips capture")
	void skipsCaptureOfSupersededHit() throws Exception {
		final Probe probe = compileCapture("{ if (i == 1) EPOCH = 2; return true; }",
			"{ STOPS.addAndGet(10); return i; }", true);
		probe.setEnabled(true);
		probe.hit(1, "");
		assertThat(probe.stops()).isZero();
		probe.hit(2, "");
		assertThat(probe.stops()).isEqualTo(11);
	}

	@Test
	@DisplayName("once mode lets exactly one of many concurrent true hits through per epoch")
	void exactlyOnceUnderContention() throws Exception {
		final Probe probe = compile("true", true);
		final Method hit = probe.hitMethod();
		for (int epoch = 1; epoch <= 3; epoch++) {
			probe.setInt(FastBreakpointProbeSource.EPOCH_FIELD, epoch);
			final CountDownLatch start = new CountDownLatch(1);
			final List<Thread> threads = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				final Thread thread = new Thread(() -> {
					try {
						start.await();
						for (int i = 0; i < 10_000; i++) {
							hit.invoke(null, i, "x");
						}
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				});
				thread.start();
				threads.add(thread);
			}
			start.countDown();
			for (Thread thread : threads) {
				thread.join();
			}
			assertThat(probe.stops()).as("stops after epoch %d", epoch).isEqualTo(epoch);
		}
	}

	/** Checks that the probe generator rejects an invalid ring capacity. */
	@Test
	@DisplayName("bufferSize must be a power of two")
	void rejectsNonPowerOfTwo() {
		assertThatThrownBy(() -> FastBreakpointProbeSource.generate("p.Probe", PARAMETERS, "true", null, true, 100,
			metadata("true")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static FastLogpointProbeSource.Metadata metadata(String condition) {
		return new FastLogpointProbeSource.Metadata("com.acme.Foo", "compute", "(I)I", 42, "", condition,
			System.currentTimeMillis(), "mcp-jdwp-java test, session 00000000");
	}

	/** Compiles and executes expressions and blocks with trailing line comments. */
	@Test
	void conditionAndCaptureAllowTrailingLineComments() throws Exception {
		final Probe probe = compileCapture("i == 7 // condition", "label // capture", true);
		probe.setEnabled(true);
		probe.hit(7, "text");
		assertThat(probe.stops()).isEqualTo(1);
		assertThat(probe.capture(7, "text")).isEqualTo("text");

		final Probe blockProbe = compileCapture("{ return i == 7; } // condition", "{ return label; } // capture", true);
		blockProbe.setEnabled(true);
		blockProbe.hit(7, "text");
		assertThat(blockProbe.stops()).isEqualTo(1);
		assertThat(blockProbe.capture(7, "text")).isEqualTo("text");
	}

	/** Checks that application locals remain accessible when their names match generated bindings. */
	@Test
	void generatedBindingsDoNotShadowApplicationLocals() throws Exception {
		final String className = "probe.test.McpFastBreakpoint_Collisions";
		final List<FastLogpointProbeSource.Parameter> parameters = List.of("ignored", "__mcpCaptured", "__mcpEpoch",
				"__mcpErrorEpoch", "__mcpFailure", "__mcpPassed", "__mcpFallthroughGuard", "ENABLED", "STOPS")
			.stream().map(name -> new FastLogpointProbeSource.Parameter(name, "int")).toList();
		final String condition = "{ return __mcpFallthroughGuard == 7 && ENABLED == 7; }";
		final String capture = "{ return ignored + __mcpCaptured + __mcpPassed + STOPS; }";
		final Probe probe = compileSource(className, FastBreakpointProbeSource.generate(
			className, parameters, condition, capture, true, 16, metadata(condition)));
		final Class<?>[] types = parameters.stream().map(parameter -> int.class).toArray(Class<?>[]::new);
		final Object[] values = parameters.stream().map(parameter -> 7).toArray();
		probe.setEnabled(true);
		probe.type().getMethod("hit", types).invoke(null, values);
		assertThat(probe.stops()).isEqualTo(1);
		final Method captureMethod = probe.type().getDeclaredMethod("__mcpCapture", types);
		captureMethod.setAccessible(true);
		assertThat(captureMethod.invoke(null, values)).isEqualTo("28");
	}

	/** Compiles the probe beside application classes named after the JDK types it uses. */
	@Test
	void generatedHelpersUseJdkTypesEvenWhenTheApplicationShadowsThem() throws Exception {
		final String className = "probe.test.McpFastBreakpoint_ShadowedTypes";
		final String shadowingTypes = "\nclass Character {}\nclass String {}\nclass Boolean {}\n"
			+ "class Throwable {}\nclass Object {}\nclass System {}\nclass Integer {}\nclass Class {}\nclass Thread {}\n";
		final String source = FastBreakpointProbeSource.generate(className, PARAMETERS, "true", "'x'", true, 16,
			metadata("true"));
		assertThat(compiler.compile(className, source + shadowingTypes)).containsKey(className.replace('.', '/'));
	}

	private Probe compile(String condition, boolean stopOnce) throws Exception {
		final Probe probe = compileDisabled(condition, stopOnce);
		probe.setEnabled(true);
		return probe;
	}

	/** Compiles and loads a capture-free probe with its initial kill switch set. */
	private Probe compileDisabled(String condition, boolean stopOnce) throws Exception {
		final String className = "probe.test." + FastBreakpointProbeSource.CLASS_PREFIX + System.nanoTime();
		final String source = FastBreakpointProbeSource.generate(className, PARAMETERS, condition, null, stopOnce, 16,
			metadata(condition));
		return compileSource(className, source);
	}

	/** Returns a compiled, initially disabled probe with the supplied capture and condition. */
	private Probe compileCapture(String condition, String capture, boolean stopOnce) throws Exception {
		final String className = "probe.test." + FastBreakpointProbeSource.CLASS_PREFIX + System.nanoTime();
		final FastLogpointProbeSource.Metadata metadata = new FastLogpointProbeSource.Metadata(
			"com.acme.Foo", "compute", "(I)I", 42, capture, condition, 0, "test");
		return compileSource(className, FastBreakpointProbeSource.generate(
			className, PARAMETERS, condition, capture, stopOnce, 16, metadata));
	}

	/** Compiles one probe class and returns its reflective handle, propagating compiler errors. */
	private Probe compileSource(String className, String source) throws Exception {
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

		/** Invokes the generated capture method with the supplied locals and returns its display text. */
		String capture(int i, String label) throws Exception {
			final Method capture = type.getDeclaredMethod("__mcpCapture", int.class, String.class);
			capture.setAccessible(true);
			return (String) capture.invoke(null, i, label);
		}

		Method hitMethod() throws Exception {
			return type.getMethod(FastLogpointProbeSource.HIT_METHOD, int.class, String.class);
		}

		void hit(int i, @Nullable String label) throws Exception {
			hitMethod().invoke(null, i, label);
		}

		@Nullable
		Object field(String name) throws Exception {
			return type.getField(name).get(null);
		}

		void setInt(String name, int value) throws Exception {
			type.getField(name).setInt(null, value);
		}

		int int32(String name) throws Exception {
			return ((AtomicInteger) type.getField(name).get(null)).get();
		}

		long counter(String name) throws Exception {
			return ((AtomicLong) type.getField(name).get(null)).get();
		}

		long stops() throws Exception {
			return counter(FastBreakpointProbeSource.STOPS_FIELD);
		}

		long trueHits() throws Exception {
			return counter(FastBreakpointProbeSource.TRUE_HITS_FIELD);
		}

		long errors() throws Exception {
			return counter(FastBreakpointProbeSource.ERRORS_FIELD);
		}

		int claimed() throws Exception {
			return int32(FastBreakpointProbeSource.CLAIMED_FIELD);
		}

		long seq() throws Exception {
			return counter(FastLogpointProbeSource.SEQ_FIELD);
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
			type.getField(FastLogpointProbeSource.ENABLED_FIELD).setBoolean(null, enabled);
		}
	}
}
