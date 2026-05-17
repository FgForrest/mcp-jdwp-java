package one.edee.mcp.jdwp;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the local-variable / field-inspection / field-mutation MCP tools:
 * {@link JDWPTools#jdwp_get_locals}, {@link JDWPTools#jdwp_get_fields},
 * {@link JDWPTools#jdwp_set_field}.
 */
@DisplayName("jdwp_get_locals / jdwp_get_fields / jdwp_set_field")
class JDWPToolsGetLocalsAndFieldsTest {

	private JDIConnectionService jdiService;
	private JDWPTools tools;
	private VirtualMachine vm;

	@BeforeEach
	void setUp() {
		jdiService = mock(JDIConnectionService.class);
		final BreakpointTracker breakpointTracker = mock(BreakpointTracker.class);
		final WatcherManager watcherManager = mock(WatcherManager.class);
		final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
		final EventHistory eventHistory = new EventHistory();
		tools = JDWPToolsTestSupport.newTools(
			jdiService, breakpointTracker, watcherManager, evaluator,
			eventHistory, new EvaluationGuard(), new JvmDiscoveryService());
		vm = mock(VirtualMachine.class);
	}

	private ThreadReference suspendedThread(long id) {
		final ThreadReference thread = mock(ThreadReference.class);
		when(thread.uniqueID()).thenReturn(id);
		when(thread.isSuspended()).thenReturn(true);
		return thread;
	}

	@Nested
	@DisplayName("jdwp_get_locals")
	class GetLocals {

		@Test
		@DisplayName("renders 'this' followed by locals for an instance method")
		void shouldRenderThisAndLocalsForInstanceMethod() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			final StackFrame frame = mock(StackFrame.class);
			final ObjectReference thisObj = mock(ObjectReference.class);
			final ReferenceType thisType = mock(ReferenceType.class);
			final LocalVariable localVar = mock(LocalVariable.class);
			final Value localValue = mock(Value.class);

			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			when(thread.frame(0)).thenReturn(frame);
			when(frame.thisObject()).thenReturn(thisObj);
			when(thisObj.referenceType()).thenReturn(thisType);
			when(thisType.name()).thenReturn("com.example.Foo");
			when(jdiService.formatFieldValue(thisObj)).thenReturn("Object#42");
			when(frame.visibleVariables()).thenReturn(List.of(localVar));
			when(frame.getValues(List.of(localVar))).thenReturn(Map.of(localVar, localValue));
			when(localVar.name()).thenReturn("count");
			when(localVar.typeName()).thenReturn("int");
			when(jdiService.formatFieldValue(localValue)).thenReturn("7");

			final String result = tools.jdwp_get_locals(1L, 0);

			assertThat(result)
				.contains("Local variables in frame 0")
				.contains("this (com.example.Foo) = Object#42")
				.contains("count (int) = 7");
		}

		@Test
		@DisplayName("omits 'this' line for a static method (no current object)")
		void shouldOmitThisLineForStaticMethod() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			final StackFrame frame = mock(StackFrame.class);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			when(thread.frame(0)).thenReturn(frame);
			when(frame.thisObject()).thenReturn(null);
			when(frame.visibleVariables()).thenReturn(List.of());
			when(frame.getValues(List.of())).thenReturn(Map.of());

			final String result = tools.jdwp_get_locals(1L, 0);

			assertThat(result).contains("Local variables in frame 0");
			assertThat(result).doesNotContain("this (");
		}

		@Test
		@DisplayName("renders the header even when the frame has no locals or this")
		void shouldRenderHeaderForEmptyFrame() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			final StackFrame frame = mock(StackFrame.class);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			when(thread.frame(0)).thenReturn(frame);
			when(frame.thisObject()).thenReturn(null);
			when(frame.visibleVariables()).thenReturn(List.of());
			when(frame.getValues(List.of())).thenReturn(Map.of());

			final String result = tools.jdwp_get_locals(1L, 0);

			assertThat(result).isEqualTo("Local variables in frame 0:\n\n");
		}

		@Test
		@DisplayName("returns 'Thread not found' when threadId is unknown")
		void shouldReturnThreadNotFoundWhenThreadIdIsUnknown() throws Exception {
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of());

			final String result = tools.jdwp_get_locals(999L, 0);

			assertThat(result).isEqualTo("Error: Thread not found with ID 999");
		}

		@Test
		@DisplayName("returns 'Error:' message when the frame lacks debug info")
		void shouldReturnErrorWhenFrameLacksDebugInfo() throws Exception {
			final ThreadReference thread = suspendedThread(1L);
			final StackFrame frame = mock(StackFrame.class);
			when(jdiService.getVM()).thenReturn(vm);
			when(vm.allThreads()).thenReturn(List.of(thread));
			when(thread.frame(0)).thenReturn(frame);
			when(frame.thisObject()).thenReturn(null);
			when(frame.visibleVariables()).thenThrow(new AbsentInformationException());

			final String result = tools.jdwp_get_locals(1L, 0);

			assertThat(result).startsWith("Error:");
		}
	}

	@Nested
	@DisplayName("jdwp_get_fields")
	class GetFields {

		@Test
		@DisplayName("delegates to JDIConnectionService.getObjectFields")
		void shouldDelegateToJdiService() throws Exception {
			when(jdiService.getObjectFields(42L)).thenReturn("Object#42 fields:\n  a = 1");

			final String result = tools.jdwp_get_fields(42L);

			assertThat(result).contains("Object#42 fields").contains("a = 1");
		}

		@Test
		@DisplayName("returns 'Error:' when the delegate throws")
		void shouldReturnErrorWhenDelegateThrows() throws Exception {
			when(jdiService.getObjectFields(42L)).thenThrow(new RuntimeException("boom"));

			final String result = tools.jdwp_get_fields(42L);

			assertThat(result).startsWith("Error:").contains("boom");
		}
	}

	@Nested
	@DisplayName("jdwp_set_field")
	class SetField {

		@Test
		@DisplayName("happy path — primitive field set succeeds")
		void shouldSetPrimitiveFieldSuccessfully() throws Exception {
			final ObjectReference obj = mock(ObjectReference.class);
			final ReferenceType type = mock(ReferenceType.class);
			final Field field = mock(Field.class);
			final Type fieldType = mock(Type.class);
			final com.sun.jdi.IntegerValue mirrored = mock(com.sun.jdi.IntegerValue.class);

			when(jdiService.getCachedObject(7L)).thenReturn(obj);
			when(jdiService.getVM()).thenReturn(vm);
			when(obj.referenceType()).thenReturn(type);
			when(type.name()).thenReturn("com.example.Foo");
			when(type.fieldByName("count")).thenReturn(field);
			when(field.typeName()).thenReturn("int");
			when(field.type()).thenReturn(fieldType);
			when(fieldType.name()).thenReturn("int");
			when(vm.mirrorOf(42)).thenReturn(mirrored);

			final String result = tools.jdwp_set_field(7L, "count", "42");

			assertThat(result).isEqualTo("Field 'com.example.Foo.count' set to 42");
			verify(obj).setValue(field, mirrored);
		}

		@Test
		@DisplayName("returns '[ERROR] Object not in cache' when the object id is unknown")
		void shouldReturnErrorWhenObjectIsNotInCache() {
			when(jdiService.getCachedObject(7L)).thenReturn(null);

			final String result = tools.jdwp_set_field(7L, "count", "42");

			assertThat(result).contains("[ERROR] Object #7 not found in cache");
		}

		@Test
		@DisplayName("returns 'Field not found' when the field name is unknown on the type")
		void shouldReturnFieldNotFoundWhenFieldMissing() throws Exception {
			final ObjectReference obj = mock(ObjectReference.class);
			final ReferenceType type = mock(ReferenceType.class);
			when(jdiService.getCachedObject(7L)).thenReturn(obj);
			when(jdiService.getVM()).thenReturn(vm);
			when(obj.referenceType()).thenReturn(type);
			when(type.name()).thenReturn("com.example.Foo");
			when(type.fieldByName("missing")).thenReturn(null);

			final String result = tools.jdwp_set_field(7L, "missing", "42");

			assertThat(result).contains("Error:")
				.contains("Field 'missing' not found")
				.contains("com.example.Foo");
		}

		/**
		 * String value with surrounding double quotes is unwrapped before being mirrored. The
		 * rendered response echoes the raw input — including the quotes — so the user can see
		 * exactly what they passed.
		 */
		@Test
		@DisplayName("strips surrounding double quotes when assigning to a String field")
		void shouldStripQuotesForStringField() throws Exception {
			final ObjectReference obj = mock(ObjectReference.class);
			final ReferenceType type = mock(ReferenceType.class);
			final Field field = mock(Field.class);
			final Type fieldType = mock(Type.class);
			final StringReference mirrored = mock(StringReference.class);

			when(jdiService.getCachedObject(7L)).thenReturn(obj);
			when(jdiService.getVM()).thenReturn(vm);
			when(obj.referenceType()).thenReturn(type);
			when(type.name()).thenReturn("com.example.Foo");
			when(type.fieldByName("name")).thenReturn(field);
			when(field.typeName()).thenReturn("java.lang.String");
			when(field.type()).thenReturn(fieldType);
			when(fieldType.name()).thenReturn("java.lang.String");
			when(vm.mirrorOf("hello")).thenReturn(mirrored);

			final String result = tools.jdwp_set_field(7L, "name", "\"hello\"");

			assertThat(result).contains("Field 'com.example.Foo.name' set to \"hello\"");
			verify(obj).setValue(field, mirrored);
		}

		/**
		 * Length-1 single-quote input (just {@code "}) must NOT crash the quote-stripping branch
		 * with a {@code StringIndexOutOfBoundsException}; the {@code value.length() >= 2} guard
		 * keeps the input as-is and the call succeeds. Mirrors the {@code jdwp_set_local} pin.
		 */
		@Test
		@DisplayName("does not crash on a single unbalanced quote when assigning to a String field")
		void shouldHandleSingleQuoteStringWithoutCrashing() throws Exception {
			final ObjectReference obj = mock(ObjectReference.class);
			final ReferenceType type = mock(ReferenceType.class);
			final Field field = mock(Field.class);
			final Type fieldType = mock(Type.class);
			final StringReference mirrored = mock(StringReference.class);

			when(jdiService.getCachedObject(7L)).thenReturn(obj);
			when(jdiService.getVM()).thenReturn(vm);
			when(obj.referenceType()).thenReturn(type);
			when(type.name()).thenReturn("com.example.Foo");
			when(type.fieldByName("name")).thenReturn(field);
			when(field.typeName()).thenReturn("java.lang.String");
			when(field.type()).thenReturn(fieldType);
			when(fieldType.name()).thenReturn("java.lang.String");
			when(vm.mirrorOf("\"")).thenReturn(mirrored);

			final String result = tools.jdwp_set_field(7L, "name", "\"");

			assertThat(result).doesNotStartWith("Error");
			assertThat(result).contains("Field 'com.example.Foo.name' set to");
			verify(obj).setValue(field, mirrored);
		}
	}

	/**
	 * {@code jdwp_set_local} must turn a {@link com.sun.jdi.ClassNotLoadedException} from
	 * {@code localVar.type()} into an actionable hint naming the missing type and pointing the
	 * caller at {@code jdwp_force_load_class}. A generic {@code "Error setting variable:"} prefix
	 * leaves the user guessing why the assignment failed.
	 */
	@Test
	@DisplayName("set_local surfaces ClassNotLoadedException with an actionable force-load hint")
	void shouldSurfaceClassNotLoadedAsGenericSetLocalError() throws Exception {
		final ThreadReference thread = suspendedThread(1L);
		final StackFrame frame = mock(StackFrame.class);
		final LocalVariable localVar = mock(LocalVariable.class);

		when(jdiService.getVM()).thenReturn(vm);
		when(vm.allThreads()).thenReturn(List.of(thread));
		when(thread.frame(0)).thenReturn(frame);
		when(frame.visibleVariableByName("x")).thenReturn(localVar);
		when(localVar.typeName()).thenReturn("com.example.NotLoaded");
		when(localVar.type()).thenThrow(new com.sun.jdi.ClassNotLoadedException("com.example.NotLoaded"));

		final String result = tools.jdwp_set_local(1L, 0, "x", "42");

		assertThat(result).startsWith("Error setting variable:");
		assertThat(result).contains("com.example.NotLoaded");
		assertThat(result).contains("jdwp_force_load_class");
	}

	/**
	 * When a cached object's {@link ObjectReference#virtualMachine()} no longer matches the live
	 * VM (because of a silent reconnect between MCP calls), {@code jdwp_set_field} must short-
	 * circuit with an actionable "previous VM session" hint instead of surfacing the raw
	 * downstream error. The cure is to re-fetch the object id via {@code jdwp_get_locals}.
	 */
	@Test
	@DisplayName("returns previous-VM-session hint when a cached object belongs to a stale VM")
	void shouldReturnGenericErrorWhenCachedObjectIsFromStaleVm() throws Exception {
		final ObjectReference obj = mock(ObjectReference.class);
		final VirtualMachine staleVm = mock(VirtualMachine.class);
		when(jdiService.getCachedObject(7L)).thenReturn(obj);
		when(jdiService.getVM()).thenReturn(vm);
		when(obj.virtualMachine()).thenReturn(staleVm);

		final String result = tools.jdwp_set_field(7L, "count", "42");

		assertThat(result)
			.contains("previous VM session")
			.contains("jdwp_get_locals");
	}
}
