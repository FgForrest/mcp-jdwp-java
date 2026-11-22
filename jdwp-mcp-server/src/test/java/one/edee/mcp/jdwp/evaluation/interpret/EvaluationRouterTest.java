package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Exercises routing, fallback and navigation against JDI mirrors. */
class EvaluationRouterTest {

    @Test
    void keepsStringMirrorsPinnedThroughFormatting() throws Exception {
        final var compiler = mock(JdiExpressionEvaluator.class);
        final var router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final var thread = mock(ThreadReference.class);
        final var vm = mock(VirtualMachine.class);
        final var stringClass = mock(ClassType.class);
        final var value = mock(StringReference.class);
        when(thread.virtualMachine()).thenReturn(vm);
        when(vm.classesByName("java.lang.String")).thenReturn(List.of(stringClass));
        when(vm.mirrorOf("hello")).thenReturn(value);
        when(value.value()).thenReturn("hello");
        assertThat(router.<String>evaluateEvent("\"hello\"", thread, Map.of(), v -> ((StringReference) v).value()))
            .isEqualTo("hello");
        final var order = inOrder(value);
        order.verify(value).disableCollection();
        order.verify(value).value();
        order.verify(value).enableCollection();
        verifyNoInteractions(compiler);
        assertThat(router.diagnostics()).contains("interpreted 1, compiled 0");
    }

    /** Refuses syntax errors and unsupported calls directly in strict mode. */
    @Test
    void syntaxErrorsAndStrictInterpreterRefusalsNeverFallBack() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter strict = new EvaluationRouter(compiler, EvaluationRouter.Mode.INTERPRETER);
        final ThreadReference thread = mock(ThreadReference.class);
        when(thread.virtualMachine()).thenReturn(mock(VirtualMachine.class));
        assertThatThrownBy(() -> strict.evaluateEvent("fetch()", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class)
            .hasMessageContaining("not interpreted: unqualified method name is absent from the frame class: fetch");
        assertThatThrownBy(() -> strict.evaluateEvent("x +", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class).hasMessageContaining("Syntax error");
        assertThat(strict.diagnostics()).contains("interpreted 0, compiled 0", "unqualified method name is absent from the frame class (1)");
        verifyNoInteractions(compiler);
        verify(thread).frame(0);
    }

    /** Reports the compiler error with the precise planning reason. */
    @Test
    void compiledErrorsKeepTheirDiagnosticAndTheFallbackReason() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter auto = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final ThreadReference thread = mock(ThreadReference.class);
        final StackFrame frame = mock(StackFrame.class);
        when(thread.frame(0)).thenReturn(frame);
        when(thread.virtualMachine()).thenReturn(mock(VirtualMachine.class));
        when(compiler.evaluate(frame, "fetch()", Map.of()))
            .thenThrow(new JdiEvaluationException("Compilation failed: missing member"));
        assertThatThrownBy(() -> auto.evaluateEvent("fetch()", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class)
            .hasMessage("Compilation failed: missing member\nnot interpreted: unqualified method name is absent from the frame class: fetch");
        final InOrder order = inOrder(compiler, thread);
        order.verify(thread).frame(0);
        order.verify(compiler).configureCompilerClasspathForEvent(thread);
        order.verify(thread).frame(0);
        order.verify(compiler).evaluate(frame, "fetch()", Map.of());
        assertThat(auto.diagnostics()).contains("interpreted 0, compiled 1", "unqualified method name is absent from the frame class (1)");
    }

    @Test
    void compilerModePassesSourceThroughWithoutParsing() throws Exception {
        final var compiler = mock(JdiExpressionEvaluator.class);
        final var router = new EvaluationRouter(compiler, EvaluationRouter.Mode.COMPILER);
        final var thread = mock(ThreadReference.class);
        final var frame = mock(StackFrame.class);
        when(thread.frame(0)).thenReturn(frame);
        assertThat(router.<String>evaluateEvent("x +", thread, Map.of(), String::valueOf)).isEqualTo("null");
        verify(compiler).evaluate(frame, "x +", Map.of());
        verify(thread, never()).virtualMachine();
        assertThat(router.diagnostics()).contains("mode compiler").doesNotContain("not interpreted");
    }

    /** A late field lookup refusal must not replay a target call or read the invalidated frame. */
    @Test
    void reportsARefusalAfterInvokingWithoutTryingTheCompiler() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationGuard guard = new EvaluationGuard();
        final EvaluationRouter router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ThreadReference thread = mock(ThreadReference.class);
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final ClassType erased = mock(ClassType.class);
        final ClassType runtime = mock(ClassType.class);
        final ObjectReference receiver = mock(ObjectReference.class);
        final ObjectReference result = mock(ObjectReference.class);
        final Method method = mock(Method.class);
        final Field first = mock(Field.class);
        final Field second = mock(Field.class);
        final int options = ObjectReference.INVOKE_SINGLE_THREADED;
        final AtomicBoolean ran = new AtomicBoolean();

        when(thread.virtualMachine()).thenReturn(vm);
        when(thread.frame(0)).thenReturn(frame);
        when(frame.thisObject()).thenReturn(receiver);
        when(frame.location()).thenAnswer(call -> {
            assertThat(ran).isFalse();
            return location;
        });
        when(location.declaringType()).thenReturn(owner);
        when(method.name()).thenReturn("take");
        when(method.signature()).thenReturn("()Ljava/lang/Object;");
        when(method.genericSignature()).thenReturn("()TT;");
        when(method.declaringType()).thenReturn(owner);
        when(method.returnType()).thenReturn(erased);
        when(owner.methods()).thenReturn(List.of(method));
        when(result.referenceType()).thenReturn(runtime);
        when(first.name()).thenReturn("value");
        when(second.name()).thenReturn("value");
        when(runtime.allFields()).thenReturn(List.of(first, second));
        when(compiler.invocationLease(thread)).thenAnswer(call -> guard.lease(thread));
        when(receiver.invokeMethod(thread, method, List.of(), options)).thenAnswer(call -> {
            assertThat(guard.isEvaluating(thread)).isTrue();
            ran.set(true);
            return result;
        });

        assertThatThrownBy(() -> router.evaluateEvent("take().value", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class)
            .hasMessage("Interpreter refused after 1 invocation: field hiding on an erased type: value");
        verify(compiler).invocationLease(thread);
        verifyNoMoreInteractions(compiler);
        verify(result).disableCollection();
        verify(result).enableCollection();
        assertThat(guard.isEvaluating(thread)).isFalse();
    }

    /** A dispatcher result based on an invalidated frame is refused without a retry or compilation. */
    @Test
    void refusesDispatcherResultWhenFrameResumesDuringFormatting() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final ThreadReference thread = mock(ThreadReference.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final AtomicBoolean resumed = new AtomicBoolean();
        when(thread.virtualMachine()).thenReturn(vm);
        when(thread.frame(0)).thenReturn(frame);
        when(location.declaringType()).thenReturn(owner);
        when(frame.location()).thenAnswer(call -> {
            if (resumed.get()) {
                throw new InvalidStackFrameException("resumed during formatting");
            }
            return location;
        });

        assertThatThrownBy(() -> router.evaluateEvent("1", thread, Map.of(), value -> {
            resumed.set(true);
            return "1";
        })).isInstanceOf(InvalidStackFrameException.class).hasMessage("resumed during formatting");
        verify(thread).frame(0);
        verifyNoInteractions(compiler);
    }
}
