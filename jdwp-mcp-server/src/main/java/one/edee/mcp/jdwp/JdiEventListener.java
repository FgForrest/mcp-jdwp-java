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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * - `VMDisconnectEvent` / `VMDeathEvent` → record `VM_DEATH`, fire the resume-until-event latch so
 * any parked `jdwp_resume_until_event` caller detects the dead VM promptly, then invoke the optional
 * {@link #vmDeathHook} before exiting the loop. A {@code VMDisconnectedException} raised by the JDI
 * event queue follows the same contract — abrupt target exit is handled symmetrically to a graceful
 * death event.
 * <p>
 * Promotion contract: the main listener loop does not call
 * {@link BreakpointTracker#tryPromotePending(JDIConnectionService, ThreadReference)} directly —
 * promotion happens lazily via {@link JDIConnectionService#getVM()} on the next MCP tool call.
 * Logpoint and conditional-BP handlers DO end up calling it indirectly the first time after
 * connect (via {@code configureCompilerClasspath} → {@code discoverClasspath} → {@code getVM}),
 * which is safe because the BP thread is already suspended at a method-invocation event —
 * JDI permits {@code invokeMethod} on threads in that state.
 * <p>
 * Chain handling: after every BP, exception, or auto-resumed logpoint hit,
 * {@link #applyChainEffectsAfterHit} arms any dependent BPs waiting on the firing BP and
 * re-disarms one-shot dependents — the firing event itself is unchanged; chain side-effects
 * are an additive concern.
 */
@Service
public class JdiEventListener {
    private static final Logger log = LoggerFactory.getLogger(JdiEventListener.class);

    private final BreakpointTracker breakpointTracker;
    private final EventHistory eventHistory;
    private final JdiExpressionEvaluator expressionEvaluator;
    private final EvaluationGuard evaluationGuard;
    /**
     * Late-bound handle to the connection service so {@link #handleClassPrepareEvent} can promote
     * pending field watchpoints synchronously on class load. {@code @Lazy} because
     * {@code JDIConnectionService} itself depends on this listener (it wires the VM-death hook)
     * and Spring would otherwise reject the circular constructor injection. {@code null} in tests
     * that build the listener directly — the field-promotion loop short-circuits when it's missing.
     */
    @Nullable
    private final JDIConnectionService jdiConnectionService;
    @Nullable
    private volatile Thread listenerThread;
    private volatile boolean running;
    /**
     * Optional callback invoked exactly once when the listener observes VM death or disconnect.
     * Left null by tests that drive the listener directly — death becomes a silent loop exit.
     */
    @Nullable
    private volatile Runnable vmDeathHook;
    /**
     * Idempotence gate so concurrent death observers (graceful in-loop event, disconnect exception,
     * external {@code stop()}) collapse into at most one {@link #vmDeathHook} invocation per session.
     */
    private final AtomicBoolean vmDeathHookInvoked = new AtomicBoolean(false);

    public JdiEventListener(BreakpointTracker breakpointTracker, EventHistory eventHistory,
                            @Lazy JdiExpressionEvaluator expressionEvaluator,
                            EvaluationGuard evaluationGuard,
                            @Lazy @Nullable JDIConnectionService jdiConnectionService) {
        this.breakpointTracker = breakpointTracker;
        this.eventHistory = eventHistory;
        this.expressionEvaluator = expressionEvaluator;
        this.evaluationGuard = evaluationGuard;
        this.jdiConnectionService = jdiConnectionService;
    }

    /**
     * Applies chain-of-breakpoints side effects after the BP identified by {@code firingBpId} hits.
     * Two things happen here:
     * <ol>
     *   <li>If the firing BP is itself a one-shot dependent, disarm it again so the next trigger
     *       fire re-arms it (IntelliJ-style behaviour, opt-in via {@code oneShot=true}).</li>
     *   <li>For every dependent waiting on this firing BP as their trigger, enable their JDI
     *       request and emit a {@code CHAIN_ARMED} event so the user sees the activation in
     *       {@code jdwp_get_events}.</li>
     * </ol>
     * Best-effort: any {@code setEnabled} failure (e.g., dependent already removed mid-event)
     * is swallowed and recorded as a debug log entry rather than escalated — the chain handler
     * is in the JDI listener hot path and must never throw, otherwise it breaks unrelated events.
     */
    private void applyChainEffectsAfterHit(int firingBpId, @Nullable EventRequest firingRequest) {
        // Remember that this trigger has fired at least once — even if no dependents are currently
        // waiting, a chained dependent that comes up later (via pending → active promotion) needs
        // to see the historical fire and come up armed instead of being penalised for arriving late.
        breakpointTracker.markTriggerFired(firingBpId);

        // Re-disarm only in one-shot mode; sticky dependents intentionally stay armed so subsequent
        // hits go through without waiting on the trigger again. A synthesised event without a request
        // (firingRequest == null) skips self-disarm but still arms dependents below. For a BOTH-mode
        // field BP the disarm must apply to BOTH underlying requests so the next event of EITHER
        // kind requires re-arm; setBreakpointEnabledById iterates both halves.
        final BreakpointTracker.TriggerLink selfLink = breakpointTracker.getDependencyOfDependent(firingBpId);
        if (selfLink != null && selfLink.oneShot() && firingRequest != null) {
            try {
                breakpointTracker.setBreakpointEnabledById(firingBpId, false);
                eventHistory.record(new EventHistory.DebugEvent("CHAIN_DISARMED",
                    String.format("BP #%d disarmed (one-shot); waiting on trigger BP #%d",
                        firingBpId, selfLink.triggerId()),
                    Map.of("breakpointId", String.valueOf(firingBpId),
                        "triggerId", String.valueOf(selfLink.triggerId()))));
            } catch (Exception e) {
                log.debug("[JDI] Failed to disarm one-shot BP #{}: {}", firingBpId, e.getMessage());
            }
        }

        // 2. Arm dependents waiting on this BP. For BOTH-mode field BPs "is already armed?" is
        // ambiguous when only one of two underlying requests is enabled, so the isEnabled() short-
        // circuit is dropped — always call setBreakpointEnabledById, which is idempotent at the
        // JDI level and tolerates the redundant re-enable. The CHAIN_ARMED event is recorded
        // unconditionally; downstream consumers must already de-duplicate by (depId, triggerId).
        for (Integer depId : breakpointTracker.getDependentsOfTrigger(firingBpId)) {
            if (!breakpointTracker.isKnownBreakpointId(depId)) {
                continue;
            }
            try {
                breakpointTracker.setBreakpointEnabledById(depId, true);
                eventHistory.record(new EventHistory.DebugEvent("CHAIN_ARMED",
                    String.format("BP #%d armed by trigger BP #%d", depId, firingBpId),
                    Map.of("breakpointId", String.valueOf(depId),
                        "triggerId", String.valueOf(firingBpId))));
            } catch (Exception e) {
                log.debug("[JDI] Failed to arm dependent BP #{} on trigger #{}: {}",
                    depId, firingBpId, e.getMessage());
            }
        }
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
     * Registers (or clears, when {@code hook} is null) the callback invoked when the listener
     * observes the target VM dying or disconnecting. The hook runs on the JDI listener thread,
     * so it must not block indefinitely on any lock held by an MCP tool call. Clearing the hook
     * after {@link #start} is best-effort — a concurrent death observation may still invoke the
     * previously-set value.
     */
    public void setVmDeathHook(@Nullable Runnable hook) {
        this.vmDeathHook = hook;
    }

    /**
     * Spawns a fresh daemon listener thread for `vm`. Calls {@link #stop()} first so there is at
     * most one listener active at any time. The thread is a daemon — it does not block JVM shutdown.
     */
    public void start(VirtualMachine vm) {
        stop();

        // Re-arm the death-hook gate so a reconnect after a clean disconnect still fires the hook
        // on the second disconnect.
        vmDeathHookInvoked.set(false);
        running = true;
        final Thread thread = new Thread(() -> listen(vm), "jdi-event-listener");
        thread.setDaemon(true);
        listenerThread = thread;
        thread.start();
        log.info("[JDI] Event listener started");
    }

    /**
     * Runs the registered {@link #vmDeathHook} at most once per session under a swallow-on-failure
     * guard. Never throws — a hook failure must not mask the death itself.
     */
    private void invokeVmDeathHook() {
        if (!vmDeathHookInvoked.compareAndSet(false, true)) {
            return;
        }
        final Runnable hook = vmDeathHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (Exception e) {
            log.warn("[JDI] VM-death hook threw: {}", e.getMessage());
        }
    }

    /**
     * Best-effort interrupt of the listener thread. The {@link #listen} loop exits on
     * {@code VMDisconnectedException}, {@code InterruptedException}, or {@link #running} becoming
     * false.
     * <p>
     * Treats user-initiated stop as a VM-death observation: wakes any {@code resume_until_event}
     * waiter and invokes {@link #vmDeathHook} so post-mortem cleanup happens promptly instead of
     * being deferred until the next MCP tool call. Joins the listener thread for a short bounded
     * interval so a subsequent {@link #start} cannot race the previous listener's post-loop cleanup.
     */
    public void stop() {
        running = false;
        final Thread thread = listenerThread;
        listenerThread = null;
        if (thread == null) {
            // No listener was ever started, or a previous stop() already cleaned up. Skip the
            // death-observation side effects so the start() → stop() prelude stays no-op on first start.
            return;
        }
        thread.interrupt();
        try {
            thread.join(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Safety net for the rare case where the listener was stuck and never ran its own death
        // path; the CAS gate inside invokeVmDeathHook() collapses the race with the listener's own
        // call so the hook still fires at most once.
        breakpointTracker.fireNextEvent();
        invokeVmDeathHook();
    }

    /**
     * Main event loop. Events that require user inspection (breakpoints, steps, exceptions) cause
     * the entire `EventSet` to stay suspended; logpoints and false conditional breakpoints reach
     * the bottom of the loop with `shouldSuspend == false` and the set is `eventSet.resume()`d.
     * Note that `resume()` is only called when NO event in the set demanded suspension — even one
     * suspending event keeps every thread in the set parked for user inspection.
     * <p>
     * On either death path (in-loop {@code VMDeath}/{@code VMDisconnect} event, or
     * {@code VMDisconnectedException}/{@code IllegalStateException} from the JDI queue, or
     * {@code InterruptedException} from an external {@link #stop}), the loop records {@code VM_DEATH},
     * clears {@link #running}, fires the resume-until-event latch, and invokes the registered
     * {@link #vmDeathHook} before exiting.
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
                    } else if (event instanceof WatchpointEvent wpEvent) {
                        if (handleWatchpointEvent(wpEvent)) {
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
                        // Clear running BEFORE waking the latch, otherwise a waiter that wakes can
                        // observe running=true and re-park before the loop has actually exited.
                        running = false;
                        breakpointTracker.fireNextEvent();
                        invokeVmDeathHook();
                        return;
                    }
                }

                if (!shouldSuspend) {
                    eventSet.resume();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // User-initiated stop arrives via Thread.interrupt(). Treat it symmetrically to the
                // other exit paths so a waiter blocked on jdwp_resume_until_event wakes and post-mortem
                // cleanup runs; the CAS gate inside invokeVmDeathHook() handles the race with stop().
                running = false;
                breakpointTracker.fireNextEvent();
                invokeVmDeathHook();
                break;
            } catch (VMDisconnectedException | IllegalStateException e) {
                // VMDisconnectedException: queue raised before any in-loop death event came through
                // (typical for abrupt target exit). IllegalStateException: the VM was disposed
                // underneath us and a subsequent JDI call now rejects requests. Both are terminal.
                log.info("[JDI] VM disconnected, stopping event listener: {}", e.getClass().getSimpleName());
                eventHistory.record(new EventHistory.DebugEvent("VM_DEATH", "VM disconnected"));
                running = false;
                breakpointTracker.fireNextEvent();
                invokeVmDeathHook();
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
     * <p>
     * Chain effects (arm dependents, re-disarm one-shot self) are applied on every real BP hit and
     * on logpoint hits, but suppressed when a condition evaluates to false — a skipped condition is
     * not a meaningful trigger.
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

            // event.request() can be null on synthesised or stale events; fall through to the
            // untracked-BP branch so the thread still suspends without any tracker mutation.
            final EventRequest rawRequest = event.request();
            final BreakpointRequest request = rawRequest instanceof BreakpointRequest br ? br : null;
            final Integer bpId = request != null ? breakpointTracker.findIdByRequest(request) : null;

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

            if (logpointExpr != null) {
                if (condition != null && !evaluateCondition(event, condition)) {
                    log.debug("[JDI] Conditional logpoint {} at {}:{} — condition false, skipping",
                        bpId, className, lineNumber);
                    // Condition-false is not a meaningful hit, so the chain trigger does not propagate.
                    return false;
                }
                evaluateLogpoint(event, bpId, logpointExpr, className, lineNumber, threadName);
                applyChainEffectsAfterHit(bpId, request);
                return false;
            }

            if (condition != null) {
                final boolean conditionResult = evaluateCondition(event, condition);
                if (!conditionResult) {
                    log.debug("[JDI] Conditional breakpoint {} at {}:{} — condition false, resuming",
                        bpId, className, lineNumber);
                    // Same rationale as the logpoint branch — condition-false is not a chain trigger.
                    return false;
                }
            }

            eventHistory.record(new EventHistory.DebugEvent("BREAKPOINT",
                String.format("Breakpoint %d hit at %s:%d on thread %s", bpId, className, lineNumber, threadName),
                Map.of("breakpointId", String.valueOf(bpId), "class", className,
                    "line", String.valueOf(lineNumber), "thread", threadName)));

            log.info("[JDI] Breakpoint {} hit on thread {} at {}:{}", bpId, threadName, className, lineNumber);
            breakpointTracker.fireNextEvent();
            applyChainEffectsAfterHit(bpId, request);
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
     * <p>
     * Both the log-only and the suspending paths additionally propagate chain effects for the
     * firing exception BP via {@link #applyChainEffectsAfterHit} when its synthetic ID is
     * recoverable from {@code event.request()}.
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
            final ExceptionRequest firingRequest = event.request() instanceof ExceptionRequest exReq ? exReq : null;
            final BreakpointTracker.ExceptionBreakpointInfo info = firingRequest != null
                ? breakpointTracker.findExceptionInfoByRequest(firingRequest) : null;
            final Integer firingId = firingRequest != null
                ? breakpointTracker.findExceptionIdByRequest(firingRequest) : null;

            if (info != null && info.isLogOnly()) {
                final String condition = firingId != null
                    ? breakpointTracker.getCondition(firingId) : null;
                if (condition != null && !evaluateConditionWithBindings(
                        event.thread(), condition, Map.of("$exception", exception))) {
                    // Condition false — skip the log AND chain effects. Same rationale as the
                    // line-BP path: a skipped condition is not a meaningful trigger.
                    log.debug("[JDI] Exception logpoint {} condition false, skipping", firingId);
                    return false;
                }
                evaluateExceptionLogpoint(event, info, exception, exceptionType,
                    throwInfo, catchInfo, threadName);
                if (firingId != null) {
                    applyChainEffectsAfterHit(firingId, firingRequest);
                }
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

            if (firingId != null) {
                applyChainEffectsAfterHit(firingId, firingRequest);
            }
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
     * Dispatches a field watchpoint hit and returns whether the firing thread should stay
     * suspended. Mirrors {@link #handleBreakpointEvent} for the field-access / field-modification
     * paths, with five synthetic bindings exposed to conditions and logpoint expressions:
     * <ul>
     *   <li>{@code $oldValue} — value before the event. For a modification event this is the value
     *       about to be overwritten; for an access event it is the value being read.</li>
     *   <li>{@code $newValue} — value about to be written. ModificationWatchpointEvent only;
     *       referencing it in an access-only expression yields a compile error from the evaluator.</li>
     *   <li>{@code $object} — the instance the field belongs to; {@code null} for static fields.</li>
     *   <li>{@code $fieldName} — String mirror of the field name.</li>
     *   <li>{@code $mode} — String mirror "access" or "modification" indicating which kind of event
     *       fired (distinct from the BP's configured mode in {@code BOTH}).</li>
     * </ul>
     * Auto-resume cases (returns {@code false}): reentrancy guard active, condition evaluates to
     * false, or the BP is configured as a logpoint. Suspending cases (returns {@code true}):
     * untracked watchpoints (defensive — should not happen), plain field BPs, BPs whose condition
     * is true (or whose evaluator throws — fail-safe), or any internal error during binding setup.
     * <p>
     * Chain effects propagate on every real hit and on logpoint hits, but are suppressed when the
     * condition evaluates to false — the symmetry mirrors {@link #handleBreakpointEvent}.
     */
    private boolean handleWatchpointEvent(WatchpointEvent event) {
        // Reentrancy guard — see handleBreakpointEvent for the rationale.
        if (evaluationGuard.isEvaluating(event.thread())) {
            try {
                final String className = event.field().declaringType().name();
                final String fieldName = event.field().name();
                final String threadName = event.thread().name();
                eventHistory.record(new EventHistory.DebugEvent("FIELD_BREAKPOINT_SUPPRESSED",
                    String.format("Recursive field event on %s.%s suppressed (thread '%s' inside MCP evaluation)",
                        className, fieldName, threadName),
                    Map.of("class", className, "field", fieldName, "thread", threadName)));
                log.info("[JDI] Suppressing recursive field watchpoint on {}.{} (thread {})",
                    className, fieldName, threadName);
            } catch (Exception e) {
                log.debug("[JDI] Error recording suppressed field event: {}", e.getMessage());
            }
            return false;
        }

        try {
            final WatchpointRequest request = event.request() instanceof WatchpointRequest wr ? wr : null;
            final Integer bpId = request != null ? breakpointTracker.findFieldIdByRequest(request) : null;
            final BreakpointTracker.FieldBreakpointInfo info = request != null
                ? breakpointTracker.findFieldInfoByRequest(request) : null;

            if (bpId == null || info == null) {
                log.warn("[JDI] Untracked field watchpoint hit on {}.{}",
                    event.field().declaringType().name(), event.field().name());
                return true;
            }

            final boolean isModification = event instanceof ModificationWatchpointEvent;
            final String modeStr = isModification ? "modification" : "access";
            final String className = event.field().declaringType().name();
            final String fieldName = event.field().name();
            final String threadName = event.thread().name();

            breakpointTracker.setLastBreakpointThread(event.thread(), bpId);

            final Map<String, Value> bindings;
            try {
                bindings = buildFieldEventBindings(event, isModification, fieldName, modeStr);
            } catch (Exception bindEx) {
                log.warn("[JDI] Could not build bindings for field event on {}.{}: {}",
                    className, fieldName, bindEx.getMessage());
                return true;
            }

            final String condition = breakpointTracker.getCondition(bpId);
            if (condition != null && !evaluateConditionWithBindings(event.thread(), condition, bindings)) {
                log.debug("[JDI] Field BP {} condition false, skipping", bpId);
                // Condition-false is not a meaningful hit — chain trigger does not propagate.
                return false;
            }

            final BreakpointTracker.FieldBreakpointSpec spec = info.getSpec();
            if (spec.logOnly()) {
                evaluateFieldLogpoint(event, bpId, spec, modeStr, className, fieldName, threadName, bindings);
                applyChainEffectsAfterHit(bpId, request);
                return false;
            }

            eventHistory.record(new EventHistory.DebugEvent(
                isModification ? "FIELD_MODIFICATION" : "FIELD_ACCESS",
                String.format("Field %s.%s %s on thread %s", className, fieldName, modeStr, threadName),
                Map.of("breakpointId", String.valueOf(bpId),
                    "class", className, "field", fieldName,
                    "mode", modeStr, "thread", threadName,
                    "oldValue", String.valueOf(event.valueCurrent()))));

            log.info("[JDI] Field {} {} on {} (BP #{})", fieldName, modeStr, threadName, bpId);
            breakpointTracker.fireNextEvent();
            applyChainEffectsAfterHit(bpId, request);
            return true;
        } catch (Exception e) {
            log.warn("[JDI] Error handling watchpoint event: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Builds the synthetic-binding map exposed to conditions and logpoint expressions on a field
     * watchpoint hit. {@code $oldValue} / {@code $object} are conditionally present (the JDI value
     * may itself be a primitive null sentinel, in which case the binding is omitted so the user
     * gets a compile error rather than a misleading NullPointerException at evaluation time).
     * {@code $newValue} appears only for {@link ModificationWatchpointEvent}.
     */
    private static Map<String, Value> buildFieldEventBindings(WatchpointEvent event,
                                                              boolean isModification,
                                                              String fieldName, String modeStr) {
        final VirtualMachine vm = event.virtualMachine();
        final Map<String, Value> bindings = new HashMap<>();
        if (event.valueCurrent() != null) {
            bindings.put("$oldValue", event.valueCurrent());
        }
        if (isModification) {
            final Value newValue = ((ModificationWatchpointEvent) event).valueToBe();
            if (newValue != null) {
                bindings.put("$newValue", newValue);
            }
        }
        if (event.object() != null) {
            bindings.put("$object", event.object());
        }
        bindings.put("$fieldName", vm.mirrorOf(fieldName));
        bindings.put("$mode", vm.mirrorOf(modeStr));
        // Map is method-scoped and the downstream consumers (condition + logpoint evaluators) read
        // it without mutation — skipping Map.copyOf saves an allocation per field hit on a hot path.
        return bindings;
    }

    /**
     * Records a {@code FIELD_LOGPOINT} entry for a log-only field watchpoint and, if the spec
     * carries an expression, evaluates it against the firing frame with the field-event synthetic
     * bindings injected. Evaluation failures are captured as a separate {@code FIELD_LOGPOINT_ERROR}
     * entry so the listener never throws.
     */
    private void evaluateFieldLogpoint(WatchpointEvent event, int bpId,
                                       BreakpointTracker.FieldBreakpointSpec spec,
                                       String modeStr, String className, String fieldName,
                                       String threadName, Map<String, Value> bindings) {
        final String expression = spec.expression();
        if (expression == null) {
            eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT",
                String.format("Field %s.%s %s on thread %s [log-only]",
                    className, fieldName, modeStr, threadName),
                Map.of("breakpointId", String.valueOf(bpId),
                    "class", className, "field", fieldName,
                    "mode", modeStr, "thread", threadName)));
            log.info("[JDI] Field {} {} logged on {} (BP #{}, log-only)",
                fieldName, modeStr, threadName, bpId);
            return;
        }
        try {
            final String resultStr = evaluateAndFormat(event.thread(), expression, bindings);
            eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT",
                String.format("Field %s.%s %s on thread %s [%s = %s]",
                    className, fieldName, modeStr, threadName, expression, resultStr),
                Map.of("breakpointId", String.valueOf(bpId),
                    "class", className, "field", fieldName,
                    "mode", modeStr, "thread", threadName,
                    "expression", expression, "result", resultStr)));
            log.info("[JDI] Field {} {} logged on {} with {} = {} (BP #{})",
                fieldName, modeStr, threadName, expression, resultStr, bpId);
        } catch (Exception e) {
            eventHistory.record(new EventHistory.DebugEvent("FIELD_LOGPOINT_ERROR",
                String.format("Field %s.%s %s on thread %s — error evaluating '%s': %s",
                    className, fieldName, modeStr, threadName, expression, e.getMessage()),
                Map.of("breakpointId", String.valueOf(bpId),
                    "class", className, "field", fieldName,
                    "expression", expression,
                    "error", String.valueOf(e.getMessage()))));
            log.warn("[JDI] Field logpoint evaluation failed for {}.{}: {}",
                className, fieldName, e.getMessage());
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
     * Evaluates a conditional breakpoint expression at frame 0 with no extra synthetic bindings.
     * Delegates to {@link #evaluateConditionWithBindings} — kept as a narrow entry point for the
     * line-BP path where {@code $exception} / field-event bindings are not in scope.
     */
    private boolean evaluateCondition(BreakpointEvent event, String condition) {
        return evaluateConditionWithBindings(event.thread(), condition, Map.of());
    }

    /**
     * Evaluates a condition expression at frame 0 of {@code thread} with additional synthetic
     * bindings (e.g. {@code $exception} for exception logpoints, {@code $oldValue}/{@code $newValue}
     * for field watchpoints). Fail-safe policy: any error (compilation failure, non-boolean result,
     * exception during execution) returns {@code true} so the user sees the breakpoint fire and can
     * investigate the problem rather than silently skipping it. Recognises both primitive
     * {@link BooleanValue} and the boxed {@code java.lang.Boolean} returned via the wrapper class's
     * {@code (Object)(...)} autoboxing cast.
     */
    private boolean evaluateConditionWithBindings(ThreadReference thread, String condition,
                                                  Map<String, Value> extraBindings) {
        try {
            expressionEvaluator.configureCompilerClasspath(thread);
            final StackFrame frame = thread.frame(0);
            final Value result = expressionEvaluator.evaluate(frame, condition, extraBindings);

            if (result instanceof BooleanValue boolVal) {
                return boolVal.value();
            }

            // Boxed java.lang.Boolean — wrapper autoboxes primitive results before returning.
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
     * <p>
     * Chained pending BPs are promoted disarmed via {@link BreakpointTracker#disarmIfChained} so
     * the very first event after class load cannot fire before the trigger has.
     */
    private void handleClassPrepareEvent(ClassPrepareEvent event) {
        try {
            final ReferenceType refType = event.referenceType();
            final String className = refType.name();

            final List<Map.Entry<Integer, BreakpointTracker.PendingBreakpoint>> pendingList =
                breakpointTracker.getPendingBreakpointsForClass(className);
            final List<Map.Entry<Integer, BreakpointTracker.PendingExceptionBreakpoint>> pendingExList =
                breakpointTracker.getPendingExceptionBreakpointsForClass(className);
            final List<Map.Entry<Integer, BreakpointTracker.PendingFieldBreakpoint>> pendingFieldList =
                breakpointTracker.getPendingFieldBreakpointsForClass(className);

            if (pendingList.isEmpty() && pendingExList.isEmpty() && pendingFieldList.isEmpty()) {
                return;
            }

            log.info("[JDI] ClassPrepareEvent for '{}', activating {} deferred breakpoint(s), {} deferred exception breakpoint(s), {} deferred field BP(s)",
                className, pendingList.size(), pendingExList.size(), pendingFieldList.size());

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
                    // If this BP was chained while still pending, it must come up disarmed —
                    // otherwise the very first event could fire before the trigger ever has.
                    breakpointTracker.disarmIfChained(id, bpRequest);

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
                    breakpointTracker.disarmIfChained(id, exReq);
                    breakpointTracker.promotePendingExceptionToActive(id, exReq);
                    log.info("[JDI] Deferred exception breakpoint {} activated for {}", id, className);
                } catch (Exception e) {
                    breakpointTracker.markPendingExceptionFailed(id, e.getMessage());
                    log.warn("[JDI] Deferred exception breakpoint {} failed: {}", id, e.getMessage());
                }
            }

            // Promote pending FIELD watchpoints synchronously here — the loading thread auto-resumes
            // right after this handler returns, runs <clinit>, and any static-field writes inside
            // <clinit> would be lost if the field BP weren't already armed. jdiConnectionService is
            // null in unit tests that build the listener directly without the Spring context — those
            // tests don't exercise field-BP promotion, so the short-circuit is harmless.
            if (jdiConnectionService != null) {
                breakpointTracker.promotePendingFieldsForClass(refType, vm, erm, jdiConnectionService);
            }

            if (breakpointTracker.getPendingBreakpointsForClass(className).isEmpty()
                && breakpointTracker.getPendingExceptionBreakpointsForClass(className).isEmpty()
                && breakpointTracker.getPendingFieldBreakpointsForClass(className).isEmpty()) {
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
