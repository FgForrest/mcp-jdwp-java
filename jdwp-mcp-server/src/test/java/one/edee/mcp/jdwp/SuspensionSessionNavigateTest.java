package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.discovery.JvmDiscoveryService;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.interpret.EvaluationRouter;
import one.edee.mcp.jdwp.evaluation.LocalProjectClasspathProvider;
import one.edee.mcp.jdwp.fastlogpoint.FastLogpointService;
import one.edee.mcp.jdwp.marks.MarkedInstanceRegistry;
import one.edee.mcp.jdwp.watchers.WatcherManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Tests navigation on holds managed by the suspension session. */
class SuspensionSessionNavigateTest {

    /**
     * A test marks the test thread as processing an event set. Clear that mark, or later tests on the
     * same JVM thread would buffer their history entries into this test's session.
     */
    @AfterEach
    void endProcessing() {
        SuspensionSession.endProcessing();
    }

    /** Checks that navigation on each supported hold preserves its suspend count. */
    @Test
    void toolsNavigateManualBystanderMonitorAndWaitHoldsWithoutBorrowingCounts() throws Exception {
        final var target = new FakeSuspendModel();
        final var event = target.thread("event");
        final var manual = target.thread("manual");
        final var bystander = target.thread("bystander");
        final var monitor = target.thread("monitor");
        final var wait = target.thread("wait");
        monitor.status = ThreadReference.THREAD_STATUS_MONITOR;
        wait.status = ThreadReference.THREAD_STATUS_WAIT;

        final var tracker = new BreakpointTracker();
        final var history = new EventHistory();
        final var guard = new EvaluationGuard();
        final SuspensionSession session = tracker.openSuspensionSession(target.vm, history);
        guard.bind(session);
        final var connection = mock(JDIConnectionService.class);
        final var compiler = mock(JdiExpressionEvaluator.class);
        final var router = new EvaluationRouter(compiler, EvaluationRouter.Mode.AUTO);
        final var watchers = new WatcherManager();
        final var tools = new JDWPTools(connection, tracker, watchers, router, history, guard,
            mock(JvmDiscoveryService.class), new MarkedInstanceRegistry(), new JdiHealthMonitor(),
            mock(LocalProjectClasspathProvider.class), mock(FastLogpointService.class));
        when(connection.getVM()).thenReturn(target.vm);
        when(connection.formatFieldValue(any(IntegerValue.class))).thenAnswer(call ->
            Integer.toString(call.<IntegerValue>getArgument(0).value()));

        final IntegerValue two = mockInteger(2);
        final IntegerValue three = mockInteger(3);
        final IntegerValue one = mockInteger(1);
        when(target.vm.mirrorOf(1)).thenReturn(one);
        when(target.vm.mirrorOf(3)).thenReturn(three);
        for (var thread : List.of(manual, bystander, monitor, wait)) {
            stack(thread.ref, two);
        }

        tools.jdwp_suspend_thread(manual.ref.uniqueID());
        assertThat(tools.jdwp_evaluate_expression(manual.ref.uniqueID(), "amount + 1", null)).endsWith(": 3");
        assertThat(manual.count).isEqualTo(1);
        final var all = session.route(target.breakpointEvent(event, EventRequest.SUSPEND_ALL), event.ref, true);
        session.begin(all);
        session.beginProcessing(all);
        session.candidate(all, new BreakpointTracker.LastBreakpoint(event.ref, 42));
        session.finish(all);
        session.presentCurrent();
        for (var thread : List.of(bystander, monitor, wait)) {
            assertThat(tools.jdwp_assert_expression("amount + 1", "3", thread.ref.uniqueID(), 0)).startsWith("OK");
            assertThat(thread.count).isEqualTo(1);
        }
        watchers.createWatcher("amount", 42, "amount + 1");
        assertThat(tools.jdwp_evaluate_watchers(bystander.ref.uniqueID(), "current_frame", 42))
            .contains("amount + 1 = 3");
        assertThat(manual.count).isEqualTo(2);
        assertThat(bystander.count).isEqualTo(1);
        assertThat(event.count).isEqualTo(1);
        assertThat(session.dispatcherInvocations()).isEmpty();
        verifyNoInteractions(compiler);

        assertThat(tools.jdwp_evaluate_expression(monitor.ref.uniqueID(), "amount()", 0))
            .contains("not interpreted: method call");
        assertThat(monitor.count).isEqualTo(1);
    }

