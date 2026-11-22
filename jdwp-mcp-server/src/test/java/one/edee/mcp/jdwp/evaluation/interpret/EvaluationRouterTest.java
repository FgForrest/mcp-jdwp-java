package one.edee.mcp.jdwp.evaluation.interpret;

import com.sun.jdi.ClassType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

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

    @Test
    void syntaxErrorsAndStrictInterpreterRefusalsNeverFallBack() {
        final var compiler = mock(JdiExpressionEvaluator.class);
        final var strict = new EvaluationRouter(compiler, EvaluationRouter.Mode.INTERPRETER);
        final var thread = mock(ThreadReference.class);
        assertThatThrownBy(() -> strict.evaluateEvent("fetch()", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class).hasMessageContaining("not interpreted: method call");
        assertThatThrownBy(() -> strict.evaluateEvent("x +", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class).hasMessageContaining("Syntax error");
        assertThat(strict.diagnostics()).contains("interpreted 0, compiled 0", "method call (1)");
        verifyNoInteractions(compiler, thread);
    }

    @Test
    void compiledErrorsKeepTheirDiagnosticAndTheFallbackReason() throws Exception {
        final var compiler = mock(JdiExpressionEvaluator.class);
        final var auto = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final var thread = mock(ThreadReference.class);
        final var frame = mock(StackFrame.class);
        when(thread.frame(0)).thenReturn(frame);
        when(compiler.evaluate(frame, "fetch()", Map.of()))
            .thenThrow(new JdiEvaluationException("Compilation failed: missing member"));
        assertThatThrownBy(() -> auto.evaluateEvent("fetch()", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class)
            .hasMessage("Compilation failed: missing member\nnot interpreted: method call");
        final var order = inOrder(compiler, thread);
        order.verify(compiler).configureCompilerClasspathForEvent(thread);
        order.verify(thread).frame(0);
        order.verify(compiler).evaluate(frame, "fetch()", Map.of());
        assertThat(auto.diagnostics()).contains("interpreted 0, compiled 1", "method call (1)");
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
}
