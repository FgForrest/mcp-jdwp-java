package one.edee.mcp.jdwp;

import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pieces a stop header and a field event are described with (#50): the exception's message,
 * read as a field without invoking anything, the values of a field access, and the access site.
 */
@DisplayName("JdiEventListener — stop details")
class JdiEventListenerStopDetailTest {

	@Test
	@DisplayName("the message is Throwable.detailMessage, read as a field")
	void readsDetailMessage() {
		final ObjectReference exception = exceptionWithMessage(stringValue("Insufficient stock"));

		assertThat(JdiEventListener.detailMessage(exception)).isEqualTo("Insufficient stock");
	}

	@Test
	@DisplayName("no message: null; a long one is cut at 300 characters")
	void nullAndLongMessages() {
		assertThat(JdiEventListener.detailMessage(exceptionWithMessage(null))).isNull();
		assertThat(JdiEventListener.detailMessage(exceptionWithMessage(stringValue("x".repeat(400)))))
			.hasSize(301).endsWith("x…");
	}

	@Test
	@DisplayName("strings are quoted, other values are not")
	void quotesStrings() {
		final IntegerValue five = mock(IntegerValue.class);
		when(five.toString()).thenReturn("5");

		assertThat(JdiEventListener.quoted(stringValue("Alice"))).isEqualTo("\"Alice\"");
		assertThat(JdiEventListener.quoted(five)).isEqualTo("5");
		assertThat(JdiEventListener.quoted(null)).isEqualTo("null");
	}

	@Test
	@DisplayName("the site is Class.method:line with the simple class name")
	void describesSite() {
		final Location location = mock(Location.class);
		final ReferenceType type = mock(ReferenceType.class);
		final Method method = mock(Method.class);
		when(location.declaringType()).thenReturn(type);
		when(type.name()).thenReturn("one.edee.config.ConfigurationProvider");
		when(location.method()).thenReturn(method);
		when(method.name()).thenReturn("resetToDefaults");
		when(location.lineNumber()).thenReturn(41);

		assertThat(JdiEventListener.describeSite(location)).isEqualTo("ConfigurationProvider.resetToDefaults:41");
		assertThat(JdiEventListener.describeSite(null)).isEqualTo("unknown");
	}

	private static ObjectReference exceptionWithMessage(StringReference message) {
		final ObjectReference exception = mock(ObjectReference.class);
		final ReferenceType type = mock(ReferenceType.class);
		final ReferenceType throwable = mock(ReferenceType.class);
		final Field cause = mock(Field.class);
		final Field detailMessage = mock(Field.class);
		when(exception.referenceType()).thenReturn(type);
		when(throwable.name()).thenReturn("java.lang.Throwable");
		when(cause.name()).thenReturn("cause");
		when(cause.declaringType()).thenReturn(throwable);
		when(detailMessage.name()).thenReturn("detailMessage");
		when(detailMessage.declaringType()).thenReturn(throwable);
		when(type.allFields()).thenReturn(List.of(cause, detailMessage));
		when(exception.getValue(detailMessage)).thenReturn(message);
		return exception;
	}

	private static StringReference stringValue(String value) {
		final StringReference string = mock(StringReference.class);
		when(string.value()).thenReturn(value);
		return string;
	}
}
