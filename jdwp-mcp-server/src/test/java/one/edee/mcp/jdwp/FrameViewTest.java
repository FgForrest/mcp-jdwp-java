package one.edee.mcp.jdwp;

import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FrameView}: only the leading frames of probe classes are hidden, logical index {@code i} is
 * physical index {@code hidden + i}, a fast breakpoint's stop is told apart from a thread suspended
 * elsewhere inside a probe, and a stack of nothing but probe frames hides nothing.
 */
@DisplayName("FrameView — logical frames without the leading probe frames")
class FrameViewTest {

	private static final String PROBE = "com.example.McpFastBreakpoint_0123";

	@Test
	@DisplayName("a fast breakpoint's stop hides stop() and hit(); logical frame 0 is the user's frame")
	void hidesLeadingProbeFrames() throws Exception {
		final ThreadReference thread = threadWith(
			frame(PROBE, "stop"), frame(PROBE, "hit"), frame("com.example.BoxOffice", "trySell"),
			frame("com.example.BoxOffice", "run"));

		final FrameView view = FrameView.of(thread);

		assertThat(view.hidden()).isEqualTo(2);
		assertThat(view.count()).isEqualTo(2);
		assertThat(view.isFastStop()).isTrue();
		assertThat(view.probeType()).isNotNull();
		assertThat(view.frame(0).location().method().name()).isEqualTo("trySell");
		assertThat(view.frame(1).location().method().name()).isEqualTo("run");
		assertThat(view.frames()).extracting(f -> f.location().method().name()).containsExactly("trySell", "run");
		assertThat(view.hiddenNote()).contains("2 frame(s) of fast-breakpoint probe " + PROBE + " hidden");
		assertThatThrownBy(() -> view.frame(-1)).isInstanceOf(IndexOutOfBoundsException.class);
	}

	@Test
	@DisplayName("probe frames below a user frame stay visible — only the leading ones are hidden")
	void keepsDeeperProbeFrames() throws Exception {
		final ThreadReference thread = threadWith(
			frame("com.example.Seat", "isFree"), frame(PROBE, "__mcpCondition"), frame(PROBE, "hit"),
			frame("com.example.BoxOffice", "trySell"));

		final FrameView view = FrameView.of(thread);

		assertThat(view.hidden()).isZero();
		assertThat(view.count()).isEqualTo(4);
		assertThat(view.isFastStop()).isFalse();
		assertThat(view.hiddenNote()).isEmpty();
	}

	@Test
	@DisplayName("a thread suspended inside hit() hides the probe but is not a fast breakpoint's stop")
	void suspendedInsideProbeIsNotAFastStop() throws Exception {
		final ThreadReference thread = threadWith(frame(PROBE, "hit"), frame("com.example.BoxOffice", "trySell"));

		final FrameView view = FrameView.of(thread);

		assertThat(view.hidden()).isEqualTo(1);
		assertThat(view.isFastStop()).isFalse();
		assertThat(view.hiddenNote()).contains("suspended inside the probe");
	}

	@Test
	@DisplayName("a stack of nothing but probe frames hides nothing")
	void allProbeFramesHideNothing() throws Exception {
		final ThreadReference thread = threadWith(frame(PROBE, "stop"), frame(PROBE, "hit"));

		final FrameView view = FrameView.of(thread);

		assertThat(view.hidden()).isZero();
		assertThat(view.count()).isEqualTo(2);
	}

	private static ThreadReference threadWith(StackFrame... frames) throws Exception {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.frameCount()).thenReturn(frames.length);
		when(thread.frame(anyInt())).thenAnswer(invocation -> frames[(int) invocation.getArgument(0)]);
		when(thread.frames()).thenReturn(List.of(frames));
		when(thread.frames(anyInt(), anyInt())).thenAnswer(invocation -> {
			final int start = invocation.getArgument(0);
			final int length = invocation.getArgument(1);
			return new ArrayList<>(List.of(frames).subList(start, start + length));
		});
		return thread;
	}

	private static StackFrame frame(String className, String methodName) {
		final ReferenceType type = mock(ReferenceType.class);
		when(type.name()).thenReturn(className);
		final Method method = mock(Method.class);
		when(method.name()).thenReturn(methodName);
		final Location location = mock(Location.class);
		when(location.declaringType()).thenReturn(type);
		when(location.method()).thenReturn(method);
		final StackFrame frame = mock(StackFrame.class);
		when(frame.location()).thenReturn(location);
		return frame;
	}
}
