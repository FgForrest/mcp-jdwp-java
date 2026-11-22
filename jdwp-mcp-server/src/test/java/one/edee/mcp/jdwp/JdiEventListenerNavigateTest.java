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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
}
