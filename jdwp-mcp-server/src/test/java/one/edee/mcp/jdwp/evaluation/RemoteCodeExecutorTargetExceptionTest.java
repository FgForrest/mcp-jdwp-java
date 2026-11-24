package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.Field;
import com.sun.jdi.InvocationException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An exception thrown by evaluated code is reported with its message, read as a field: without it
 * an {@code InaccessibleObjectException} does not say which package is closed.
 */
@DisplayName("RemoteCodeExecutor reports the message of a target exception")
class RemoteCodeExecutorTargetExceptionTest {

	private static final String TYPE = "java.lang.IllegalStateException";
	private static final String WITHOUT_MESSAGE = "Target VM threw exception: " + TYPE;

	@Test
	@DisplayName("the message follows the type")
	void shouldReportMessage() {
		assertThat(reported("not open")).isEqualTo(WITHOUT_MESSAGE + ": not open");
	}

	@Test
	@DisplayName("no message, or a blank one, leaves the type alone")
	void shouldReportTypeOnlyWithoutMessage() {
		assertThat(reported(null)).isEqualTo(WITHOUT_MESSAGE);
		assertThat(reported(" ")).isEqualTo(WITHOUT_MESSAGE);
	}

	@Test
	@DisplayName("a message of several lines stays on one")
	void shouldKeepMessageOnOneLine() {
		assertThat(reported("first\r\n  second\nthird")).isEqualTo(WITHOUT_MESSAGE + ": first second third");
	}

	@Test
	@DisplayName("a long message is cut")
	void shouldCutLongMessage() {
		assertThat(reported("x".repeat(5_000))).isEqualTo(WITHOUT_MESSAGE + ": " + "x".repeat(300) + "…");
	}

	@Test
	@DisplayName("a message that cannot be read leaves the type alone")
	void shouldReportTypeOnlyWhenMessageIsUnreadable() {
		final ObjectReference exception = exception("gone");
		final Field field = exception.referenceType().allFields().get(0);
		when(exception.getValue(field)).thenThrow(new ObjectCollectedException());

		assertThat(RemoteCodeExecutor.targetException(new InvocationException(exception)).getMessage())
			.isEqualTo(WITHOUT_MESSAGE);
	}

	private static String reported(@Nullable String message) {
		return RemoteCodeExecutor.targetException(new InvocationException(exception(message))).getMessage();
	}

	/** An exception mirror whose {@code Throwable.detailMessage} holds {@code message}. */
	private static ObjectReference exception(@Nullable String message) {
		final ObjectReference exception = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final ReferenceType throwable = mock(ReferenceType.class);
		final Field detailMessage = mock(Field.class);
		when(exception.type()).thenReturn(type);
		when(exception.referenceType()).thenReturn(type);
		when(type.name()).thenReturn(TYPE);
		when(type.allFields()).thenReturn(List.of(detailMessage));
		when(detailMessage.name()).thenReturn("detailMessage");
		when(detailMessage.declaringType()).thenReturn(throwable);
		when(throwable.name()).thenReturn("java.lang.Throwable");
		if (message != null) {
			final StringReference string = mock(StringReference.class);
			when(string.value()).thenReturn(message);
			when(exception.getValue(detailMessage)).thenReturn(string);
		}
		return exception;
	}
}
