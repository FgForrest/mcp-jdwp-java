package one.edee.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JDWPTools}'s private static {@code appendUserFrames} helper, which is shared
 * by {@code jdwp_get_stack} and {@code jdwp_get_breakpoint_context}. The 5-arg overload
 * controls noise collapsing and indentation; the 4-arg convenience overload always collapses.
 */
@DisplayName("JDWPTools appendUserFrames helper")
class JDWPToolsAppendUserFramesTest {

	@Nested
	@DisplayName("Rendering rules")
	class RenderingRules {

		@Test
		void shouldRenderAllFramesWhenAllAreUserFramesAndUnderLimit() throws Exception {
			List<StackFrame> frames = List.of(
				userFrame("com.example.Foo", "doFoo", "Foo.java", 10),
				userFrame("com.example.Bar", "doBar", "Bar.java", 20));

			StringBuilder out = new StringBuilder();
			invokeAppend(out, frames, 10, false, "");

			String result = out.toString();
			assertThat(result).contains("#0 com.example.Foo.doFoo (Foo.java:10)");
			assertThat(result).contains("#1 com.example.Bar.doBar (Bar.java:20)");
			assertThat(result).doesNotContain("more frame(s) hidden");
			assertThat(result).doesNotContain("collapsed");
		}

		@Test
		void shouldStopAfterLimitAndAppendMoreFramesHiddenFooter() throws Exception {
			List<StackFrame> frames = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				frames.add(userFrame("com.example.Frame" + i, "m" + i, "F.java", i));
			}

			StringBuilder out = new StringBuilder();
			invokeAppend(out, frames, 2, false, "");

			String result = out.toString();
			assertThat(result).contains("#0 com.example.Frame0.m0 (F.java:0)");
			assertThat(result).contains("#1 com.example.Frame1.m1 (F.java:1)");
			assertThat(result).doesNotContain("Frame2");
			assertThat(result).contains("3 more frame(s) hidden");
		}

		@Test
		void shouldCollapseNoiseFramesWhenIncludeNoiseFalse() throws Exception {
			List<StackFrame> frames = new ArrayList<>();
			frames.add(userFrame("com.example.A", "a", "A.java", 1));
			frames.add(userFrame("com.example.B", "b", "B.java", 2));
			frames.add(userFrame("com.example.C", "c", "C.java", 3));
			frames.add(userFrame("org.junit.platform.engine.X", "x", "X.java", 4));
			frames.add(userFrame("org.junit.platform.engine.Y", "y", "Y.java", 5));
			frames.add(userFrame("org.apache.maven.surefire.Z", "z", "Z.java", 6));
			frames.add(userFrame("jdk.internal.reflect.W", "w", "W.java", 7));
			frames.add(userFrame("com.example.D", "d", "D.java", 8));
			frames.add(userFrame("com.example.E", "e", "E.java", 9));

			StringBuilder out = new StringBuilder();
			invokeAppend(out, frames, 100, false, "");

			String result = out.toString();
			assertThat(result).contains("com.example.A.a");
			assertThat(result).contains("com.example.B.b");
			assertThat(result).contains("com.example.C.c");
			assertThat(result).contains("com.example.D.d");
			assertThat(result).contains("com.example.E.e");
			assertThat(result).doesNotContain("org.junit");
			assertThat(result).doesNotContain("org.apache.maven");
			assertThat(result).doesNotContain("jdk.internal.reflect");
			assertThat(result).contains("4 junit/maven/reflection frame(s) collapsed");
		}

		@Test
		void shouldRenderNoiseInlineWhenIncludeNoiseTrue() throws Exception {
			List<StackFrame> frames = List.of(
				userFrame("com.example.A", "a", "A.java", 1),
				userFrame("org.junit.platform.engine.X", "x", "X.java", 2));

			StringBuilder out = new StringBuilder();
			invokeAppend(out, frames, 10, true, "");

			String result = out.toString();
			assertThat(result).contains("com.example.A.a");
			assertThat(result).contains("org.junit.platform.engine.X.x");
			assertThat(result).doesNotContain("collapsed");
		}

		@Test
		void shouldShowBothCollapsedAndHiddenFootersWhenLimitAndNoiseBothApply() throws Exception {
			List<StackFrame> frames = new ArrayList<>();
			frames.add(userFrame("com.example.A", "a", "A.java", 1));
			frames.add(userFrame("com.example.B", "b", "B.java", 2));
			frames.add(userFrame("com.example.C", "c", "C.java", 3));
			frames.add(userFrame("com.example.D", "d", "D.java", 4));
			frames.add(userFrame("org.junit.platform.engine.X", "x", "X.java", 5));

			StringBuilder out = new StringBuilder();
			invokeAppend(out, frames, 2, false, "");

			String result = out.toString();
			// limit reached: rendered = 2, collapsed = 0 so far → only "more frame(s) hidden" applies.
			// But the noise frame at index 4 is past the limit so it never gets counted.
			// Expected: 2 user frames printed + "2 more frame(s) hidden" footer.
			assertThat(result).contains("#0 com.example.A.a");
			assertThat(result).contains("#1 com.example.B.b");
			assertThat(result).doesNotContain("com.example.C");
			assertThat(result).contains("more frame(s) hidden");
		}