    private static IntegerValue mockInteger(int number) {
        final IntegerValue value = mock(IntegerValue.class);
        when(value.value()).thenReturn(number);
        return value;
    }

    private static void stack(ThreadReference thread, IntegerValue value) throws Exception {
        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final LocalVariable amount = mock(LocalVariable.class);
        final IntegerType type = mock(IntegerType.class);
        when(type.name()).thenReturn("int");
        when(owner.name()).thenReturn("example.App");
        when(location.declaringType()).thenReturn(owner);
        when(frame.location()).thenReturn(location);
        when(frame.visibleVariables()).thenReturn(List.of(amount));
        when(amount.name()).thenReturn("amount");
        when(amount.type()).thenReturn(type);
        when(frame.getValues(List.of(amount))).thenReturn(Map.of(amount, value));
        when(thread.frameCount()).thenReturn(1);
        when(thread.frames(0, 1)).thenReturn(List.of(frame));
        when(thread.frame(0)).thenReturn(frame);
    }

    /** Refuses a result that mixes pre-resume locals with post-resume fields. */
    @Test
    void refusesWhenTheDispatcherResumesTheBystanderMidPlan() throws Exception {
        final FakeSuspendModel target = new FakeSuspendModel();
        final FakeSuspendModel.FakeThread event = target.thread("event");
        final FakeSuspendModel.FakeThread thread = target.thread("bystander");
        final BreakpointTracker tracker = new BreakpointTracker();
        final EventHistory history = new EventHistory();
        final EvaluationGuard guard = new EvaluationGuard();
        final SuspensionSession session = tracker.openSuspensionSession(target.vm, history);
        final SuspensionSession.Context context = session.route(target.breakpointEvent(event, EventRequest.SUSPEND_ALL), event.ref, true);
        session.begin(context);

        final JDIConnectionService connection = mock(JDIConnectionService.class);
        when(connection.getVM()).thenReturn(target.vm);
        final EvaluationRouter router = new EvaluationRouter(mock(JdiExpressionEvaluator.class), EvaluationRouter.Mode.AUTO);
        final JDWPTools tools = new JDWPTools(connection, tracker, new WatcherManager(), router, history, guard,
            mock(JvmDiscoveryService.class), new MarkedInstanceRegistry(), new JdiHealthMonitor(),
            mock(LocalProjectClasspathProvider.class), mock(FastLogpointService.class));

        final StackFrame frame = mock(StackFrame.class);
        final Location location = mock(Location.class);
        final ClassType owner = mock(ClassType.class);
        final ObjectReference self = mock(ObjectReference.class);
        final LocalVariable local = mock(LocalVariable.class);
        final Field field = mock(Field.class);
        final IntegerType intType = mock(IntegerType.class);
        final IntegerValue before = mock(IntegerValue.class);
        final IntegerValue after = mock(IntegerValue.class);
        final IntegerValue mixed = mock(IntegerValue.class);

        when(thread.ref.frameCount()).thenReturn(1);
        when(thread.ref.frames(0, 1)).thenReturn(List.of(frame));
        when(thread.ref.frame(0)).thenReturn(frame);
        when(frame.location()).thenAnswer(call -> {
            if (thread.count == 0) {
                throw new InvalidStackFrameException("bystander resumed");
            }
            return location;
        });
        when(frame.thisObject()).thenReturn(self);
        when(frame.visibleVariables()).thenReturn(List.of(local));
        when(frame.getValues(List.of(local))).thenReturn(Map.of(local, before));
        when(local.name()).thenReturn("cached");
        when(local.type()).thenReturn(intType);
        when(intType.name()).thenReturn("int");
        when(location.declaringType()).thenReturn(owner);
        when(owner.name()).thenReturn("example.App");
        when(owner.fields()).thenReturn(List.of(field));
        when(field.name()).thenReturn("amount");
        when(field.declaringType()).thenReturn(owner);
        when(field.type()).thenAnswer(call -> {
            // A false condition finished on the event thread. It was the only hold on this bystander.
            session.finish(context);
            assertThat(thread.count).isZero();
            return intType;
        });
        when(before.value()).thenReturn(1);
        when(after.value()).thenReturn(20);
        when(self.getValue(field)).thenAnswer(call -> thread.count == 0 ? after : before);
        when(target.vm.mirrorOf(21)).thenReturn(mixed);
        when(connection.formatFieldValue(mixed)).thenReturn("21");

        assertThat(tools.jdwp_evaluate_expression(thread.ref.uniqueID(), "cached + this.amount", null))
            .startsWith("Error:");
    }
}
