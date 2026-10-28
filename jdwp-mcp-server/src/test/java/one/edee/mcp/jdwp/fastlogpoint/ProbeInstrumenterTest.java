package one.edee.mcp.jdwp.fastlogpoint;

import one.edee.mcp.jdwp.fastlogpoint.fixture.InstrumentationFixture;
import one.edee.mcp.jdwp.fastlogpoint.fixture.ProbeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the bytecode surgery behind fast logpoints on a real class file: locating a line's
 * insertion point and visible locals, inserting probe calls that the JVM verifier accepts, and
 * passing the right local slots. The instrumented fixture is loaded in an isolated class loader
 * (so it is verified) and run; {@link ProbeRecorder} stands in for the probe.
 */
@DisplayName("ProbeInstrumenter")
class ProbeInstrumenterTest {

	private static final String FIXTURE = InstrumentationFixture.class.getName();
	private static final String RECORDER = Type.getInternalName(ProbeRecorder.class);
	private static final String MIX = "mix";
	private static final String MIX_DESC = "(IJLjava/lang/String;)J";
	private static final Path FIXTURE_SOURCE =
		Path.of("src/test/java/one/edee/mcp/jdwp/fastlogpoint/fixture/InstrumentationFixture.java");

	private byte[] original;

	@BeforeEach
	void setUp() throws Exception {
		ProbeRecorder.CALLS.clear();
		try (InputStream in = InstrumentationFixture.class.getResourceAsStream("InstrumentationFixture.class")) {
			original = Objects.requireNonNull(in).readAllBytes();
		}
	}

	@Test
	@DisplayName("locate reports the locals in scope at the line, as the debugger sees them")
	void locatesVisibleLocals() throws Exception {
		final ProbeInstrumenter.InsertionPoint result = ProbeInstrumenter.locate(original, MIX, MIX_DESC, line("result"));
		assertThat(result.staticMethod()).isFalse();
		assertThat(result.visibleLocals()).extracting(ProbeInstrumenter.LocalSlot::name)
			.containsExactlyInAnyOrder("this", "count", "weight", "tag", "total", "described");
		assertThat(result.local("weight")).isNotNull()
			.satisfies(local -> assertThat(local.descriptor()).isEqualTo("J"));

		final ProbeInstrumenter.InsertionPoint loop = ProbeInstrumenter.locate(original, MIX, MIX_DESC, line("loop"));
		assertThat(loop.visibleLocals()).extracting(ProbeInstrumenter.LocalSlot::name)
			.containsExactlyInAnyOrder("this", "count", "weight", "tag", "total", "i");
		assertThat(loop.local("described")).isNull();
	}

	@Test
	@DisplayName("the probe receives this and the locals; the method's own result is unchanged")
	void passesThisAndLocals() throws Exception {
		final int line = line("result");
		final ProbeInstrumenter.InsertionPoint point = ProbeInstrumenter.locate(original, MIX, MIX_DESC, line);
		final List<ProbeInstrumenter.ArgumentLoad> loads = new ArrayList<>();
		loads.add(new ProbeInstrumenter.ArgumentLoad(Opcodes.ALOAD, 0));
		for (String name : List.of("count", "weight", "tag", "total", "described")) {
			loads.add(load(point, name));
		}
		final byte[] instrumented = ProbeInstrumenter.instrument(original, List.of(new ProbeInstrumenter.ProbeSite(
			1, MIX, MIX_DESC, line, RECORDER, "result",
			"(Ljava/lang/Object;IJLjava/lang/String;JLjava/lang/String;)V", loads)));

		final Object fixture = load(instrumented).getConstructor(String.class).newInstance("n");
		final Object result = fixture.getClass().getMethod(MIX, int.class, long.class, String.class)
			.invoke(fixture, 3, 10L, "t");

		assertThat(result).isEqualTo(16L);
		assertThat(ProbeRecorder.CALLS).containsExactly(List.of("result", fixture, 3, 10L, "t", 13L, "t/n"));
	}

	@Test
	@DisplayName("a probe on a loop-body line runs on every iteration")
	void instrumentsLoopBody() throws Exception {
		final int line = line("loop");
		final ProbeInstrumenter.InsertionPoint point = ProbeInstrumenter.locate(original, MIX, MIX_DESC, line);
		final byte[] instrumented = ProbeInstrumenter.instrument(original, List.of(new ProbeInstrumenter.ProbeSite(
			1, MIX, MIX_DESC, line, RECORDER, "loop", "(IJI)V",
			List.of(load(point, "count"), load(point, "total"), load(point, "i")))));

		final Object fixture = load(instrumented).getConstructor(String.class).newInstance("n");
		fixture.getClass().getMethod(MIX, int.class, long.class, String.class).invoke(fixture, 3, 10L, "t");

		assertThat(ProbeRecorder.CALLS).containsExactly(
			List.of("loop", 3, 10L, 0), List.of("loop", 3, 10L, 1), List.of("loop", 3, 11L, 2));
	}

