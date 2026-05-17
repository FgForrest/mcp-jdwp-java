package one.edee.mcp.jdwp;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JDWPTools}'s private {@code createJdiValue(VirtualMachine, String, Type)} parser
 * via reflection. Each test mocks the {@link VirtualMachine}'s {@code mirrorOf*} return value
 * for the corresponding primitive kind, and asserts that the parser routed the input correctly.
 */
@org.junit.jupiter.api.DisplayName("JDWPTools createJdiValue parser")
class JDWPToolsCreateJdiValueTest {

	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		JDIConnectionService jdiService = mock(JDIConnectionService.class);
		BreakpointTracker tracker = mock(BreakpointTracker.class);
		WatcherManager watcherManager = mock(WatcherManager.class);
		JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		EventHistory eventHistory = mock(EventHistory.class);
		tools = JDWPToolsTestSupport.newTools(
			jdiService, tracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(),
			new one.edee.mcp.jdwp.discovery.JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
	}

	@Nested
	@DisplayName("Primitive parsing")
	class PrimitiveParsing {

		@Test
		void shouldCreateIntValue() throws Exception {
			IntegerValue iv = mock(IntegerValue.class);
			when(vm.mirrorOf(42)).thenReturn(iv);

			Value result = invokeCreate("42", typeNamed("int"));

			assertThat(result).isSameAs(iv);
			verify(vm).mirrorOf(42);
		}

		@Test
		void shouldCreateLongValueWithoutSuffix() throws Exception {
			LongValue lv = mock(LongValue.class);
			when(vm.mirrorOf(123L)).thenReturn(lv);

			Value result = invokeCreate("123", typeNamed("long"));

			assertThat(result).isSameAs(lv);
		}

		@Test
		void shouldCreateLongValueWithSuffix() throws Exception {
			LongValue lv = mock(LongValue.class);
			when(vm.mirrorOf(456L)).thenReturn(lv);

			Value result = invokeCreate("456L", typeNamed("long"));

			assertThat(result).isSameAs(lv);
		}

		@Test
		void shouldCreateDoubleValue() throws Exception {
			DoubleValue dv = mock(DoubleValue.class);
			when(vm.mirrorOf(3.14)).thenReturn(dv);

			Value result = invokeCreate("3.14", typeNamed("double"));

			assertThat(result).isSameAs(dv);
		}

		@Test
		void shouldCreateFloatValueWithoutSuffix() throws Exception {
			FloatValue fv = mock(FloatValue.class);
			when(vm.mirrorOf(2.5f)).thenReturn(fv);

			Value result = invokeCreate("2.5", typeNamed("float"));

			assertThat(result).isSameAs(fv);
		}

		@Test
		void shouldCreateFloatValueWithSuffix() throws Exception {
			FloatValue fv = mock(FloatValue.class);
			when(vm.mirrorOf(2.5f)).thenReturn(fv);

			Value result = invokeCreate("2.5f", typeNamed("float"));

			assertThat(result).isSameAs(fv);
		}

		@Test
		void shouldCreateBooleanTrue() throws Exception {
			BooleanValue bv = mock(BooleanValue.class);
			when(vm.mirrorOf(true)).thenReturn(bv);

			Value result = invokeCreate("true", typeNamed("boolean"));

			assertThat(result).isSameAs(bv);
		}

		@Test
		void shouldCreateBooleanFalse() throws Exception {
			BooleanValue bv = mock(BooleanValue.class);
			when(vm.mirrorOf(false)).thenReturn(bv);

			Value result = invokeCreate("false", typeNamed("boolean"));

			assertThat(result).isSameAs(bv);
		}

		/**
		 * For a {@code char} target type, surrounding {@code '...'} must be stripped before
		 * {@code mirrorOf} is called so the parser produces the intended character rather than the
		 * apostrophe at index 0.
		 */
		@Test
		void shouldUseFirstCharFromQuotedInput() throws Exception {
			CharValue cv = mock(CharValue.class);
			when(vm.mirrorOf('a')).thenReturn(cv);

			Value result = invokeCreate("'a'", typeNamed("char"));

			assertThat(result).isSameAs(cv);
			verify(vm).mirrorOf('a');
		}

		@Test
		void shouldCreateCharFromFirstChar() throws Exception {
			CharValue cv = mock(CharValue.class);
			when(vm.mirrorOf('a')).thenReturn(cv);

			Value result = invokeCreate("a", typeNamed("char"));

			assertThat(result).isSameAs(cv);
		}

		@Test
		void shouldCreateByteValue() throws Exception {
			ByteValue bv = mock(ByteValue.class);
			when(vm.mirrorOf((byte) 7)).thenReturn(bv);

			Value result = invokeCreate("7", typeNamed("byte"));

			assertThat(result).isSameAs(bv);
		}

		@Test
		void shouldCreateShortValue() throws Exception {
			ShortValue sv = mock(ShortValue.class);
			when(vm.mirrorOf((short) 9)).thenReturn(sv);

			Value result = invokeCreate("9", typeNamed("short"));

			assertThat(result).isSameAs(sv);
		}
	}

	@Nested
	@DisplayName("String and null")
	class StringAndNull {

		@Test
		void shouldCreateStringValue() throws Exception {
			StringReference sr = mock(StringReference.class);
			when(vm.mirrorOf("hello")).thenReturn(sr);

			Value result = invokeCreate("hello", typeNamed("java.lang.String"));

			assertThat(result).isSameAs(sr);
		}

		@Test
		void shouldReturnNullForNullKeyword() throws Exception {
			Value result = invokeCreate("null", typeNamed("java.lang.String"));

			assertThat(result).isNull();
		}
	}

	@Nested
	@DisplayName("Error cases")
	class ErrorCases {

		@Test
		void shouldThrowForUnsupportedType() {
			Type unsupported = typeNamed("java.util.Map");

			assertThatThrownBy(() -> invokeCreate("ignored", unsupported))
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported type");
		}

		@Test
		void shouldThrowNumberFormatForBadInt() {
			assertThatThrownBy(() -> invokeCreate("not-a-number", typeNamed("int")))
				.rootCause()
				.isInstanceOf(NumberFormatException.class);
		}
	}

	// ── helpers ──

	/**
	 * Invokes the private {@code createJdiValue(VirtualMachine, String, Type)} on the
	 * fixture {@link JDWPTools} instance via reflection.
	 */
	private Value invokeCreate(String valueStr, Type targetType) throws Exception {
		return TestReflectionUtils.invokePrivate(
			tools, "createJdiValue",
			new Class[]{VirtualMachine.class, String.class, Type.class},
			vm, valueStr, targetType);
	}

	/**
	 * Builds a mocked {@link Type} whose {@link Type#name()} returns {@code name}. Used as
	 * the target-type argument when driving the parser.
	 */
	private static Type typeNamed(String name) {
		Type t = mock(Type.class);
		when(t.name()).thenReturn(name);
		return t;
	}
}
