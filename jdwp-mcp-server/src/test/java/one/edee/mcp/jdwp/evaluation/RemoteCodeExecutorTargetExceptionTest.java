package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.Field;
import com.sun.jdi.InvocationException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.VMDisconnectedException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
	private static final String INITIALIZER_ERROR = "java.lang.ExceptionInInitializerError";
	private static final String INITIALIZER_REPORT = "Target VM threw exception: " + INITIALIZER_ERROR;

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
		assertThat(reported("\u0085\u2028\u2029")).isEqualTo(WITHOUT_MESSAGE);
	}

	@Test
	@DisplayName("a message of several lines stays on one")
	void shouldKeepMessageOnOneLine() {
		assertThat(reported("first\r\n  second\nthird\u0085fourth\u2028fifth\u2029sixth"))
			.isEqualTo(WITHOUT_MESSAGE + ": first second third fourth fifth sixth");
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

	/** Collection of the throwable mirror preserves the invocation failure. */
	@Test
	void shouldPreserveInvocationFailureWhenThrowableWasCollected() {
		final ObjectReference exception = exception("gone");
		when(exception.type()).thenThrow(new ObjectCollectedException());
		final InvocationException failure = new InvocationException(exception);

		assertThat(RemoteCodeExecutor.targetException(failure))
			.hasMessage("Target VM threw exception: unknown")
			.hasCauseReference(failure);
	}

	/** A disconnect during the type read still reaches the caller. */
	@Test
	void shouldPropagateVmDisconnectWhenReadingType() {
		final ObjectReference exception = mock(ObjectReference.class);
		final VMDisconnectedException disconnected = new VMDisconnectedException();
		when(exception.type()).thenThrow(disconnected);

		assertThatThrownBy(() -> RemoteCodeExecutor.targetException(new InvocationException(exception)))
			.isSameAs(disconnected);
	}

	/** Both JDK layouts expose the cause as a field, with the usual message limits. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void reportsInitializerCause(boolean legacyField) {
		final ObjectReference cause = exception("boom\n" + "x".repeat(400));
		final ObjectReference error = initializerError(legacyField, cause);

		assertThat(RemoteCodeExecutor.targetException(new InvocationException(error)))
			.hasMessage(INITIALIZER_REPORT + " (cause: " + TYPE + ": boom " + "x".repeat(295) + "…)");
	}

	/** Null, self-reference and an unreadable field all leave the initializer error alone. */
	@Test
	void omitsAbsentOrUnreadableCause() {
		final ObjectReference error = initializerError(false, null);
		final Field cause = error.referenceType().allFields().get(1);
		assertThat(RemoteCodeExecutor.targetException(new InvocationException(error))).hasMessage(INITIALIZER_REPORT);

		when(error.getValue(cause)).thenReturn(error);
		assertThat(RemoteCodeExecutor.targetException(new InvocationException(error))).hasMessage(INITIALIZER_REPORT);

		when(error.getValue(cause)).thenThrow(new IllegalArgumentException("unreadable field"));
		assertThat(RemoteCodeExecutor.targetException(new InvocationException(error))).hasMessage(INITIALIZER_REPORT);
	}

	/** A collected cause leaves the outer error report intact. */
	@Test
	void omitsCollectedCause() {
		final ObjectReference cause = exception("gone");
		when(cause.type()).thenThrow(new ObjectCollectedException());
		final ObjectReference error = initializerError(false, cause);

		assertThat(RemoteCodeExecutor.targetException(new InvocationException(error))).hasMessage(INITIALIZER_REPORT);
	}

	/** Other Errors are reported once and an initializer error's cause is only read one level deep. */
	@Test
	void reportsOnlyOneLevelAndOnlyForInitializerErrors() {
		final ObjectReference nested = initializerError(false, exception("hidden"));
		final ObjectReference error = initializerError(false, nested);
		assertThat(RemoteCodeExecutor.targetException(new InvocationException(error)))
			.hasMessage(INITIALIZER_REPORT + " (cause: " + INITIALIZER_ERROR + ")");

		final ObjectReference unwrapped = initializerError(false, exception("hidden"));
		when(unwrapped.referenceType().name()).thenReturn("java.lang.AssertionError");
		assertThat(RemoteCodeExecutor.targetException(new InvocationException(unwrapped)))
			.hasMessage("Target VM threw exception: java.lang.AssertionError");
	}

	/** Builds a mirror using the current or legacy initializer-error layout. */
	private static ObjectReference initializerError(boolean legacyField, @Nullable ObjectReference cause) {
		final ObjectReference error = exception(null);
		final ReferenceType type = error.referenceType();
		final Field detail = type.allFields().get(0);
		final ReferenceType throwable = detail.declaringType();
		final Field causeField = mock(Field.class);
		when(type.name()).thenReturn(INITIALIZER_ERROR);
		when(causeField.name()).thenReturn(legacyField ? "exception" : "cause");
		when(causeField.declaringType()).thenReturn(legacyField ? type : throwable);
		if (legacyField) {
			final Field unsetCause = mock(Field.class);
			when(unsetCause.name()).thenReturn("cause");
			when(unsetCause.declaringType()).thenReturn(throwable);
			when(error.getValue(unsetCause)).thenReturn(error);
			when(type.allFields()).thenReturn(List.of(detail, unsetCause, causeField));
		} else {
			when(type.allFields()).thenReturn(List.of(detail, causeField));
		}
		when(error.getValue(causeField)).thenReturn(cause);
		return error;
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
