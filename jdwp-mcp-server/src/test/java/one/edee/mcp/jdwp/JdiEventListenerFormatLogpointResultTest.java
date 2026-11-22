package one.edee.mcp.jdwp;

import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests {@link JdiEventListener}'s private static {@code formatLogpointResult(Value)} via
 * reflection. This method converts a JDI {@link Value} into a human-readable string for
 * logpoint event history entries.
 */
class JdiEventListenerFormatLogpointResultTest {

	@Test
	@DisplayName("null value returns \"null\"")
	void shouldReturnNullStringForNullValue() throws Exception {
		String result = invokeFormatLogpointResult(null);
		assertThat(result).isEqualTo("null");
	}

	@Test
	@DisplayName("StringReference returns its .value()")
	void shouldReturnStringValueForStringReference() throws Exception {
		StringReference strRef = mock(StringReference.class);
		when(strRef.value()).thenReturn("hello world");

		String result = invokeFormatLogpointResult(strRef);
		assertThat(result).isEqualTo("hello world");
	}

	@Test
	@DisplayName("PrimitiveValue returns its .toString()")
	void shouldReturnToStringForPrimitiveValue() throws Exception {
		PrimitiveValue pv = mock(PrimitiveValue.class);
		when(pv.toString()).thenReturn("42");

		String result = invokeFormatLogpointResult(pv);
		assertThat(result).isEqualTo("42");
	}

	@Test
	@DisplayName("ObjectReference shows its type without an uncached ID")
	void shouldShowObjectTypeWithoutAnId() throws Exception {
		ObjectReference objRef = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		when(objRef.referenceType()).thenReturn(refType);
		when(refType.name()).thenReturn("com.example.Foo");

		String result = invokeFormatLogpointResult(objRef);
		assertThat(result).isEqualTo("Object (com.example.Foo)");
	}

	@Test
	@DisplayName("A boxed primitive is read directly from its value field")
	void shouldUnboxPrimitiveWithoutInvokingTargetCode() throws Exception {
		ObjectReference boxed = mock(ObjectReference.class);
		ReferenceType type = mock(ReferenceType.class);
		Field field = mock(Field.class);
		PrimitiveValue value = mock(PrimitiveValue.class);
		when(boxed.referenceType()).thenReturn(type);
		when(type.name()).thenReturn("java.lang.Integer");
		when(type.fieldByName("value")).thenReturn(field);
		when(boxed.getValue(field)).thenReturn(value);
		when(value.toString()).thenReturn("-204723185");

		assertThat(invokeFormatLogpointResult(boxed)).isEqualTo("-204723185");
		org.mockito.Mockito.verify(boxed).referenceType();
		org.mockito.Mockito.verify(boxed).getValue(field);
		verifyNoMoreInteractions(boxed);
	}

	@Test
	@DisplayName("Unknown Value subtype falls back to .toString()")
	void shouldFallBackToToStringForUnknownValueType() throws Exception {
		Value unknown = mock(Value.class);
		when(unknown.toString()).thenReturn("<custom-value>");

		String result = invokeFormatLogpointResult(unknown);
		assertThat(result).isEqualTo("<custom-value>");
	}

	// ── helper ──

	/**
	 * Invokes the private static {@code formatLogpointResult(Value)} on {@link JdiEventListener}
	 * via reflection. Since it is static, we pass {@code null} as the target and use the class
	 * directly.
	 */
	private static String invokeFormatLogpointResult(Value value) throws Exception {
		java.lang.reflect.Method method = JdiEventListener.class.getDeclaredMethod(
			"formatLogpointResult", Value.class);
		method.setAccessible(true);
		return (String) method.invoke(null, value);
	}
}
