package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiClassDefinitionException;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Covers the define-phase package fallback in {@link JdiExpressionEvaluator#evaluate}: when the
 * wrapper cannot be DEFINED into a non-public {@code this}'s own package (sealed package / module
 * encapsulation / restrictive classloader, surfaced as {@link JdiClassDefinitionException}), the
 * evaluator retries once in the default isolated package. A define-phase failure means the user
 * expression never ran, so the retry cannot double-execute a side-effecting expression — which is
 * why only {@link JdiClassDefinitionException} (not a generic user-code failure) triggers it.
 */
class JdiExpressionEvaluatorPackageFallbackTest {

	private InMemoryJavaCompiler compiler;
	private JdiExpressionEvaluator evaluator;
	private StackFrame frame;

	@BeforeEach
	void setUp() throws Exception {
		compiler = mock(InMemoryJavaCompiler.class);
		final JDIConnectionService jdiService = mock(JDIConnectionService.class);
		evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(),
			LocalProjectClasspathProviderTestSupport.noOpProvider());
		// Compiler is mocked — echo back the requested class name so the bytecode lookup hits.
		when(compiler.compile(anyString(), anyString()))
			.thenAnswer(inv -> Map.of(inv.getArgument(0, String.class), new byte[]{1, 2, 3}));
	}

	/**
	 * Wires a minimal frame whose {@code this} runtime type is {@code com.app.Foo}.
	 *
	 * @param publicThis when false, {@code this} is non-public so the evaluator targets
	 *                   {@code com.app}; when true, {@code this} is public so it targets the
	 *                   default package (no fallback possible).
	 */
	private void wireFrame(boolean publicThis) throws Exception {
		frame = mock(StackFrame.class);
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(1L);
		when(frame.thread()).thenReturn(thread);
		when(frame.virtualMachine()).thenReturn(mock(VirtualMachine.class));
		when(frame.visibleVariables()).thenReturn(List.of());
		// The rejected defineClass invocation invalidates the frame; the retry reads it afresh.
		final Location location = mock(Location.class);
		when(frame.location()).thenReturn(location);
		when(thread.frames()).thenReturn(List.of(frame));

		final ObjectReference thisRef = mock(ObjectReference.class);
		final ClassType thisType = mock(ClassType.class);
		when(thisType.name()).thenReturn("com.app.Foo");
		when(thisType.isPublic()).thenReturn(publicThis);
		when(thisType.allFields()).thenReturn(List.of());
		when(thisType.classLoader()).thenReturn(mock(ClassLoaderReference.class));
		when(thisRef.referenceType()).thenReturn(thisType);
		when(frame.thisObject()).thenReturn(thisRef);
	}

	@Test
	@DisplayName("define failure in the target package retries once in the default package")
	void shouldRetryInDefaultPackageWhenDefineFailsInTargetPackage() throws Exception {
		wireFrame(false); // non-public this → wrapper targets com.app
		final Value expected = mock(Value.class);

		try (MockedStatic<RemoteCodeExecutor> mocked = mockStatic(RemoteCodeExecutor.class)) {
			mocked.when(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()))
				.thenAnswer(inv -> {
					final String className = inv.getArgument(3, String.class);
					if (className.startsWith("com.app.")) {
						throw new JdiClassDefinitionException("Prohibited package name: com.app", new RuntimeException());
					}
					return expected;
				});

			final Value result = evaluator.evaluate(frame, "1 + 1", Map.of());

			assertThat(result).isSameAs(expected);
			// Two binds: the rejected com.app attempt, then the successful default-package retry.
			mocked.verify(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()),
				times(2));
		}
	}

	@Test
	@DisplayName("a frame invalidated by a resume elsewhere before the invocation is read afresh")
	void shouldReadAnInvalidatedFrameAfresh() throws Exception {
		wireFrame(true);
		final StackFrame stale = frame;
		final ThreadReference thread = stale.thread();
		final Location location = stale.location();
		final ObjectReference thisRef = stale.thisObject();
		final VirtualMachine vm = stale.virtualMachine();
		final StackFrame fresh = mock(StackFrame.class);
		when(fresh.thread()).thenReturn(thread);
		when(fresh.location()).thenReturn(location);
		when(fresh.virtualMachine()).thenReturn(vm);
		when(fresh.visibleVariables()).thenReturn(List.of());
		when(fresh.thisObject()).thenReturn(thisRef);
		when(stale.thisObject()).thenThrow(new InvalidStackFrameException("Thread has been resumed"));
		when(thread.frames()).thenReturn(List.of(fresh));
		final Value expected = mock(Value.class);

		try (MockedStatic<RemoteCodeExecutor> mocked = mockStatic(RemoteCodeExecutor.class)) {
			mocked.when(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()))
				.thenReturn(expected);

			assertThat(evaluator.evaluate(stale, "1 + 1", Map.of())).isSameAs(expected);
		}
	}

	@Test
	@DisplayName("define failure already in the default package is NOT retried")
	void shouldNotRetryWhenDefineFailsInDefaultPackage() throws Exception {
		wireFrame(true); // public this → wrapper already targets the default package

		try (MockedStatic<RemoteCodeExecutor> mocked = mockStatic(RemoteCodeExecutor.class)) {
			mocked.when(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()))
				.thenThrow(new JdiClassDefinitionException("defineClass blocked", new RuntimeException()));

			assertThatThrownBy(() -> evaluator.evaluate(frame, "1 + 1", Map.of()))
				.isInstanceOf(JdiEvaluationException.class);

			mocked.verify(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()),
				times(1));
		}
	}

	@Test
	@DisplayName("a user-code failure (not a define failure) is NOT retried, even in the target package")
	void shouldNotRetryOnUserCodeFailure() throws Exception {
		wireFrame(false); // non-public this → target package, so a retry WOULD be possible if mis-triggered

		try (MockedStatic<RemoteCodeExecutor> mocked = mockStatic(RemoteCodeExecutor.class)) {
			// Plain JdiEvaluationException models the user's expression throwing — must not retry,
			// otherwise a side-effecting expression would run twice.
			mocked.when(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()))
				.thenThrow(new JdiEvaluationException("Target VM threw exception: java.lang.NullPointerException"));

			assertThatThrownBy(() -> evaluator.evaluate(frame, "this.x.length()", Map.of()))
				.isInstanceOf(JdiEvaluationException.class);

			mocked.verify(() -> RemoteCodeExecutor.execute(any(), any(), any(), anyString(), any(), anyString(), anyList()),
				times(1));
		}
	}
}
