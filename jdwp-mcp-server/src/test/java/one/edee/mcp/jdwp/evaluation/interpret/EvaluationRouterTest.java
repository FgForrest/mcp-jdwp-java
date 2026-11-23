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

    /** Retains an existing mirror stored in a read-only block through result formatting. */
    @Test
    void pinsAReadOnlyBlockLocalWithoutTakingALease() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final ThreadReference thread = mock(ThreadReference.class);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ClassType type = mock(ClassType.class);
        final ObjectReference marked = mock(ObjectReference.class);
        when(thread.virtualMachine()).thenReturn(vm);
        when(marked.type()).thenReturn(type);
        when(marked.uniqueID()).thenReturn(42L);

        final String source = "{ var alias = $marked; return alias; }";
        final long formatted = router.evaluateEvent(source, thread, Map.of("$marked", marked),
            value -> ((ObjectReference) value).uniqueID());
        assertThat(formatted).isEqualTo(42);
        final InOrder order = inOrder(marked);
        order.verify(marked).disableCollection();
        order.verify(marked).uniqueID();
        order.verify(marked).enableCollection();
        verifyNoInteractions(compiler);
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

    /** Refreshes the frame after loader discovery and retains the reason for falling back to compilation. */
    @Test
    void compiledErrorsKeepTheirDiagnosticAndTheFallbackReason() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter auto = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final ThreadReference thread = mock(ThreadReference.class);
        final StackFrame planningFrame = mock(StackFrame.class);
        final StackFrame discoveryFrame = mock(StackFrame.class);
        final StackFrame compilationFrame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final ClassLoaderReference definingLoader = mock(ClassLoaderReference.class);
        when(thread.frame(0)).thenReturn(planningFrame, discoveryFrame, compilationFrame);
        when(thread.virtualMachine()).thenReturn(mock(VirtualMachine.class));
        when(discoveryFrame.location()).thenReturn(location);
        when(location.declaringType()).thenReturn(owner);
        when(owner.classLoader()).thenReturn(definingLoader);
        when(compiler.evaluate(compilationFrame, "fetch()", Map.of()))
            .thenThrow(new JdiEvaluationException("Compilation failed: missing member"));
        assertThatThrownBy(() -> auto.evaluateEvent("fetch()", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class)
            .hasMessage("Compilation failed: missing member\nnot interpreted: unqualified method name is absent from the frame class: fetch");
        final InOrder order = inOrder(planningFrame, discoveryFrame, owner, compiler, thread);
        order.verify(thread).frame(0);
        order.verify(planningFrame).location();
        order.verify(thread).frame(0);
        order.verify(discoveryFrame).location();
        order.verify(owner).classLoader();
        order.verify(compiler).configureCompilerClasspathForEvent(thread, definingLoader);
        order.verify(thread).frame(0);
        order.verify(compiler).evaluate(compilationFrame, "fetch()", Map.of());
        assertThat(auto.diagnostics()).contains("interpreted 0, compiled 1", "unqualified method name is absent from the frame class (1)");
    }

    /** Checks each selected frame in a batch even when the tool is on the same target thread. */
    @Test
    void compiledToolJobUsesEachSelectedFramesDefiningLoader() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter router = new EvaluationRouter(compiler, EvaluationRouter.Mode.COMPILER);
        final ThreadReference thread = mock(ThreadReference.class);
        final EvaluationRouter.ToolJob job = router.toolJob(thread, () -> {});
        final EvaluationRouter.Input input = router.parse("fetch()");
        for (final ClassLoaderReference definingLoader :
            List.of(mock(ClassLoaderReference.class), mock(ClassLoaderReference.class))) {
            final StackFrame frame = mock(StackFrame.class);
            final Location location = mock(Location.class);
            final ClassType owner = mock(ClassType.class);
            when(frame.location()).thenReturn(location);
            when(location.declaringType()).thenReturn(owner);
            when(owner.classLoader()).thenReturn(definingLoader);

            job.evaluate(input, () -> frame, Map.of(), String::valueOf);

            verify(compiler).configureCompilerClasspath(thread, definingLoader);
            verify(compiler).evaluate(frame, "fetch()", Map.of());
        }
        verifyNoInteractions(thread);
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

    /** Unknown member types must select fallback without leasing the thread or running the getter. */
    @Test
    void refusesAnErasedFieldLookupBeforeInvoking() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationRouter router = new EvaluationRouter(compiler, EvaluationRouter.Mode.INTERPRETER);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ThreadReference thread = mock(ThreadReference.class);
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final ClassType erased = mock(ClassType.class);
        final ObjectReference receiver = mock(ObjectReference.class);
        final Method method = mock(Method.class);

        when(thread.virtualMachine()).thenReturn(vm);
        when(thread.frame(0)).thenReturn(frame);
        when(frame.thisObject()).thenReturn(receiver);
        when(frame.location()).thenReturn(location);
        when(location.declaringType()).thenReturn(owner);
        when(method.name()).thenReturn("take");
        when(method.signature()).thenReturn("()Ljava/lang/Object;");
        when(method.genericSignature()).thenReturn("()TT;");
        when(method.declaringType()).thenReturn(owner);
        when(method.returnType()).thenReturn(erased);
        when(owner.methods()).thenReturn(List.of(method));

        assertThatThrownBy(() -> router.evaluateEvent("take().value", thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class)
            .hasMessage("not interpreted: field lookup depends on an erased receiver: value");
        verifyNoInteractions(compiler, receiver);
    }

    /** Recreates a repeatable helper result when GC wins the first pin, without repeating its inputs. */
    @Test
    void retriesOnlyTheCollectedHelperResult() throws Exception {
        final JdiExpressionEvaluator evaluator = mock(JdiExpressionEvaluator.class);
        final EvaluationGuard guard = new EvaluationGuard();
        final ThreadReference thread = mock(ThreadReference.class);
        final ClassType owner = mock(ClassType.class);
        final Method helper = mock(Method.class);
        final ObjectReference first = mock(ObjectReference.class);
        final ObjectReference next = mock(ObjectReference.class);
        final List<Value> arguments = List.of(mock(IntegerValue.class));
        final int options = ClassType.INVOKE_SINGLE_THREADED;
        when(evaluator.invocationLease(thread)).thenAnswer(call -> guard.lease(thread));
        when(helper.declaringType()).thenReturn(owner);
        when(helper.isStatic()).thenReturn(true);
        when(owner.invokeMethod(thread, helper, arguments, options)).thenReturn(first, next);
        doThrow(new ObjectCollectedException()).when(first).disableCollection();

        final Planner.Invoke plan = new Planner.Invoke(mock(VirtualMachine.class),
            context -> context.repeatableCall(helper, null, arguments));
        final Value result = Interpreter.execute(plan, thread, evaluator, count -> assertThat(count).isEqualTo(2),
            value -> {
                verify(next, never()).enableCollection();
                return value;
            });
        assertThat(result).isSameAs(next);
        verify(owner, times(2)).invokeMethod(thread, helper, arguments, options);
        verify(first, never()).enableCollection();
        verify(next).enableCollection();
    }

    /** A block snapshots its frame and uses one lease for both calls and reports a collected mirror without replay. */
    @Test
    void blockKeepsItsSnapshotAndNeverReplaysAfterInvoking() throws Exception {
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final EvaluationGuard guard = new EvaluationGuard();
        final EvaluationRouter router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final VirtualMachine vm = mock(VirtualMachine.class);
        final ThreadReference thread = mock(ThreadReference.class);
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final ObjectReference receiver = mock(ObjectReference.class);
        final LocalVariable local = mock(LocalVariable.class);
        final IntegerType integer = mock(IntegerType.class);
        final IntegerValue snapshot = mock(IntegerValue.class);
        final Method use = mock(Method.class);
        final Field field = mock(Field.class);
        final AtomicBoolean ran = new AtomicBoolean();
        final int options = ObjectReference.INVOKE_SINGLE_THREADED;
        when(thread.virtualMachine()).thenReturn(vm);
        when(thread.frame(0)).thenReturn(frame);
        when(frame.thisObject()).thenReturn(receiver);
        when(frame.location()).thenAnswer(call -> {
            assertThat(ran).isFalse();
            return location;
        });
        when(location.declaringType()).thenReturn(owner);
        when(owner.name()).thenReturn("app.Example");
        when(frame.visibleVariables()).thenReturn(List.of(local));
        when(frame.getValues(List.of(local))).thenAnswer(call -> {
            assertThat(ran).isFalse();
            return Map.of(local, snapshot);
        });
        when(local.name()).thenReturn("frameValue");
        when(local.type()).thenReturn(integer);
        when(integer.name()).thenReturn("int");
        when(owner.methods()).thenReturn(List.of(use));
        when(use.name()).thenReturn("use");
        when(use.signature()).thenReturn("(I)I");
        when(use.declaringType()).thenReturn(owner);
        when(use.returnType()).thenReturn(integer);
        when(use.argumentTypes()).thenReturn(List.of(integer));
        when(owner.fields()).thenReturn(List.of(field));
        when(field.declaringType()).thenReturn(owner);
        when(field.name()).thenReturn("field");
        when(field.type()).thenReturn(integer);
        when(receiver.getValue(field)).thenThrow(new ObjectCollectedException());
        when(compiler.invocationLease(thread)).thenAnswer(call -> guard.lease(thread));
        when(receiver.invokeMethod(thread, use, List.of(snapshot), options)).thenAnswer(call -> {
            assertThat(guard.isEvaluating(thread)).isTrue();
            ran.set(true);
            return snapshot;
        });

        // The final field read reports collection after both calls have run.
        final String source = "{ int copy = frameValue; this.use(copy); this.use(copy); return this.field; }";
        assertThatThrownBy(() -> router.evaluateEvent(source, thread, Map.of(), String::valueOf))
            .isInstanceOf(JdiEvaluationException.class)
            .hasMessage("A mirror used by the expression was collected by the target's garbage collector before the "
                + "debugger could pin it. 2 target calls already ran. Re-running the expression repeats those calls.");
        final InOrder order = inOrder(frame, compiler, receiver);
        order.verify(frame).getValues(List.of(local));
        order.verify(compiler).invocationLease(thread);
        order.verify(receiver, times(2)).invokeMethod(thread, use, List.of(snapshot), options);
        order.verify(receiver).getValue(field);
        verify(compiler).invocationLease(thread);
        verifyNoMoreInteractions(compiler);
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