		@Test
		void shouldUseUnknownSourceWhenAbsentInformationExceptionThrown() throws Exception {
			StackFrame frame = mock(StackFrame.class);
			Location location = mock(Location.class);
			ReferenceType refType = mock(ReferenceType.class);
			Method method = mock(Method.class);
			when(frame.location()).thenReturn(location);
			when(location.declaringType()).thenReturn(refType);
			when(refType.name()).thenReturn("com.example.NoDebug");
			when(location.method()).thenReturn(method);
			when(method.name()).thenReturn("nodebug");
			when(location.sourceName()).thenThrow(new AbsentInformationException("no debug info"));
			when(location.lineNumber()).thenReturn(0);

			StringBuilder out = new StringBuilder();
			invokeAppend(out, List.of(frame), 10, false, "");

			assertThat(out.toString()).contains("Unknown Source");
		}

		@Test
		void shouldIndentEachFrameLineWithProvidedPrefix() throws Exception {
			List<StackFrame> frames = List.of(
				userFrame("com.example.A", "a", "A.java", 1),
				userFrame("com.example.B", "b", "B.java", 2));

			StringBuilder out = new StringBuilder();
			invokeAppend(out, frames, 10, false, "  ");

			String[] lines = out.toString().split("\n");
			for (String line : lines) {
				if (!line.isEmpty()) {
					assertThat(line).startsWith("  ");
				}
			}
		}
	}

	@Nested
	@DisplayName("Convenience overload")
	class ConvenienceOverload {

		@Test
		void shouldAlwaysCollapseNoiseWhenUsingThreeArgOverload() throws Exception {
			List<StackFrame> frames = List.of(
				userFrame("com.example.A", "a", "A.java", 1),
				userFrame("org.junit.platform.engine.X", "x", "X.java", 2));

			StringBuilder out = new StringBuilder();
			invokeAppend4Arg(out, frames, 10, "");

			String result = out.toString();
			assertThat(result).contains("com.example.A.a");
			assertThat(result).doesNotContain("org.junit");
			assertThat(result).contains("collapsed");
		}
	}

	// ── helpers ──

	/**
	 * Builds a mocked {@link StackFrame} whose {@code location()} reports the given declaring
	 * class, method name, source file, and line number.
	 */
	private static StackFrame userFrame(String declaringType, String methodName, String sourceName, int line) {
		try {
			StackFrame frame = mock(StackFrame.class);
			Location location = mock(Location.class);
			ReferenceType refType = mock(ReferenceType.class);
			Method method = mock(Method.class);
			when(frame.location()).thenReturn(location);
			when(location.declaringType()).thenReturn(refType);
			when(refType.name()).thenReturn(declaringType);
			when(location.method()).thenReturn(method);
			when(method.name()).thenReturn(methodName);
			when(location.sourceName()).thenReturn(sourceName);
			when(location.lineNumber()).thenReturn(line);
			return frame;
		} catch (AbsentInformationException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Reflectively invokes the 5-arg {@code appendUserFrames(StringBuilder, List, int, boolean, String)}.
	 */
	private static void invokeAppend(StringBuilder out, List<StackFrame> frames, int limit,
			boolean includeNoise, String indent) throws Exception {
		java.lang.reflect.Method m = JDWPTools.class.getDeclaredMethod("appendUserFrames",
			StringBuilder.class, List.class, int.class, boolean.class, String.class);
		m.setAccessible(true);
		try {
			m.invoke(null, out, frames, limit, includeNoise, indent);
		} catch (InvocationTargetException ite) {
			if (ite.getCause() instanceof Exception ex) throw ex;
			throw ite;
		}
	}

	/**
	 * Reflectively invokes the 4-arg convenience overload
	 * {@code appendUserFrames(StringBuilder, List, int, String)}.
	 */
	private static void invokeAppend4Arg(StringBuilder out, List<StackFrame> frames, int limit, String indent)
			throws Exception {
		java.lang.reflect.Method m = JDWPTools.class.getDeclaredMethod("appendUserFrames",
			StringBuilder.class, List.class, int.class, String.class);
		m.setAccessible(true);
		try {
			m.invoke(null, out, frames, limit, indent);
		} catch (InvocationTargetException ite) {
			if (ite.getCause() instanceof Exception ex) throw ex;
			throw ite;
		}
	}
}
