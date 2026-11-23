package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.jdi.event.BreakpointEvent;
import org.assertj.core.api.SoftAssertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Tests dispatcher navigation and fail-safe evaluation errors. */
class JdiEventListenerNavigateTest {

    @Test
    void queuedStopAllSetsStayParkedDuringANavigationCondition() throws Exception {
        final var target = new FakeSuspendModel();
        final var first = target.thread("first");
        final var second = target.thread("second");
        final var third = target.thread("third");

        final var tracker = new BreakpointTracker();
        final var history = new EventHistory();
        final var guard = new EvaluationGuard();
        final var compiler = mock(JdiExpressionEvaluator.class);
        final var router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final var listener = new JdiEventListener(tracker, history, compiler, guard, null,
            new MarkedInstanceRegistry(), router);
        final var readStarted = new CountDownLatch(1);
        final var releaseRead = new CountDownLatch(1);
        final var queued = new CountDownLatch(1);
        final BlockingQueue<EventSet> sets = new LinkedBlockingQueue<>();
        final EventQueue wire = mock(EventQueue.class);
        when(target.vm.eventQueue()).thenReturn(wire);
        when(wire.remove()).thenAnswer(call -> {
            if (sets.isEmpty() && third.count > 0) {
                queued.countDown();
            }
            return sets.take();
        });
        final BreakpointRequest condition = mock(BreakpointRequest.class);
        final int id = tracker.registerBreakpoint(condition);
        tracker.setCondition(id, "enabled");
        final EventSet firstSet = target.breakpointEvent(first, EventRequest.SUSPEND_EVENT_THREAD, condition);
        final EventSet secondSet = target.breakpointEvent(second, EventRequest.SUSPEND_ALL);
        final EventSet thirdSet = target.breakpointEvent(third, EventRequest.SUSPEND_ALL);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        when(owner.name()).thenReturn("example.App");
        when(location.declaringType()).thenReturn(owner);
        when(location.lineNumber()).thenReturn(10);
        for (EventSet set : List.of(firstSet, secondSet, thirdSet)) {
            final var event = (com.sun.jdi.event.BreakpointEvent) set.iterator().next();
            when(event.location()).thenReturn(location);
        }
        final StackFrame frame = mock(StackFrame.class);
        final LocalVariable enabled = mock(LocalVariable.class);
        final BooleanType type = mock(BooleanType.class);
        final BooleanValue yes = mock(BooleanValue.class);
        when(yes.value()).thenReturn(true);
        when(type.name()).thenReturn("boolean");
        when(enabled.name()).thenReturn("enabled");
        when(enabled.type()).thenReturn(type);
        when(first.ref.frame(0)).thenReturn(frame);
        when(frame.location()).thenReturn(location);
        when(frame.visibleVariables()).thenReturn(List.of(enabled));
        when(frame.getValues(List.of(enabled))).thenAnswer(call -> {
            assertThat(Thread.currentThread().getName()).contains("dispatcher");
            readStarted.countDown();
            assertThat(releaseRead.await(5, TimeUnit.SECONDS)).isTrue();
            return Map.of(enabled, yes);
        });
        sets.addAll(List.of(firstSet, secondSet, thirdSet));
        try {
            listener.start(target.vm);
            assertThat(readStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(queued.await(2, TimeUnit.SECONDS)).isTrue();
            final var session = tracker.suspensionSession();
            assertThat(session).isNotNull();
            assertThat(session.dispatcherInvocations()).isEmpty();
            assertThat(guard.isEvaluating(first.ref)).isFalse();
            final var watchdog = new SuspensionWatchdog(session, () -> 2 * SuspensionWatchdog.MIN_AGE_NANOS);
            watchdog.tick();
            watchdog.tick();
            assertThat(first.count).isEqualTo(3);
            assertThat(second.count).isEqualTo(2);
            assertThat(third.count).isEqualTo(2);
            verify(first.ref, never()).currentContendedMonitor();
            verify(secondSet, never()).resume();
            verify(thirdSet, never()).resume();
            verify(compiler, never()).configureCompilerClasspathForEvent(any());
            verify(compiler, never()).evaluate(any(), anyString(), anyMap());
            releaseRead.countDown();
            assertThat(listener.awaitIdle(2000)).isTrue();
            assertThat(history.getRecent(20)).extracting(EventHistory.DebugEvent::type)
                .contains("BREAKPOINT").doesNotContain("CONDITION_ERROR");
        } finally {
            releaseRead.countDown();
            listener.stop();
        }
    }

    /** An interpreted condition holds a single dispatcher lease for both calls and releases it on return. */
    @Test
    void invokesConditionUnderOneDispatcherLease() throws Exception {
        final FakeSuspendModel target = new FakeSuspendModel();
        final FakeSuspendModel.FakeThread thread = target.thread("event");
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = spy(new EvaluationGuard());
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final JdiEventListener listener = new JdiEventListener(tracker, history, compiler, guard, null,
            new MarkedInstanceRegistry(), new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO));
        final BlockingQueue<EventSet> sets = new LinkedBlockingQueue<>();
        final EventQueue queue = mock(EventQueue.class);
        when(target.vm.eventQueue()).thenReturn(queue);
        when(queue.remove()).thenAnswer(call -> sets.take());
        final BreakpointRequest request = mock(BreakpointRequest.class);
        final int id = tracker.registerBreakpoint(request);
        tracker.setCondition(id, "enabled() && enabled()");

        final EventSet set = target.breakpointEvent(thread, EventRequest.SUSPEND_EVENT_THREAD, request);
        final BreakpointEvent event = (BreakpointEvent) set.iterator().next();
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final StackFrame frame = mock(StackFrame.class);
        final Method method = mock(Method.class);
        final BooleanType type = mock(BooleanType.class);
        final BooleanValue yes = mock(BooleanValue.class);
        final ObjectReference self = mock(ObjectReference.class);
        final CountDownLatch called = new CountDownLatch(2);

        when(event.location()).thenReturn(location);
        when(thread.ref.frame(0)).thenReturn(frame);
        when(frame.location()).thenReturn(location);
        when(frame.thisObject()).thenReturn(self);
        when(location.declaringType()).thenReturn(owner);
        when(location.lineNumber()).thenReturn(10);
        when(owner.name()).thenReturn("example.App");
        when(owner.methods()).thenReturn(List.of(method));
        when(method.name()).thenReturn("enabled");
        when(method.signature()).thenReturn("()Z");
        when(method.returnType()).thenReturn(type);
        when(method.declaringType()).thenReturn(owner);
        when(type.name()).thenReturn("boolean");
        when(yes.value()).thenReturn(true);
        when(target.vm.mirrorOf(true)).thenReturn(yes);
        when(compiler.invocationLease(thread.ref)).thenAnswer(call -> guard.lease(thread.ref));
        when(self.invokeMethod(thread.ref, method, List.of(), ObjectReference.INVOKE_SINGLE_THREADED))
            .thenAnswer(call -> {
                assertThat(Thread.currentThread().getName()).contains("dispatcher");
                assertThat(guard.isEvaluating(thread.ref)).isTrue();
                final SuspensionSession session = tracker.suspensionSession();
                assertThat(session).isNotNull();
                assertThat(session.dispatcherInvocations()).hasSize(1);
                called.countDown();
                return yes;
            });

        try {
            listener.start(target.vm);
            sets.add(set);
            assertThat(called.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.awaitIdle(3000)).isTrue();
            verify(guard).lease(thread.ref);
            verify(compiler).invocationLease(thread.ref);
            verify(compiler, never()).configureCompilerClasspathForEvent(any());
            assertThat(guard.isEvaluating(thread.ref)).isFalse();
            final SuspensionSession session = tracker.suspensionSession();
            assertThat(session).isNotNull();
            assertThat(session.dispatcherInvocations()).isEmpty();
            assertThat(history.getRecent(20)).extracting(EventHistory.DebugEvent::type)
                .contains("BREAKPOINT").doesNotContain("CONDITION_ERROR");
        } finally {
            listener.stop();
        }
    }

