package one.edee.mcp.jdwp.evaluation;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

	@Test
	@DisplayName("bufferSize must be a power of two")
	void rejectsNonPowerOfTwo() {
		assertThatThrownBy(() -> FastBreakpointProbeSource.generate("p.Probe", PARAMETERS, "true", true, 100,
			metadata("true")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static FastLogpointProbeSource.Metadata metadata(String condition) {
		return new FastLogpointProbeSource.Metadata("com.acme.Foo", "compute", "(I)I", 42, "", condition,
			System.currentTimeMillis(), "mcp-jdwp-java test, session 00000000");
	}

	private Probe compile(String condition, boolean stopOnce) throws Exception {
		final Probe probe = compileDisabled(condition, stopOnce);
		probe.setEnabled(true);
		return probe;
	}

	private Probe compileDisabled(String condition, boolean stopOnce) throws Exception {
		final String className = "probe.test." + FastBreakpointProbeSource.CLASS_PREFIX + System.nanoTime();
		final String source = FastBreakpointProbeSource.generate(className, PARAMETERS, condition, stopOnce, 16,
			metadata(condition));
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
