package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An object created through JDI is referenced by nothing in the target until it is pinned, so a GC
 * in between collects it. {@link RemoteCodeExecutor} pins every mirror it creates at once, creates
 * it again when it was collected first, and unpins it after the call it was made for.
 */
@DisplayName("RemoteCodeExecutor pins the mirrors it creates")
class RemoteCodeExecutorPinningTest {

	private static final String CLASS_NAME = "com.example.Probe";
	private static final byte[] BYTECODE = {1, 2, 3};

	private VirtualMachine vm;
	private ThreadReference thread;
	private ClassLoaderReference classLoader;
	private ArrayType byteArrayType;
	private ClassType classClass;
	private Method defineClass;
	private ClassType defined;

	@BeforeEach
	void setUp() throws Exception {
		vm = mock(VirtualMachine.class);
		thread = mock(ThreadReference.class);
		classLoader = mock(ClassLoaderReference.class);
		byteArrayType = mock(ArrayType.class);
		classClass = mock(ClassType.class);
		defineClass = mock(Method.class);
		defined = mock(ClassType.class);

		when(vm.classesByName(CLASS_NAME)).thenReturn(List.of());
		when(vm.classesByName("byte[]")).thenReturn(List.of(byteArrayType));
		when(vm.classesByName("java.lang.Class")).thenReturn(List.of(classClass));
		when(vm.mirrorOf(anyByte())).thenReturn(mock(ByteValue.class));
		when(vm.mirrorOf(anyInt())).thenReturn(mock(IntegerValue.class));
		when(vm.mirrorOf(anyBoolean())).thenReturn(mock(BooleanValue.class));

		final ReferenceType loaderType = mock(ReferenceType.class);
		when(loaderType.name()).thenReturn("app");
		when(classLoader.referenceType()).thenReturn(loaderType);
		when(loaderType.methodsByName(eq("defineClass"), anyString())).thenReturn(List.of(defineClass));

		final ClassObjectReference definedObject = mock(ClassObjectReference.class);
		when(definedObject.reflectedType()).thenReturn(defined);
		when(classLoader.invokeMethod(eq(thread), eq(defineClass), anyList(), anyInt())).thenReturn(definedObject);
		final Method forName = mock(Method.class);
		when(classClass.methodsByName(eq("forName"), anyString())).thenReturn(List.of(forName));
		when(classClass.invokeMethod(eq(thread), eq(forName), anyList(), anyInt())).thenReturn(definedObject);
	}

	@Test
	@DisplayName("an array collected before it was pinned is created again, and every pin is released")
	void collectedArrayIsCreatedAgain() throws Exception {
		final ArrayReference collected = mock(ArrayReference.class);
		doThrow(new ObjectCollectedException()).when(collected).disableCollection();
		final ArrayReference array = mock(ArrayReference.class);
		when(byteArrayType.newInstance(BYTECODE.length)).thenReturn(collected, array);
		final StringReference name = mock(StringReference.class);
		when(vm.mirrorOf(anyString())).thenReturn(name);

		assertThat(RemoteCodeExecutor.defineAndInitialize(vm, thread, classLoader, CLASS_NAME, BYTECODE))
			.isSameAs(defined);

		verify(collected, never()).setValues(anyInt(), anyList(), anyInt(), anyInt());
		@SuppressWarnings("unchecked")
		final ArgumentCaptor<List<Value>> args = ArgumentCaptor.forClass(List.class);
		verify(classLoader).invokeMethod(eq(thread), eq(defineClass), args.capture(), anyInt());
		assertThat(args.getValue()).startsWith(name, array);
		verify(array).enableCollection();
		verify(name, times(2)).disableCollection();
		verify(name, times(2)).enableCollection();
	}

	@Test
	@DisplayName("a mirror collected on every attempt fails the call and leaves nothing pinned")
	void mirrorCollectedOnEveryAttemptFails() throws Exception {
		final ArrayReference array = mock(ArrayReference.class);
		when(byteArrayType.newInstance(BYTECODE.length)).thenReturn(array);
		final StringReference name = mock(StringReference.class);
		doThrow(new ObjectCollectedException()).when(name).disableCollection();
		when(vm.mirrorOf(anyString())).thenReturn(name);

		assertThatThrownBy(() -> RemoteCodeExecutor.defineAndInitialize(vm, thread, classLoader, CLASS_NAME, BYTECODE))
			.isInstanceOf(ObjectCollectedException.class);

		verify(vm, times(3)).mirrorOf(anyString());
		verify(classLoader, never()).invokeMethod(any(), any(), anyList(), anyInt());
		verify(array).enableCollection();
	}
}