    /** Publishes the fail-safe stop and releases its counts on reset after an oversized condition fails. */
    @Test
    void publishesTheFailSafeStopForAnOversizedCondition() throws Exception {
        final FakeSuspendModel target = new FakeSuspendModel();
        final FakeSuspendModel.FakeThread thread = target.thread("event");
        final FakeSuspendModel.FakeThread bystander = target.thread("bystander");
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = new EvaluationGuard();
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final JdiEventListener listener = new JdiEventListener(tracker, history, compiler, guard, null,
            new MarkedInstanceRegistry(), new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO));
        final LinkedBlockingQueue<EventSet> sets = new LinkedBlockingQueue<EventSet>();
        final EventQueue queue = mock(EventQueue.class);
        when(target.vm.eventQueue()).thenReturn(queue);
        when(queue.remove()).thenAnswer(call -> sets.take());

        final BreakpointRequest request = mock(BreakpointRequest.class);
        final int id = tracker.registerBreakpoint(request);
        tracker.setCondition(id, "1 + ".repeat(10000) + "1 == 10001");
        org.mockito.Mockito.doThrow(new StackOverflowError()).when(compiler).configureCompilerClasspathForEvent(thread.ref);
        final EventSet set = target.breakpointEvent(thread, EventRequest.SUSPEND_ALL, request);
        final BreakpointEvent event = (BreakpointEvent) set.iterator().next();
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        when(location.declaringType()).thenReturn(owner);
        when(location.lineNumber()).thenReturn(10);
        when(owner.name()).thenReturn("example.App");
        final CountDownLatch dispatchStarted = new CountDownLatch(1);
        when(event.location()).thenAnswer(call -> {
            if (Thread.currentThread().getName().contains("dispatcher")) {
                dispatchStarted.countDown();
            }
            return location;
        });
        try {
            listener.start(target.vm);
            sets.add(set);
            assertThat(dispatchStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(listener.awaitIdle(3000)).isTrue();
            final SuspensionSession session = tracker.suspensionSession();
            assertThat(session).isNotNull();
            final SoftAssertions checks = new SoftAssertions();
            checks.assertThat(history.getRecent(20)).extracting(EventHistory.DebugEvent::type)
                .contains("CONDITION_ERROR");
            checks.assertThat(session.presentCurrent()).as("published fail-safe stop").isNotNull();
            session.reset();
            checks.assertThat(bystander.count).as("bystander suspend count after reset").isZero();
            checks.assertAll();
        } finally {
            listener.stop();
        }
    }
    /** Records a thrown Error as a logpoint failure and settles the non-stopping event set. */
    @Test
    void releasesLogpointSetAfterEvaluationThrowsAnError() throws Exception {
        final FakeSuspendModel target = new FakeSuspendModel();
        final FakeSuspendModel.FakeThread thread = target.thread("event");
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final JdiExpressionEvaluator compiler = mock(JdiExpressionEvaluator.class);
        final JdiEventListener listener = new JdiEventListener(tracker, history, compiler, new EvaluationGuard(),
            null, new MarkedInstanceRegistry(), new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO));
        final BreakpointRequest request = mock(BreakpointRequest.class);
        final int id = tracker.registerBreakpoint(request);
        tracker.setLogpointExpression(id, "value()");
        final EventSet set = target.breakpointEvent(thread, EventRequest.SUSPEND_EVENT_THREAD, request);
        final BreakpointEvent event = (BreakpointEvent) set.iterator().next();
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        when(event.location()).thenReturn(location);
        when(location.declaringType()).thenReturn(owner);
        when(owner.name()).thenReturn("example.App");
        final EventQueue queue = mock(EventQueue.class);
        final LinkedBlockingQueue<EventSet> sets = new LinkedBlockingQueue<>();
        when(target.vm.eventQueue()).thenReturn(queue);
        when(queue.remove()).thenAnswer(call -> sets.take());
        org.mockito.Mockito.doThrow(new StackOverflowError()).when(compiler).configureCompilerClasspathForEvent(thread.ref);
        try {
            listener.start(target.vm);
            sets.add(set);
            verify(set, org.mockito.Mockito.timeout(3000)).resume();
            assertThat(listener.awaitIdle(3000)).isTrue();
            assertThat(thread.count).isZero();
            assertThat(history.getRecent(20)).anySatisfy(record -> {
                assertThat(record.type()).isEqualTo("LOGPOINT_ERROR");
                assertThat(record.summary()).contains("StackOverflowError");
            });
        } finally {
            listener.stop();
        }
    }
}
