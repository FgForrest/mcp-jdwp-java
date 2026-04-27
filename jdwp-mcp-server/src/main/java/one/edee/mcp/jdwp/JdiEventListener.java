package one.edee.mcp.jdwp;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Drains the JDI event queue on a dedicated daemon thread named `jdi-event-listener`. The MCP
 * server MUST consume every event the target VM emits — JDI blocks the target on a full event
 * queue, so this listener has to keep up.
 * <p>
 * Per-event behaviour:
 * - `BreakpointEvent` → record to {@link EventHistory}, update {@link BreakpointTracker#setLastBreakpointThread},
 * fire the resume-until-event latch, and KEEP the thread suspended (auto-resume only for logpoints
 * and false conditional breakpoints).
 * - `StepEvent` → delete the one-shot StepRequest (JDI requirement), record, fire the latch.
 * - `ExceptionEvent` → record with throw/catch metadata, set `lastBreakpointThread` with sentinel
 * BP id `-1`, fire the latch.
 * - `ClassPrepareEvent` → promote pending line and exception breakpoints for the loaded class.
 * - `VMStartEvent` → keep the VM suspended so the user can finish wiring up breakpoints.
 * - `VMDisconnectEvent` / `VMDeathEvent` → exit the loop.
 * <p>
 * Promotion contract: the main listener loop does not call
 * {@link BreakpointTracker#tryPromotePending(JDIConnectionService, ThreadReference)} directly —
 * promotion happens lazily via {@link JDIConnectionService#getVM()} on the next MCP tool call.
 * Logpoint and conditional-BP handlers DO end up calling it indirectly the first time after
 * connect (via {@code configureCompilerClasspath} → {@code discoverClasspath} → {@code getVM}),
 * which is safe because the BP thread is already suspended at a method-invocation event —
 * JDI permits {@code invokeMethod} on threads in that state.
 */
@Service
public class JdiEventListener {
    private static final Logger log = LoggerFactory.getLogger(JdiEventListener.class);

    private final BreakpointTracker breakpointTracker;
    private final EventHistory eventHistory;
    private final JdiExpressionEvaluator expressionEvaluator;
    private final EvaluationGuard evaluationGuard;
    /**
     * Daemon thread running {@link #listen}; replaced on each {@link #start}, nulled by {@link #stop}.
     */
    @Nullable
    private volatile Thread listenerThread;
    /**
     * Loop control flag; flipped to false by {@link #stop} or by VM disconnect/death events.
     */
    private volatile boolean running;

    public JdiEventListener(BreakpointTracker breakpointTracker, EventHistory eventHistory,
                            @Lazy JdiExpressionEvaluator expressionEvaluator,
                            EvaluationGuard evaluationGuard) {
        this.breakpointTracker = breakpointTracker;
        this.eventHistory = eventHistory;
        this.expressionEvaluator = expressionEvaluator;
        this.evaluationGuard = evaluationGuard;
    }

    /**
     * Formats a JDI value for the human-readable logpoint output. Mirrors the format used by
     * {@link JDIConnectionService#formatFieldValue} but without its side effects (no object cache
     * insertion, no primitive unboxing) — logpoint output only needs to be a string for the event
     * history, so the simpler implementation is preferred.
     */
    private static String formatLogpointResult(@Nullable Value value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof StringReference strRef) {
            return strRef.value();
        }
        if (value instanceof PrimitiveValue) {
            return value.toString();
        }
        if (value instanceof ObjectReference objRef) {
            return String.format("Object#%d (%s)", objRef.uniqueID(), objRef.referenceType().name());
        }
        return value.toString();
    }

    /**
     * Spawns a fresh daemon listener thread for `vm`. Calls {@link #stop()} first so there is at
     * most one listener active at any time. The thread is a daemon — it does not block JVM shutdown.
     */
    public void start(VirtualMachine vm) {
        stop(); // clean up any previous listener

        running = true;
        final Thread thread = new Thread(() -> listen(vm), "jdi-event-listener");
        thread.setDaemon(true);
        listenerThread = thread;
        thread.start();
        log.info("[JDI] Event listener started");
    }

    /**
     * Best-effort interrupt of the listener thread. The {@link #listen} loop exits on
     * `VMDisconnectedException`, an `InterruptedException`, or {@link #running} becoming false.
     */
    public void stop() {
        running = false;
        final Thread thread = listenerThread;
        if (thread != null) {
            thread.interrupt();
            listenerThread = null;
        }
    }

    /**
     * Main event loop. Events that require user inspection (breakpoints, steps, exceptions) cause
     * the entire `EventSet` to stay suspended; logpoints and false conditional breakpoints reach
     * the bottom of the loop with `shouldSuspend == false` and the set is `eventSet.resume()`d.
     * Note that `resume()` is only called when NO event in the set demanded suspension — even one
     * suspending event keeps every thread in the set parked for user inspection.
     */
    private void listen(VirtualMachine vm) {
        final EventQueue queue = vm.eventQueue();

        while (running) {
            try {
                final EventSet eventSet = queue.remove();
                boolean shouldSuspend = false;

                for (Event event : eventSet) {
                    if (event instanceof BreakpointEvent bpEvent) {
                        if (handleBreakpointEvent(bpEvent)) {
                            shouldSuspend = true;
                        }
                    } else if (event instanceof StepEvent stepEvent) {
                        if (handleStepEvent(stepEvent)) {
                            shouldSuspend = true;
                        }
                    } else if (event instanceof ExceptionEvent exEvent) {
                        if (handleExceptionEvent(exEvent)) {
                            shouldSuspend = true;
                        }
                    } else if (event instanceof ClassPrepareEvent cpEvent) {
                        handleClassPrepareEvent(cpEvent);
                    } else if (event instanceof VMStartEvent) {
                        log.info("[JDI] VM started — keeping suspended for breakpoint setup");
                        eventHistory.record(new EventHistory.DebugEvent("VM_START", "VM started"));
                        shouldSuspend = true;
                    } else if (event instanceof VMDisconnectEvent || event instanceof VMDeathEvent) {
                        log.info("[JDI] VM disconnected/died, stopping event listener");
                        eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected/died"));
                        // Wake any caller parked on jdwp_resume_until_event so they detect the dead
                        // VM promptly instead of timing out (FINDING-7).
                        breakpointTracker.fireNextEvent();
                        running = false;
                        return;
                    }
                }

                if (!shouldSuspend) {
                    eventSet.resume();
                }
                // NOTE: the main loop does not call tryPromotePending directly — promotion is
                // deferred to JDIConnectionService.getVM() on the next MCP tool call. Logpoint
                // and conditional-BP handlers DO end up invoking it indirectly the first time
                // after connect (configureCompilerClasspath → discoverClasspath → getVM →
                // tryPromotePending), which is safe because the BP thread is suspended at a
                // method-invocation event — JDI permits invokeMethod in that state.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (VMDisconnectedException e) {
                log.info("[JDI] VM disconnected, stopping event listener");
                // Wake any caller parked on jdwp_resume_until_event so they detect the dead VM
                // promptly instead of timing out (FINDING-7).
                breakpointTracker.fireNextEvent();
                break;
            } catch (Exception e) {
                if (running) {
                    log.warn("[JDI] Error processing event: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Dispatches a breakpoint hit and returns whether the thread should remain suspended.
     * <p>
     * Auto-resume cases (returns `false`):
     * 1. Logpoint — expression is evaluated and recorded; the thread continues without notifying the user.
     * 2. Conditional breakpoint with `condition == false` (the fail-safe path is in {@link #evaluateCondition}).
     * <p>
     * Suspending cases (returns `true`):
     * - Untracked breakpoints (defensive — shouldn't happen but we err on the side of inspection).
     * - Plain breakpoints with no condition or with a true condition.
     * - Any internal error during evaluation.
     */
    private boolean handleBreakpointEvent(BreakpointEvent event) {
        try {
            // Reentrancy guard: if the firing thread is already executing an MCP-driven
            // invokeMethod chain (expression evaluation, logpoint expression, conditional BP
            // expression, jdwp_to_string, force-load, classpath discovery), suppress the
            // recursive hit and auto-resume. Otherwise the outer invokeMethod would wait
            // forever for a thread we just re-suspended — that is the deadlock the guard
            // exists to prevent. Must run BEFORE setLastBreakpointThread / fireNextEvent so
            // the suppressed event does not clobber the user's current context or wake a
            // waiter on jdwp_resume_until_event.
            if (evaluationGuard.isEvaluating(event.thread())) {
                final String className = event.location().declaringType().name();
                final int lineNumber = event.location().lineNumber();
                eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT_SUPPRESSED",
                    String.format("Recursive BP at %s:%d suppressed (thread '%s' inside MCP evaluation)",
                        className, lineNumber, event.thread().name()),
                    Map.of("class", className, "line", String.valueOf(lineNumber),
                        "thread", event.thread().name())));
                log.info("[JDI] Suppressing recursive BP at {}:{} on thread {} (inside MCP evaluation)",
                    className, lineNumber, event.thread().name());
                return false;
            }

            final BreakpointRequest request = (BreakpointRequest) event.request();
            final Integer bpId = breakpointTracker.findIdByRequest(request);

            if (bpId == null) {
                log.warn("[JDI] Untracked breakpoint hit at {}:{}",
                    event.location().declaringType().name(), event.location().lineNumber());
                return true;
            }

            breakpointTracker.setLastBreakpointThread(event.thread(), bpId);

            final String className = event.location().declaringType().name();
            final int lineNumber = event.location().lineNumber();
            final String threadName = event.thread().name();

            final String logpointExpr = breakpointTracker.getLogpointExpression(bpId);
            final String condition = breakpointTracker.getCondition(bpId);

            // Check if this is a logpoint — evaluate expression, record output, auto-resume.
            // If a condition is also set, evaluate it first and skip logging when false.
            if (logpointExpr != null) {
                if (condition != null && !evaluateCondition(event, condition)) {
                    log.debug("[JDI] Conditional logpoint {} at {}:{} — condition false, skipping",
                        bpId, className, lineNumber);
                    return false;
                }
                evaluateLogpoint(event, bpId, logpointExpr, className, lineNumber, threadName);
                return false;
            }

            // Check if this has a condition — evaluate and resume if false
            if (condition != null) {
                final boolean conditionResult = evaluateCondition(event, condition);
                if (!conditionResult) {
                    log.debug("[JDI] Conditional breakpoint {} at {}:{} — condition false, resuming",
                        bpId, className, lineNumber);
                    return false;
                }
            }

            // Normal breakpoint — record event and keep suspended
            eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT",
                String.format("Breakpoint %d hit at %s:%d on thread %s", bpId, className, lineNumber, threadName),
                Map.of("breakpointId", String.valueOf(bpId), "class", className,
                    "line", String.valueOf(lineNumber), "thread", threadName)));

            log.info("[JDI] Breakpoint {} hit on thread {} at {}:{}", bpId, threadName, className, lineNumber);
            breakpointTracker.fireNextEvent();
            return true;

        } catch (Exception e) {
            log.warn("[JDI] Error handling breakpoint event: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Handles a single-step event by deleting the one-shot {@link StepRequest}
     * (JDI convention — step requests are consumed after firing), recording a `STEP` event in
     * {@link EventHistory}, and firing the resume-until-event latch so a waiting `jdwp_resume_until_event`
     * call can return.
     *
     * <p>Returns {@code true} when the firing thread should stay suspended, {@code false} when the
     * step event was suppressed by the reentrancy guard (defensive — JDI does not deliver step
     * events from inside {@code invokeMethod} in practice, but the guard covers the case anyway).
     */
    private boolean handleStepEvent(StepEvent event) {
        // Some legacy JDI providers deliver step events with a null request reference (already
        // auto-removed by the provider) — guard before calling deleteEventRequest.
        if (event.request() != null) {
            event.request().virtualMachine().eventRequestManager().deleteEventRequest(event.request());
        }

        // Reentrancy guard — see handleBreakpointEvent for the rationale. JDI is not supposed to
        // deliver step events during invokeMethod but we suppress defensively so a future JDI
        // implementation change can never turn this path into a deadlock.
        if (evaluationGuard.isEvaluating(event.thread())) {
            try {
                final String className = event.location().declaringType().name();
                final int lineNumber = event.location().lineNumber();
                eventHistory.record(new EventHistory.DebugEvent("STEP_SUPPRESSED",
                    String.format("Step at %s:%d suppressed (thread '%s' inside MCP evaluation)",
                        className, lineNumber, event.thread().name()),
                    Map.of("class", className, "line", String.valueOf(lineNumber),
                        "thread", event.thread().name())));
                log.info("[JDI] Suppressing step event at {}:{} on thread {} (inside MCP evaluation)",
                    className, lineNumber, event.thread().name());
            } catch (Exception e) {
                log.debug("[JDI] Error recording suppressed step event: {}", e.getMessage());
            }
            return false;
        }

        try {
            final String className = event.location().declaringType().name();
            final int lineNumber = event.location().lineNumber();
            final String threadName = event.thread().name();

            eventHistory.record(new EventHistory.DebugEvent("STEP",
                String.format("Step to %s:%d on thread %s", className, lineNumber, threadName),
                Map.of("class", className, "line", String.valueOf(lineNumber), "thread", threadName)));
            breakpointTracker.fireNextEvent();
        } catch (Exception e) {
            log.debug("[JDI] Error recording step event: {}", e.getMessage());
        }
        return true;
    }

    /**
     * Handles a JDI exception event. Three paths:
     * <ul>
     *   <li><b>Reentrancy-suppressed</b> — the firing thread is already inside an MCP-driven
     *       {@code invokeMethod} chain. Records {@code EXCEPTION_SUPPRESSED} and returns
     *       {@code false} so the listener auto-resumes; otherwise the outer invocation would hang
     *       on a re-suspended thread. The exception still propagates back to the original caller
     *       via the usual JDI {@link InvocationException} channel, so no information is lost.</li>
     *   <li><b>Log-only</b> — the firing {@link BreakpointTracker.ExceptionBreakpointInfo} has
     *       {@code logOnly=true}. Records {@code EXCEPTION_LOG} (with the optional expression's
     *       result, or {@code EXCEPTION_LOG_ERROR} on evaluation failure) and returns
     *       {@code false} so the throwing thread auto-resumes — non-intrusive exception tracing.</li>
     *   <li><b>Suspending (default)</b> — records {@code EXCEPTION}, parks the firing thread under
     *       the sentinel BP id {@code -1} so {@code jdwp_get_current_thread} can locate it, fires
     *       the resume-until-event latch, and returns {@code true}.</li>
     * </ul>
     */
    private boolean handleExceptionEvent(ExceptionEvent event) {
        // Reentrancy guard — see contract above.
        if (evaluationGuard.isEvaluating(event.thread())) {
            try {
                final String exceptionType = event.exception().referenceType().name();
                final String threadName = event.thread().name();
                eventHistory.record(new EventHistory.DebugEvent("EXCEPTION_SUPPRESSED",
                    String.format("Exception %s on thread '%s' suppressed (inside MCP evaluation)",
                        exceptionType, threadName),
                    Map.of("exceptionType", exceptionType, "thread", threadName)));
                log.info("[JDI] Suppressing exception {} on thread {} (inside MCP evaluation)",
                    exceptionType, threadName);
            } catch (Exception e) {
                log.debug("[JDI] Error recording suppressed exception event: {}", e.getMessage());
            }
            return false;
        }

        try {
            final ObjectReference exception = event.exception();
            final Location throwLocation = event.location();
            final Location catchLocation = event.catchLocation();

            final String exceptionType = exception.referenceType().name();
            final String throwInfo = throwLocation != null
                ? throwLocation.declaringType().name() + ':' + throwLocation.lineNumber() : "unknown";
            final String catchInfo = catchLocation != null
                ? catchLocation.declaringType().name() + ':' + catchLocation.lineNumber() : "uncaught";
            final String threadName = event.thread().name();

            // Look up the firing BP record so we can decide between log-only and suspending mode.
            // event.request() may be null on synthesised events (defensive — JDI normally fills it).
            final BreakpointTracker.ExceptionBreakpointInfo info =
                event.request() instanceof ExceptionRequest exReq
                    ? breakpointTracker.findExceptionInfoByRequest(exReq) : null;

            if (info != null && info.isLogOnly()) {
                evaluateExceptionLogpoint(event, info, exception, exceptionType,
                    throwInfo, catchInfo, threadName);
                return false;
            }

            breakpointTracker.setLastBreakpointThread(event.thread(), -1);
            breakpointTracker.fireNextEvent();

            eventHistory.record(new EventHistory.DebugEvent("EXCEPTION",
                String.format("%s thrown at %s, caught at %s on thread %s",
                    exceptionType, throwInfo, catchInfo, threadName),
                Map.of("exceptionType", exceptionType, "throwLocation", throwInfo,
                    "catchLocation", catchInfo, "thread", threadName)));

            log.info("[JDI] Exception {} thrown at {}, caught at {} on thread {}",
                exceptionType, throwInfo, catchInfo, threadName);
        } catch (Exception e) {
            log.warn("[JDI] Error handling exception event: {}", e.getMessage());
        }
        return true;
    }

    /**
     * Records an {@code EXCEPTION_LOG} entry for a log-only exception breakpoint and, if the BP
     * carries an expression, evaluates it against the throwing frame with {@code $exception} bound
     * to the thrown object. Evaluation failures (compilation error, target-VM exception during
     * {@code invokeMethod}, etc.) are captured as a separate {@code EXCEPTION_LOG_ERROR} entry —
     * the listener never throws so a single bad expression cannot break the event loop.
     *
     * <p>Runs on the JDI listener thread, mirroring {@link #evaluateLogpoint}: this is safe
     * because we are inside a synchronously-dispatched event handler, not inside a JDI callback,
     * so {@code invokeMethod} is permitted on the suspended firing thread.
     */
    private void evaluateExceptionLogpoint(ExceptionEvent event,
                                           BreakpointTracker.ExceptionBreakpointInfo info,
                                           ObjectReference exception, String exceptionType,
                                           String throwInfo, String catchInfo, String threadName) {
        final String expression = info.getExpression();

        if (expression == null) {
            // Log-only with no expression — same metadata as EXCEPTION, distinct type so callers
            // can grep `EXCEPTION_LOG` for non-suspending traces.
            eventHistory.record(new EventHistory.DebugEvent("EXCEPTION_LOG",
                String.format("%s thrown at %s, caught at %s on thread %s [log-only]",
                    exceptionType, throwInfo, catchInfo, threadName),
                Map.of("exceptionType", exceptionType, "throwLocation", throwInfo,
                    "catchLocation", catchInfo, "thread", threadName)));
            log.info("[JDI] Exception {} logged at {} (log-only) on thread {}",
                exceptionType, throwInfo, threadName);
            return;
        }

        try {
            final String resultStr = evaluateAndFormat(event.thread(), expression,
                Map.of("$exception", exception));

            eventHistory.record(new EventHistory.DebugEvent("EXCEPTION_LOG",
                String.format("%s thrown at %s on thread %s [%s = %s]",
                    exceptionType, throwInfo, threadName, expression, resultStr),
                Map.of("exceptionType", exceptionType, "throwLocation", throwInfo,
                    "catchLocation", catchInfo, "thread", threadName,
                    "expression", expression, "result", resultStr)));
            log.info("[JDI] Exception {} logged at {} with {} = {} on thread {}",
                exceptionType, throwInfo, expression, resultStr, threadName);
        } catch (Exception e) {
            eventHistory.record(new EventHistory.DebugEvent("EXCEPTION_LOG_ERROR",
                String.format("%s thrown at %s on thread %s — error evaluating '%s': %s",
                    exceptionType, throwInfo, threadName, expression, e.getMessage()),
                Map.of("exceptionType", exceptionType, "throwLocation", throwInfo,
                    "thread", threadName, "expression", expression,
                    "error", String.valueOf(e.getMessage()))));
            log.warn("[JDI] Exception logpoint evaluation failed for '{}': {}",
                expression, e.getMessage());
        }
    }

    /**
     * Evaluates the logpoint expression and records a `LOGPOINT` (or `LOGPOINT_ERROR`) entry. Runs
     * on the JDI listener thread; this is safe because we're inside a synchronously-dispatched
     * event handler — JDI's prohibition on `invokeMethod` applies to event-dispatch callbacks, not
     * to method invocations made while the listener thread is processing a drained event.
     * <p>
     * Never throws — any evaluation failure is captured as a `LOGPOINT_ERROR` entry so the user
     * sees the failure in `jdwp_get_events` instead of a silently dropped event.
     */
    private void evaluateLogpoint(BreakpointEvent event, int bpId, String expression,
                                  String className, int lineNumber, String threadName) {
        try {
            final String resultStr = evaluateAndFormat(event.thread(), expression, Map.of());

            eventHistory.record(new EventHistory.DebugEvent("LOGPOINT",
                String.format("[Logpoint %d] %s = %s at %s:%d", bpId, expression, resultStr, className, lineNumber),
                Map.of("breakpointId", String.valueOf(bpId), "expression", expression,
                    "result", resultStr, "class", className,
                    "line", String.valueOf(lineNumber), "thread", threadName)));

            log.info("[JDI] Logpoint {} evaluated: {} = {}", bpId, expression, resultStr);
        } catch (Exception e) {
            eventHistory.record(new EventHistory.DebugEvent("LOGPOINT_ERROR",
                String.format("[Logpoint %d] Error evaluating '%s': %s", bpId, expression, e.getMessage())));
            log.warn("[JDI] Logpoint {} evaluation failed: {}", bpId, e.getMessage());
        }
    }

    /**
     * Configures the compiler classpath, takes the top frame of {@code thread}, evaluates
     * {@code expression} with the supplied synthetic bindings, and returns the formatted result.
     * Shared between {@link #evaluateLogpoint} and {@link #evaluateExceptionLogpoint} — both want
     * the same evaluate-and-format pipeline; only the recorded event types and metadata differ.
     * Throws on any failure so the caller can produce a {@code *_ERROR} event entry.
     */
    private String evaluateAndFormat(ThreadReference thread, String expression,
                                     Map<String, Value> extraBindings) throws Exception {
        expressionEvaluator.configureCompilerClasspath(thread);
        final StackFrame frame = thread.frame(0);
        final Value result = expressionEvaluator.evaluate(frame, expression, extraBindings);
        return formatLogpointResult(result);
    }

    /**
     * Evaluates a conditional breakpoint expression at frame 0. Fail-safe policy: any error
     * (compilation failure, non-boolean result, exception during execution) returns `true` so the
     * user sees the breakpoint hit and can investigate the problem rather than silently skipping
     * the BP. Recognises both primitive `BooleanValue` and the boxed `java.lang.Boolean` returned
     * via the wrapper class's `(Object)(...)` autoboxing cast.
     */
    private boolean evaluateCondition(BreakpointEvent event, String condition) {
        try {
            final ThreadReference thread = event.thread();
            expressionEvaluator.configureCompilerClasspath(thread);
            final StackFrame frame = thread.frame(0);
            final Value result = expressionEvaluator.evaluate(frame, condition);

            // Direct boolean primitive (unlikely due to autoboxing in wrapper)
            if (result instanceof BooleanValue boolVal) {
                return boolVal.value();
            }

            // Boxed java.lang.Boolean — read internal 'value' field
            if (result instanceof ObjectReference objRef
                && "java.lang.Boolean".equals(objRef.referenceType().name())) {
                final Field valueField = objRef.referenceType().fieldByName("value");
                if (valueField != null) {
                    final Value innerValue = objRef.getValue(valueField);
                    if (innerValue instanceof BooleanValue boolVal) {
                        return boolVal.value();
                    }
                }
            }

            log.warn("[JDI] Condition '{}' returned non-boolean: {}. Suspending.", condition, result);
            return true;
        } catch (Exception e) {
            log.warn("[JDI] Error evaluating condition '{}': {}. Suspending.", condition, e.getMessage());
            return true;
        }
    }

    /**
     * Promotes pending line breakpoints AND pending exception breakpoints for the freshly loaded
     * class. After promotion, deletes the {@link ClassPrepareRequest} for this class if no other
     * pending items still reference it — keeping a stale CPR around would deliver duplicate events
     * for unrelated classes that happen to share the same name pattern.
     */
    private void handleClassPrepareEvent(ClassPrepareEvent event) {
        try {
            final ReferenceType refType = event.referenceType();
            final String className = refType.name();

            final List<Map.Entry<Integer, BreakpointTracker.PendingBreakpoint>> pendingList =
                breakpointTracker.getPendingBreakpointsForClass(className);
            final List<Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint>> pendingExList =
                breakpointTracker.getPendingExceptionBreakpointsForClass(className);

            if (pendingList.isEmpty() && pendingExList.isEmpty()) {
                return;
            }

            log.info("[JDI] ClassPrepareEvent for '{}', activating {} deferred breakpoint(s) and {} deferred exception breakpoint(s)",
                className, pendingList.size(), pendingExList.size());

            final VirtualMachine vm = event.virtualMachine();
            final EventRequestManager erm = vm.eventRequestManager();

            for (Map.Entry<Integer, BreakpointTracker.PendingBreakpoint> entry : pendingList) {
                final int id = entry.getKey();
                final BreakpointTracker.PendingBreakpoint pending = entry.getValue();

                try {
                    final List<Location> locations = refType.locationsOfLine(pending.getLineNumber());
                    if (locations.isEmpty()) {
                        final String reason = String.format("No executable code at line %d in %s", pending.getLineNumber(), className);
                        breakpointTracker.markPendingFailed(id, reason);
                        log.warn("[JDI] Deferred breakpoint {} failed: {}", id, reason);
                        continue;
                    }

                    final Location location = locations.get(0);
                    final BreakpointRequest bpRequest = erm.createBreakpointRequest(location);
                    bpRequest.setSuspendPolicy(pending.getSuspendPolicy());
                    bpRequest.enable();

                    breakpointTracker.promotePendingToActive(id, bpRequest);
                    log.info("[JDI] Deferred breakpoint {} activated at {}:{}", id, className, pending.getLineNumber());

                } catch (AbsentInformationException e) {
                    breakpointTracker.markPendingFailed(id, "No debug info (compile with -g)");
                    log.warn("[JDI] Deferred breakpoint {} failed: no debug info for {}", id, className);
                } catch (Exception e) {
                    breakpointTracker.markPendingFailed(id, e.getMessage());
                    log.warn("[JDI] Deferred breakpoint {} failed: {}", id, e.getMessage());
                }
            }

            // Also activate any deferred exception breakpoints for this class
            for (Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint> entry : pendingExList) {
                final int id = entry.getKey();
                final BreakpointTracker.PendingExceptionBreakpoint pending = entry.getValue();
                try {
                    final ExceptionRequest exReq = erm.createExceptionRequest(refType, pending.isCaught(), pending.isUncaught());
                    // Always SUSPEND_EVENT_THREAD: logOnly BPs are auto-resumed by handleExceptionEvent
                    // after recording / optional expression evaluation. SUSPEND_NONE would prevent
                    // invokeMethod for the expression eval.
                    exReq.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    exReq.enable();
                    breakpointTracker.promotePendingExceptionToActive(id, exReq);
                    log.info("[JDI] Deferred exception breakpoint {} activated for {}", id, className);
                } catch (Exception e) {
                    breakpointTracker.markPendingExceptionFailed(id, e.getMessage());
                    log.warn("[JDI] Deferred exception breakpoint {} failed: {}", id, e.getMessage());
                }
            }

            // Clean up ClassPrepareRequest if no more pending (line OR exception) BPs for this class
            if (breakpointTracker.getPendingBreakpointsForClass(className).isEmpty()
                && breakpointTracker.getPendingExceptionBreakpointsForClass(className).isEmpty()) {
                final ClassPrepareRequest cpr = breakpointTracker.removeClassPrepareRequest(className);
                if (cpr != null) {
                    erm.deleteEventRequest(cpr);
                }
            }

        } catch (Exception e) {
            log.warn("[JDI] Error handling ClassPrepareEvent: {}", e.getMessage());
        }
    }
}