	@Test
	@DisplayName("two probes on one line run in creation order")
	void sameLineSitesKeepCreationOrder() throws Exception {
		final int line = line("loop");
		final ProbeInstrumenter.InsertionPoint point = ProbeInstrumenter.locate(original, MIX, MIX_DESC, line);
		final ProbeInstrumenter.ProbeSite first = new ProbeInstrumenter.ProbeSite(4, MIX, MIX_DESC, line, RECORDER,
			"second", "(J)V", List.of(load(point, "total")));
		final ProbeInstrumenter.ProbeSite second = new ProbeInstrumenter.ProbeSite(9, MIX, MIX_DESC, line, RECORDER,
			"loop", "(IJI)V", List.of(load(point, "count"), load(point, "total"), load(point, "i")));
		final byte[] instrumented = ProbeInstrumenter.instrument(original, List.of(second, first));

		final Object fixture = load(instrumented).getConstructor(String.class).newInstance("n");
		fixture.getClass().getMethod(MIX, int.class, long.class, String.class).invoke(fixture, 1, 5L, "t");

		assertThat(ProbeRecorder.CALLS).containsExactly(List.of("second", 5L), List.of("loop", 1, 5L, 0));
	}

	@Test
	@DisplayName("static methods pass wide primitives and arrays from their own slots")
	void instrumentsStaticMethod() throws Exception {
		final int line = line("static");
		final ProbeInstrumenter.InsertionPoint point =
			ProbeInstrumenter.locate(original, "scale", "(D[I)D", line);
		assertThat(point.staticMethod()).isTrue();
		final byte[] instrumented = ProbeInstrumenter.instrument(original, List.of(new ProbeInstrumenter.ProbeSite(
			1, "scale", "(D[I)D", line, RECORDER, "scale", "(D[ID)V",
			List.of(load(point, "factor"), load(point, "values"), load(point, "sum")))));

		final Method scale = load(instrumented).getMethod("scale", double.class, int[].class);
		assertThat(scale.invoke(null, 2.0, new int[]{1, 2})).isEqualTo(6.0);
		assertThat(ProbeRecorder.CALLS).containsExactly(List.of("static", 2.0, 2, 6.0));
	}

	@Test
	@DisplayName("only the instrumented method's code changes")
	void leavesOtherMethodsUntouched() throws Exception {
		final int line = line("loop");
		final ProbeInstrumenter.InsertionPoint point = ProbeInstrumenter.locate(original, MIX, MIX_DESC, line);
		final byte[] instrumented = ProbeInstrumenter.instrument(original, List.of(new ProbeInstrumenter.ProbeSite(
			1, MIX, MIX_DESC, line, RECORDER, "second", "(J)V", List.of(load(point, "total")))));

		final Map<String, byte[]> before = ClassFileMethods.codeByMethod(original);
		final Map<String, byte[]> after = ClassFileMethods.codeByMethod(instrumented);
		assertThat(after).containsOnlyKeys(before.keySet());
		assertThat(after.get(MIX + MIX_DESC)).isNotEqualTo(before.get(MIX + MIX_DESC));
		assertThat(after.get("scale(D[I)D")).isEqualTo(before.get("scale(D[I)D"));
		assertThat(after.get("<init>(Ljava/lang/String;)V")).isEqualTo(before.get("<init>(Ljava/lang/String;)V"));
	}

	@Test
	@DisplayName("a missing method or a line without code is refused")
	void rejectsUnknownTargets() {
		assertThatThrownBy(() -> ProbeInstrumenter.locate(original, "absent", "()V", 1))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("absent()V not found");
		assertThatThrownBy(() -> ProbeInstrumenter.locate(original, MIX, MIX_DESC, 1))
			.isInstanceOf(FastLogpointException.class).hasMessageContaining("Line 1 has no code");
	}

	@Test
	@DisplayName("methodDescriptor finds a static method's descriptor")
	void readsStaticMethodDescriptor() throws Exception {
		assertThat(ProbeInstrumenter.methodDescriptor(original, "scale")).isEqualTo("(D[I)D");
		assertThatThrownBy(() -> ProbeInstrumenter.methodDescriptor(original, MIX))
			.isInstanceOf(FastLogpointException.class);
	}

	private static ProbeInstrumenter.ArgumentLoad load(ProbeInstrumenter.InsertionPoint point, String name) {
		final ProbeInstrumenter.LocalSlot local = Objects.requireNonNull(point.local(name), name);
		return new ProbeInstrumenter.ArgumentLoad(ProbeInstrumenter.loadOpcode(local.descriptor()), local.slot());
	}

	private static int line(String mark) throws Exception {
		final List<String> lines = Files.readAllLines(FIXTURE_SOURCE);
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).contains("// MARK: " + mark)) {
				return i + 1;
			}
		}
		throw new IllegalStateException(mark);
	}

	/**
	 * Defines the fixture from {@code classFile} in a fresh loader — non-bootstrap classes are
	 * verified, so a broken stack map or argument load fails here — delegating everything else
	 * (notably {@link ProbeRecorder}) to the test's loader.
	 */
	private static Class<?> load(byte[] classFile) throws ClassNotFoundException {
		final ClassLoader isolated = new ClassLoader(ProbeInstrumenterTest.class.getClassLoader()) {
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (!name.equals(FIXTURE)) {
					return super.loadClass(name, resolve);
				}
				synchronized (getClassLoadingLock(name)) {
					final Class<?> loaded = findLoadedClass(name);
					return loaded != null ? loaded : defineClass(name, classFile, 0, classFile.length);
				}
			}
		};
		return isolated.loadClass(FIXTURE);
	}
}
