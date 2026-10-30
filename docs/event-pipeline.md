# Event pipeline

JDI delivers every interesting target-VM event — breakpoint hits, steps, exceptions, field accesses, class loads, VM start, VM death — to a single queue inside the server JVM. The `JdiEventListener` is the daemon thread that drains it, a second thread processes what it drains, and the `SuspensionSession` owns every suspension the resulting event sets hold. This chapter documents the contract the other components depend on: how sets are routed and processed, when a stop is published, and who may release it (§ 14).

## 1. The listener thread

> **Background — why a JDI debugger must drain its event queue**
>
> JDI events ride the same JDWP socket as command replies. The target VM's JDWP agent serialises events into composite event packets and writes them to the socket; the debugger reads them out via `VirtualMachine.eventQueue().remove()`. The socket has a finite OS-level send buffer. If the debugger does not drain the queue fast enough, the agent's writes start to block on socket `send()`.
>
> The agent emits events from JVMTI callbacks, which fire on the threads that triggered the event — application threads running breakpointed code, the loading thread for a `ClassPrepareEvent`, and so on. A blocked write inside one of those callbacks blocks that thread, and through it any other thread waiting on the same JVMTI lock. In the worst case, the entire target VM stalls because the debugger forgot to read.
>
> This is why the listener is a dedicated daemon whose only job is to drain the queue. It must not block on anything that could in turn require the queue to advance — otherwise the deadlock above is the result. Every blocking operation the listener can hit is bounded: the session monitor that routing takes is held only across short JDWP commands (suspend, resume), never across an invocation or a wait; the death path waits for the dispatcher at most 2 s; the rest is non-blocking (`ConcurrentHashMap`, `ConcurrentLinkedDeque`).

One thread, named `jdi-event-listener`, daemon, spawned by `JdiEventListener.start(vm)`. Exactly one runs at a time — `start()` calls `stop()` first so reconnects never overlap two listeners. `start()` also opens the connection's `SuspensionSession` (`BreakpointTracker.openSuspensionSession`, which closes the previous one), binds it to the `EvaluationGuard`, and starts its `SuspensionWatchdog` (§ 14).

The listener only drains and routes. Processing an event set can call into the target — logpoint expressions, breakpoint conditions, fast-logpoint arming, the classpath pre-warm all use `invokeMethod` — and a method invoked that way can hit a breakpoint of its own. The invocation cannot return until that second event is drained and its thread resumed. So the sets are processed on a second thread, `jdi-event-dispatcher` (a single-thread executor per connection, so they stay in arrival order and their evaluations stay serialized), while the listener keeps draining:

- a `VMDeath` / `VMDisconnect` set is processed on the listener thread after the dispatcher has finished the sets before it (bounded at 2 s). The session is closed first, and the set gets no context. Sets still queued after that are dropped unprocessed, so `VM_DEATH` stays the last entry; a set already being processed — a long compile, say — finishes on the closed session, which drops its buffered history and publishes nothing (§ 3);
- a set whose thread is inside an MCP-driven evaluation (it holds a lease, § 14) is **suppressed** on the listener thread at once (`suppress`): it is routed as a context and finished immediately. In between, each event is recorded as `*_SUPPRESSED` (a step's request is deleted too); a fast-logpoint arming hit is re-armed and a fast breakpoint's stop skipped instead; a class prepare still promotes its deferred breakpoints. With no stop candidate, `finish` releases the set, which lets the waiting invocation finish. This path never evaluates, arms or pre-warms — not even if the evaluation has ended by the time the set is handled — so the listener thread never calls into the target;
- every other set is routed as a QUEUED context (`SuspensionSession.route`) and handed to the dispatcher.

Only a set's own context releases its suspension. The dispatcher's `finish` ends each set's processing: it publishes the set's first stop candidate as a **held stop**, or it releases the set. A held stop belongs to the user: a tool releases it, and only after a presenting tool has shown it. A set still queued or being processed is out of every tool's reach. So no tool resume can spend a waiting set's suspension, and a set's own release can never free a newer stop of the same thread. § 14 has the full model.

Because a set can wait on the dispatcher, the handlers re-apply the rule JDI applies when a set leaves its queue to one case: an event whose request was **deleted** meanwhile (a cleared logpoint, a class redefined by a fast logpoint) is dropped and does not keep its set suspended — otherwise the stale hit would be untracked and park its thread for good. An event whose tracked request was merely **disabled** meanwhile still counts as a hit — it was one when it happened.

Lifetime:

- Started by `JDIConnectionService.connect(...)` after the JDI attach succeeds.
- Stopped by `cleanupSessionState` on `disconnect`, on reconnect-to-different-target, and on `notifyVmDied`.
- Self-terminates when the loop catches `VMDisconnectedException` / `IllegalStateException` from `vm.eventQueue().remove()`: it waits for the dispatcher (bounded), closes the session and records `VM_DEATH`.

`stop()` closes the session — which wakes any `jdwp_resume_until_event` waiter with `CLOSED` (§ 9) — stops the watchdog and shuts the dispatcher down (an `invokeMethod` in flight is not interruptible; it fails once the VM goes). It then interrupts the listener and joins it for up to 500 ms, and runs the VM-death hook. It still calls `breakpointTracker.fireNextEvent()`, but no production tool waits on the tracker's latch any more.

## 2. The loop

`listen(...)` drains and routes (see § 1); `dispatch(...)` handles one set on the dispatcher, and `process(...)` runs its handlers. Skeleton:

```java
while (running) {
    EventSet eventSet = vm.eventQueue().remove();          // blocks
    if (isDeath(eventSet)) {
        awaitDispatched(dispatcher); session.close(); process(eventSet, null, null);
    } else if (fromEvaluatingThread(eventSet)) {
        suppress(eventSet, session);                        // route + finish at once
    } else {
        Context context = session.route(eventSet, threadOf(eventSet), isVmStart(eventSet));
        dispatcher.execute(() -> dispatch(eventSet, session, context));
    }
}

void dispatch(EventSet eventSet, SuspensionSession session, Context context) {
    if (!session.begin(context)) {                          // the watchdog cancelled it (§ 14)
        settleCancelled(eventSet);
        session.finish(context);
        return;
    }
    session.beginProcessing(context);                       // snapshots → candidates, history → buffer
    boolean keep;
    try {
        keep = !process(eventSet, session, context);
    } catch (Exception e) {
        keep = true;                                        // fail-safe
    } finally {
        SuspensionSession.endProcessing();
    }
    if (!keep) {
        session.dropCandidatesAfter(context, 0);
    } else if (session.candidateCount(context) == 0 && context.thread() != null) {
        session.candidate(context, new LastBreakpoint(context.thread(), null));   // untracked hit
    }
    session.finish(context);                                // publish the first candidate, or release
}

boolean process(EventSet eventSet, …) {                    // returns "release the set"
    boolean shouldSuspend = false;
    for (Event e : eventSet) {
        int mark = candidateCount(context);
        boolean stop = dispatchByType(e);
        shouldSuspend |= stop;
        if (!stop) dropCandidatesAfter(context, mark);      // the handler's candidates are withdrawn
    }
    // fast-logpoint arming hits run last; then, if shouldSuspend, the classpath pre-warm
    return !shouldSuspend;
}
```

Two details matter:

1. **`shouldSuspend` is OR-accumulated across the entire `EventSet`.** JDWP can deliver multiple events together — for example, a `BreakpointEvent` and a `StepEvent` on the same thread at the same location. If **any** handler wants a stop, the set is published and the entire set stays parked. A handler that does not stop withdraws the candidates it added (a breakpoint handler records its snapshot before it evaluates the condition); of the candidates kept, the first is the stop's report. The JDI suspension policy on each request decides which threads are parked; the dispatcher's decision is purely "publish a stop, or release the set".
2. **An exception thrown from a handler defaults to suspend.** Several handlers catch, record the error and return `true`; an exception escaping `process` sets `keep` in `dispatch`. A set kept without a candidate (an untracked breakpoint) is published as a plain breakpoint stop of its thread. The principle is fail-safe — if something is broken, the agent gets a chance to inspect; we never silently resume on an unexpected error.

The classpath pre-warm runs inside the set's processing, before its stop is published, so no tool can touch the thread meanwhile. It runs only when the set stops and its policy is not `none`, and only on the first stop per connection.

### Dispatch by type

`process` routes each event by `instanceof`:

| Event type | Handler |
|---|---|
| `BreakpointEvent` of a fast logpoint's arming request | collected; `FastLogpointService.handleArmingHit` after every other event of the set — arming redefines the class, which replaces the requests behind the set's other events in it |
| `BreakpointEvent` of a fast breakpoint's stop | `FastLogpointService.handleStopHit` |
| `BreakpointEvent` | `handleBreakpointEvent` |
| `StepEvent` | `handleStepEvent` |
| `ExceptionEvent` | `handleExceptionEvent` |
| `WatchpointEvent` (access + modification) | `handleWatchpointEvent` |
| `ClassPrepareEvent` | `handleClassPrepareEvent` — never stops |
| `VMStartEvent` | inline — records `VM_START` and adds a `VM_START` candidate: the VM stays suspended |
| `VMDeathEvent` / `VMDisconnectEvent` | inline — only in a death set, on the listener thread; terminates the loop |

## 3. The suspending-event contract

Every handler that decides to stop still makes the same three calls, in the same order:

1. **Snapshot** — `BreakpointTracker.setLastBreakpointThread(thread, id)` or `setLastSuspendingEvent(thread, kind)`.
2. **History** — `EventHistory.record(...)` of the hit (`BREAKPOINT`, `STEP`, …).
3. **Latch** — `BreakpointTracker.fireNextEvent()`.

On the dispatcher none of them takes effect at once. While a set is processed (`SuspensionSession.beginProcessing`, a thread-local), the snapshot becomes a **stop candidate** of the set's context, the history entry is **buffered** in the context, and `fireNextEvent()` returns without doing anything. The fast-breakpoint service's `publishStop` makes the same three calls and is treated the same way.

`finish(context)` then settles the set in one step, under the session monitor:

- **Session closed** → nothing: the buffered history is dropped and nothing is published (`vm.dispose()` releases everything in the target).
- **History** — the buffered entries are appended to `EventHistory`, unless the set belongs to an earlier generation (`jdwp_reset` ran since it was routed, or one of its requests was stamped by an earlier generation); then they are dropped.
- **Publish** — when the set is current, has a candidate, suspends something (policy not `none`) and was not cancelled by the watchdog: the context becomes HELD with the first candidate as its report; `BreakpointTracker.published(report)` stores the report as the last snapshot; a pending step of the same thread is cleared — unless the stop is that step's landing, its request is deleted and `STEP_INTERRUPTED` recorded; the session's waiters are notified.
- **Otherwise release** the set (§ 14).

The order of the three calls inside a handler is therefore no longer what readers rely on. Everything a waiter can see is written inside one monitor section, and presenting tools select under the same monitor (§ 9, § 14), so a presented stop's history entries are already in `EventHistory`. A set is published at most once.

Records made outside set processing — a suppressed set on the listener, the watchdog, tool methods, invoker threads — go straight to `EventHistory`.

## 4. The reentrancy check — first thing every handler does

A set whose thread holds an invocation lease never reaches a handler: the listener suppresses it at routing (§ 1). Every suspending handler still starts with the same guard check:

```java
if (evaluationGuard.isEvaluating(event.thread())) {
    recordSuppressedBreakpoint(event);   // BREAKPOINT_SUPPRESSED
    return false;                        // no stop
}
```

It covers a set routed before an evaluation on its thread began — a set with suspend policy `none`, for example, holds nothing on its thread, so it does not keep an evaluation from being admitted. The handler records the suppression and adds no candidate; unless another event of the set stops, `finish` releases the set. `isEvaluating` reads the guard's leases — see [threading-and-safety.md](threading-and-safety.md) § 5.

This means a recursive hit during expression evaluation produces only a history entry — no stop, and no change to the current stop.

## 5. The suspension-decision matrix

> **Background — JDI's three suspend policies, and what an `EventSet` is**
>
> Each JDI `EventRequest` (a `BreakpointRequest`, `ExceptionRequest`, watchpoint, etc.) carries a `SuspendPolicy` chosen at registration time. The three constants — all defined on `EventRequest` — are:
>
> - **`SUSPEND_NONE`** — the firing event does not suspend any thread. The target keeps running; the event is delivered to the debugger purely for observation. Used by tracing tools that want a log without ever stopping execution.
> - **`SUSPEND_EVENT_THREAD`** — only the thread that triggered the event is suspended. Other threads keep running. This is the IntelliJ "Suspend: Thread" mode and the default for most kinds of breakpoint in this plugin.
> - **`SUSPEND_ALL`** — every thread in the target VM is suspended. The whole world stops. Costs more in target-thread context switches but gives a consistent global snapshot.
>
> When multiple requests fire at the same location at the same time, the JVM groups their events into a single **`EventSet`**. The set's effective suspend policy is the strongest of its members: any `SUSPEND_ALL` upgrades the whole set, otherwise any `SUSPEND_EVENT_THREAD` keeps the firing thread parked, otherwise the set is `SUSPEND_NONE`. `EventSet.resume()` undoes the set's own suspension by its policy: `vm.resume()` for a stop-all set, a resume of the event thread for a thread set, nothing for `none`. `SuspensionSession` releases a context with exactly this call, after re-suspending the threads the release must not touch (§ 14).

For each event type, the handler returns `true` (it wants a stop: its candidate stays) or `false` (its candidates are withdrawn). The set is published when any of its events stopped and its policy is not `SUSPEND_NONE`; otherwise `finish` releases it. A `none` set is history only, whatever its handlers return. This is the full matrix.

| Event | Branch | Handler returns | Notes |
|---|---|---|---|
| `BreakpointEvent` | Reentrancy-guarded | false | `BREAKPOINT_SUPPRESSED`, no candidate |
| | Request deleted while the set waited | false | Dropped (§ 1) |
| | Untracked (no matching BP in registry) | **true** | Defensive — log warning; `dispatch` adds a plain breakpoint candidate |
| | Logpoint + condition false | false | No history, no chain effect |
| | Logpoint + condition true (or no condition) | false | Evaluate, log, chain effect |
| | Plain BP, condition false | false | No chain effect |
| | Plain BP, condition true (or no condition) | **true** | Candidate, history, chain effect |
| | Handler exception | **true** | Fail-safe |
| `StepEvent` | Reentrancy-guarded | false | Delete the one-shot `StepRequest`, `STEP_SUPPRESSED` |
| | Normal step | **true** | Delete the one-shot `StepRequest`, candidate (`STEP`), history |
| `ExceptionEvent` | Reentrancy-guarded | false | `EXCEPTION_SUPPRESSED` |
| | Log-only + condition false | false | |
| | Log-only + condition true | false | Evaluate, log, chain effect |
| | Suspending | **true** | Candidate, history, chain effect when `firingId != null` |
| `WatchpointEvent` | Reentrancy-guarded | false | `FIELD_BREAKPOINT_SUPPRESSED` |
| | Untracked | **true** | Defensive |
| | Condition false | false | No chain effect |
| | Logpoint | false | Evaluate, log, chain effect |
| | Suspending | **true** | Candidate, history, chain effect |
| `ClassPrepareEvent` | always | (never stops) | Promotion side effect only; the CPR's own `SUSPEND_EVENT_THREAD` policy parks the loading thread until `finish` releases the set |
| `VMStartEvent` | always | **true** | `VM_START` candidate: the VM stays suspended so the agent can set up. Not invocable — an evaluation needs a later stop (§ 14) |
| `VMDisconnect` / `VMDeath` | always | exits loop | Death set only, processed on the listener without a context; sets `running=false`, runs the death hook |

Fast logpoints and fast breakpoints are decided by `FastLogpointService` (`handleArmingHit` → `STOPPED` / `RESUME`, `handleStopHit`) under the same candidate rules; see [breakpoints.md](breakpoints.md) § 5a and § 5c. Arming and removal never redefine a method a held thread is in (§ 5d).

A few things stand out from the matrix:

- **Untracked events suspend.** If JDWP delivers a breakpoint or watchpoint event for a request the tracker has no record of (extremely rare — the only way is concurrent registration races during cleanup), the dispatcher errs on the side of inspection. Log a warning, suspend, let the agent figure it out. The alternative (silent resume) would mask real bugs. A request deleted while its set waited is the exception — that event is dropped (§ 1).
- **Conditional-false skips chain effects.** A condition that evaluates to `false` is "not a meaningful hit", so it does not count as a trigger fire for the chain dependency graph. Documented inline in `handleBreakpointEvent`.
- **Logpoints always suspend briefly.** A logpoint expression needs to call `invokeMethod`, which requires a suspended thread at an invocation event. So the JDI request uses `SUSPEND_EVENT_THREAD`; the dispatcher evaluates, records, and the handler returns `false`, so `finish` releases the set. From the agent's perspective the thread never paused — but JDI did pause it long enough for the evaluation to be legal.

## 6. Conditional-breakpoint evaluation

`evaluateConditionWithBindings` (`JdiEventListener.java:919-949`) is the shared path for line BP, exception BP, and field BP conditions. Two properties:

- **Fail-safe.** Any compile or runtime error in the condition returns `true` (suspend). The reasoning: a broken expression silently swallowing breakpoint hits is a debugging nightmare. If the agent typed something wrong, surface it as a stop, with the error in the event details — the agent can then call `jdwp_evaluate_expression` to see what happened.
- **Accepts primitive or boxed Boolean.** Both `BooleanValue` and `java.lang.Boolean` mirrors are recognised (lines 927-941). Convenient when the agent writes `order.isValid()` against a Java API that boxes its return.

## 7. Logpoint evaluation

Logpoints piggyback on the same models as suspending breakpoints — they are just breakpoints whose metadata sets `BreakpointMetadata.logpointExpression`. Field logpoints use `FieldBreakpointSpec.logOnly = true`.

When a logpoint fires:

1. Run the same reentrancy and tracking checks as a normal BP.
2. If a condition is set, evaluate it. False → return false, no log, no chain.
3. Evaluate the log expression via `evaluateLogpoint` (`JdiEventListener.java:848-865`). The result is rendered through `evaluateAndFormat` (line 874-881).
4. Record `LOGPOINT` to event history with the formatted result.
5. Apply chain effects.
6. Return `false` → no candidate; unless another event of the set stops, `finish` releases the set.

Symmetric paths for `EXCEPTION_LOG` (`JdiEventListener.java:617-658`) and `FIELD_LOGPOINT` (`JdiEventListener.java:799-837`). All three are subject to the same fail-safe rule: if the log expression fails, record `LOGPOINT_ERROR` / `EXCEPTION_LOG_ERROR` / `FIELD_LOGPOINT_ERROR` with the failure reason and continue.

## 8. The `EventHistory` ring buffer

Internals of `EventHistory`:

- `MAX_EVENTS = 500` — hard-coded, deliberately not configurable.
- `ConcurrentLinkedDeque<DebugEvent>` — producers are the listener (suppressed sets, `VM_DEATH`), `SuspensionSession` on the dispatcher (a finished set's buffered entries) and on the watchdog, invoker threads (`EVALUATION_COMPLETED_LATE`) and tool methods; consumers are MCP tool methods via `getRecent(int)`.
- `record()` appends, then evicts oldest entries until size ≤ cap. FIFO. On a thread that processes a set it buffers the entry in the set's context instead (§ 3).
- `getRecent(int n)` returns a **detached copy** — `new ArrayList<>(events)` then sublist. The consumer can iterate freely while producers keep appending.
- `clear()` — called from `jdwp_reset`, `jdwp_clear_events`, and `cleanupSessionState`.

A `DebugEvent` is the record `(Instant timestamp, String type, String summary, Map<String, String> details, int sessionEpoch)`; `record()` stamps the attach epoch so `jdwp_get_events` can segment connections. The `type` strings are the canonical vocabulary:

```
BREAKPOINT, STEP, EXCEPTION,
EXCEPTION_LOG, EXCEPTION_LOG_ERROR,
LOGPOINT, LOGPOINT_ERROR, CONDITION_ERROR,
FIELD_ACCESS, FIELD_MODIFICATION,
FIELD_LOGPOINT, FIELD_LOGPOINT_ERROR,
BREAKPOINT_SUPPRESSED, EXCEPTION_SUPPRESSED, FIELD_BREAKPOINT_SUPPRESSED, STEP_SUPPRESSED,
CHAIN_ARMED, CHAIN_DISARMED, CHAIN_BROKEN,
CLASS_PREPARE, BP_MULTI_LOCATION, BP_PROMOTION_FAILED,
RECONNECT, VM_START, VM_DEATH,
STEP_INTERRUPTED, EVENT_CANCELLED, EVALUATION_UNBLOCKED, EVALUATION_COMPLETED_LATE,
FAST_LOGPOINT, FAST_LOGPOINT_ERROR, FAST_LOGPOINT_DROPPED, FAST_LOGPOINT_RESTORED,
FAST_BREAKPOINT, FAST_BREAKPOINT_ERROR, FAST_BREAKPOINT_STALE, FAST_BREAKPOINT_SUPPRESSED,
FAST_LOGPOINT_* and FAST_BREAKPOINT_* with ARMED, ARM_FAILED, ARMING_DEFERRED, REMOVAL_DEFERRED, DISARMED, WARNING, RECOVERED
```

The list is what production code emits (`new EventHistory.DebugEvent(` across the main sources; the last line's types are built from the fast kind's prefix). The four suspension-ownership types:

| Type | Recorded by | When |
|---|---|---|
| `STEP_INTERRUPTED` | `SuspensionSession.finish` (publication) | another stop was published on a thread with a step in flight; the step's request is deleted |
| `EVENT_CANCELLED` | the watchdog (`SuspensionSession.unblock`) | a queued set was cancelled so its event thread could be released (§ 14) |
| `EVALUATION_UNBLOCKED` | the watchdog (`SuspensionSession.unblock`) | the watchdog released the owner of the monitor a dispatcher evaluation waits for (§ 14) |
| `EVALUATION_COMPLETED_LATE` | `InvocationRunner` | a tool's invocation finished after the tool answered `[STILL_RUNNING]`; recorded only in the session it started in ([threading-and-safety.md](threading-and-safety.md) § 5) |

## 9. Waiting for the next stop — `jdwp_resume_until_event`

`jdwp_resume_until_event` is the blocking alternative to "resume + poll events + poll events". It calls `SuspensionSession.nextStop(timeoutMs)`, which runs under the session monitor and waits on the monitor's own condition (`wait` / `notifyAll`), so selecting a stop, releasing, and registering as a waiter are atomic with respect to publication. Each pass:

1. **An unseen stop exists** → present the oldest (it becomes the current stop) and return `STOPPED`. Nothing is released. This holds on a closed session too: a stop published just before the VM died explains the death.
2. **The session is closed** (disconnect, death, reconnect) → `CLOSED`.
3. **`jdwp_reset` ran since the call started** (the generation changed) → `RESET`.
4. **First pass only:** unless a step is in flight on some thread, release what the user holds — the same release as `jdwp_resume` (§ 14). While a step is pending, nothing is released; the call only waits for the landing.
5. **Deadline passed** → `TIMEOUT`. Otherwise wait for the remaining time and select again. A notification never implies that a particular stop exists.

The tool renders the outcomes as `Event fired …` (the stop's location, and a note when the watchdog made the stop partial), `[VM_DEATH] …`, `[NO_EVENT] …` (reset) and the `[TIMEOUT]` soft-wait envelope with a diagnostic report. What the call released before it waited is appended as a trailing line.

Ordering guarantee: short of `jdwp_reset` (which releases every stop) and a closed session, every stop is either presented as new or released after it was presented — never both, never neither.

## 10. `EventKind` — what kind of event the snapshot was

`BreakpointTracker` defines:

```java
public enum EventKind { BREAKPOINT, STEP, EXCEPTION, VM_START }
```

Attached to `LastBreakpoint` records — the handlers' stop candidates, and so the reports of published stops. The canonical constructor normalises `EXCEPTION` and `VM_START` records to `id=null` so a stale BP id from a previous step does not leak into "Event fired" headers. `VM_START` marks the stop a VM suspended at start publishes (§ 14).

The kind is what lets the agent's tool response distinguish "thread parked at line 42" from "thread parked because an NPE was thrown three frames up" without re-walking the registry. Useful when the same thread can land in two different snapshots depending on which request fired.

## 11. Transport-loss events are *not* in the history

Worth calling out because it can confuse contributors: the `[VM_DEATH]` and `[VM_GONE]` envelope strings returned to the agent (see [diagnostics.md](diagnostics.md)) are **tool-return strings**, not `EventHistory` entries. The actual `VM_DEATH` history entry is recorded by the listener at `JdiEventListener.java:311, 338` when it observes a `VMDeathEvent` / `VMDisconnectEvent` or catches `VMDisconnectedException`. The envelope strings exist to give the in-flight tool call a clean error message; the history entry exists to record the fact in the timeline.

In practice: if the agent calls `jdwp_get_events` after a transport loss, it sees the `VM_DEATH` entry. The tool call that *caused* the disconnect detection got a `[VM_DEATH]` envelope string as its return value.

## 12. Class-prepare events drive deferred-breakpoint promotion

The listener uses `ClassPrepareEvent` for two things:

- **Promote deferred breakpoints.** When the agent set a breakpoint on a class that hadn't loaded yet, the tracker registered a `ClassPrepareRequest` filtered by that class name. The handler at `JdiEventListener.java:979-1072` walks the pending registries for the class and converts each pending entry into a real `BreakpointRequest` / `ExceptionRequest` / watchpoint pair.
- **Catch static-initializer field writes.** Field-watchpoint promotion is **synchronous** inside the CPR handler (lines 1056-1058). The loading thread auto-resumes right after the handler returns and runs `<clinit>` — a static-initializer write would be lost if the watchpoint were installed asynchronously.

After promoting, the CPR is deleted if no pending entries reference the class any more (lines 1060-1067). The full deferred-promotion story is in [breakpoints.md](breakpoints.md).

## 13. Don't add state to the listener

Beyond the per-connection handles it starts and stops — the dispatcher, the session, the watchdog — the listener owns one piece of mutable state: the `vmDeathHookInvoked` atomic. Suspension state lives in `SuspensionSession`; everything else lives in `BreakpointTracker`, `EventHistory`, `EvaluationGuard`, or `JDIConnectionService`. Keeping the listener thin is a deliberate choice — it is the busiest piece of code in the system and the easiest place to introduce a race. New event-handling logic almost always belongs in a service the listener delegates to, not in a new field on the listener itself.

## 14. Suspension ownership

> **Background — suspend counts are shared**
>
> JDI keeps one suspend count per thread ([threading-and-safety.md](threading-and-safety.md) § 4). A thread runs only at count 0, and a resume at count 0 is a no-op. Every source of suspension adds to the same count: a stop-all event set, a thread event set, `ThreadReference.suspend()`, `vm.suspend()`. A resume cannot tell whose count it spends. A debugger that resumes "the thread" without knowing who holds it can free a stop the user has not seen yet, or leave a thread one count deeper than it believes.

`SuspensionSession` — one per connection, opened by `JdiEventListener.start`, closed by `stop()` and on VM death — records who owns each suspension the debugger causes. Only that owner releases it, exactly once. Tools never resume blindly: `vm.resume()` runs only as a context's own `EventSet.resume()`.

| Suspension | Owner | Released by |
|---|---|---|
| A routed event set | its context | the dispatcher's `finish` when it publishes no stop; once published and presented, a user release |
| A set raised inside an evaluation | its context | the listener, at once (`suppress`) |
| `jdwp_suspend_thread` | a manual hold | `jdwp_resume`, `jdwp_resume_until_event`, `jdwp_resume_thread`, a step, `jdwp_reset` |
| An invocation's borrowed count | the JDWP agent | the agent, when the invocation completes |
| The `jdwp_dump_locks` snapshot | the tool | the tool: `vm.suspend()` … `vm.resume()` in `finally` |

One monitor guards all session state. It spans the session's short JDWP commands — suspend, resume, a step's request creation — but never event processing, an invocation or a wait (waits use the monitor's own condition, which releases it). A closed session ignores every call, so a late completion from an old dispatcher cannot touch the next connection's state.

### Contexts

Every routed set becomes a `SuspensionSession.Context`: an id (never reused), the generation it was routed in, the event set, its policy, its event thread, and a state.

```
QUEUED ──begin──▶ PROCESSING ──finish──▶ HELD ──user release / reset──▶ RELEASED
   │                  │
   └──────────────────┴──finish (no stop), cancel──▶ RELEASED
```

- **QUEUED** — routed, waiting for the dispatcher.
- **PROCESSING** — the dispatcher runs its handlers.
- **HELD** — published as a stop; the user owns it. It is *unseen* until a presenting tool shows it.
- **RELEASED** — its suspension has been released; it leaves the session.

A context **claims** thread T when it is not RELEASED, T is not in its `released` set, and its policy is ALL, or EVENT_THREAD with T as its event thread. A `none` context claims nothing. It **covers** T when it claims T and T is not in its `exempt` set (a lease's exemption, below). `released` holds the threads a user release or the watchdog took out of a stop-all; `unblocked` the subset the watchdog took.

### Presentation

Three tools present stops; all select under the session monitor.

- `jdwp_get_current_thread` and `jdwp_get_breakpoint_context` call `presentCurrent()`: the current stop while it is still held on its event thread, else the oldest unseen stop, which becomes current. Both getters keep showing the same stop until it is released, and say how many further stops wait unseen. With nothing held they fall back to the last published snapshot (`BreakpointTracker.getLastBreakpoint()`) for display.
- `jdwp_resume_until_event` calls `nextStop()` (§ 9): the oldest unseen stop first.

The current stop is a live context, not a thread. It stops being current once its event thread is released, even while a stop-all keeps holding the other threads. Tools whose `threadId` is omitted use `currentStop()`, which presents nothing; with no current stop they answer "No current stop", adding how many stops wait unseen.

### Releases

- **`releaseAll()`** — `jdwp_resume`, and the first pass of `nextStop`. Releases every *presented* held stop, oldest first, and every manual hold. Unseen stops stay held and are listed ("not yet seen"); queued and processing sets are not touched and are counted ("still being processed"). With nothing to release the reply says so.
- **`releaseThread(T)`** — `jdwp_resume_thread`. Refuses, naming the blocker, when an evaluation runs on T, when a context covering T is QUEUED or PROCESSING, or when T is held by a stop not yet seen. Otherwise it releases what holds T: its manual hold, its own presented thread stop, and its share of a presented stop-all — T alone is resumed and joins that context's `released`; the other threads stay parked. If nothing holds T, it says so and resumes nothing.
- **`suspendThread(T)`** — `jdwp_suspend_thread`. Refuses a thread that is already suspended; otherwise `T.suspend()` and a manual hold. Holds do not stack.

Releasing a context (`release`) is compensated:

- **Stop-all:** every thread in `released` and `exempt` is suspended once more before the set's own `EventSet.resume()` (a `vm.resume()`), so the release nets zero on them. The compensation is relative: a released thread that has stopped again since keeps its new stop. JDI errors on dead threads are caught per thread.
- **Thread or `none` set:** `EventSet.resume()` (nothing for `none`).
- **The anchor of a running invocation** (below) keeps the borrower's share: a stop-all suspends the borrowing thread once more before its `vm.resume()`; a thread stop of the borrower alone skips its resume. Either way the share becomes the lease's *debt*, resumed when the lease ends. Every other thread is released at once.

### Steps

A step (`jdwp_step_over` / `_into` / `_out`) runs entirely under the session monitor (`SuspensionSession.step`):

1. admission: the refusals of `releaseThread(T)`, plus "nothing holds T";
2. the installer, under the `EventRequestManager`'s monitor, deletes T's old step request and creates the new one — policy `SUSPEND_EVENT_THREAD`, class exclusions before the count filter — and enables it;
3. the request is stamped with the generation and recorded as T's pending step;
4. T's holders are released as `releaseThread` would.

If the release fails, the request is deleted and the pending entry dropped, so a failed step leaves nothing armed.

With `SUSPEND_EVENT_THREAD`, only the stepping thread stops on landing. From a stop-all hold the other threads stay parked by that hold; from a thread stop the VM is not frozen. The landing publishes as a `STEP` stop and clears the pending entry. Any other stop published on the stepping thread ends the step: its request is deleted and `STEP_INTERRUPTED` recorded. While any step is pending, `nextStop` releases nothing and only waits (§ 9).

Example, from stop-all hold B (every thread at count 1):

1. The step releases T: T at 0, B's `released` is {T}.
2. The step lands as context S (thread policy): T at 1.
3. `jdwp_resume_until_event` presents S.
4. `jdwp_resume` releases B first — it compensates T (T at 2), then `vm.resume()` (T at 1, the others at 0) — then S (T at 0). Everything runs.

### Reset

`jdwp_reset` deletes every breakpoint and step request first — a thread the release lets run can then no longer leave a stop behind — and then calls `SuspensionSession.reset()`:

- the generation advances;
- every held stop, seen or not, and every manual hold is released;
- pending step requests are deleted;
- the current stop is cleared and waiters wake (`nextStop` returns `RESET`).

Queued and processing sets of the old generation finish normally but cannot publish: `finish` releases them and drops their buffered history. Step requests carry their generation (request property `mcp.generation`), so a step event of an earlier generation routed after the reset is stale and its set is released too. Other requests are not stamped; an event of a request the reset deleted is dropped by the deleted-request rule (§ 1).

### Leases and admission

Every invocation the server makes in the target runs inside an `EvaluationGuard` lease ([threading-and-safety.md](threading-and-safety.md) § 5). The outermost level of a thread's lease calls `SuspensionSession.lease(T)`, which admits the invocation or refuses it with a reason (`EvaluationRefusedException`), atomically under the monitor.

- **Anchor.** A single-threaded invocation borrows one count of its thread: the JDWP agent resumes the thread once when the invocation starts and suspends it once when it completes. The anchor is the context that count belongs to. For the dispatcher it is the set being processed, when T is that set's event thread and the policy is not `none`. Any other caller needs T stopped at an event of its own that a tool has presented: a HELD, reported context with T as its event thread that is invocable — VMStart is not; the JDWP agent does not enable invocation at VM start.
- **Refusals.** An evaluation already running on T; a context covering T that is queued, processing or unseen; T only a bystander of another thread's stop-all; T held only by `jdwp_suspend_thread`; T not stopped by this session at all. Each is refused with its reason.
- **Count check.** T's suspend count must carry every hold the session knows: the anchor, every other covering context, and a manual hold. A count below that means a hold was undone outside the session — refused, nothing is resumed. A count above it is a set not routed yet, which routing exempts.
- **Exemptions.** T is resumed once out of every other context covering it and joins that context's `exempt`; a manual hold of T is resumed too. The borrowed count is then T's only one, and the invocation can run. A stop-all routed while leases are active exempts every leased thread other than its own event thread (`route`).
- **End (`unlease`).** T is suspended once back into each exempting context that still holds — one released meanwhile has compensated T already. A manual hold still present is put back. Last, the debt is resumed.
- **Transfer.** If the anchor is released while its invocation runs (`jdwp_resume`, `jdwp_reset`, `nextStop`'s release), the borrower keeps its share (see Releases). `jdwp_resume_thread` and a step on a leased thread are refused ("an evaluation is running on T").

**A lease spans invocations only, never frame reads.** JDI invalidates every `StackFrame` of a thread whenever the debugger resumes it, however many suspensions remain, and both an invocation and an exemption are such a resume. So the evaluator reads its frame first (the locals, `this`, the frame's class loader) and leases the thread only around its invocations; the fast-logpoint arming takes one lease per invocation group, not one across the whole arming. Frame tools that meet invalidated frames re-read them ([threading-and-safety.md](threading-and-safety.md) § 5).

### The watchdog

A dispatcher invocation — a condition, a logpoint, a fast-logpoint arming — can block on a monitor owned by a thread that a set queued behind it keeps parked. The dispatcher never reaches that set, and no tool releases a set before it is processed. (A tool's own invocation has a bounded wait instead: `InvocationRunner`, [threading-and-safety.md](threading-and-safety.md) § 5.)

`SuspensionWatchdog` runs per session on the daemon thread `jdi-suspension-watchdog`; the listener starts it with the session, and it stops once the session is closed. Every 500 ms it samples the session's dispatcher leases (`dispatcherInvocations()`). The lease object is the sample's token, so samples never carry over to a later invocation on the same thread. An invocation watched for more than 3 s whose thread shows status MONITOR in two consecutive samples has its monitor's owner looked up — a balanced suspend → `currentContendedMonitor().owningThread()` → resume of the invoking thread, outside the session monitor. Then `unblock(token, owner)` re-checks everything under the monitor and releases nothing unless:

- the token is still a live dispatcher lease;
- the owner is not leased, not reserved by a frame tool, and not manually held;
- no HELD context covers the owner, and a covering context whose event thread is the owner is still QUEUED, not VMStart, and has no request marked `KEEPS_THREAD_PROPERTY`;
- at least one context covers the owner, and the owner's suspend count equals their number. A count above that is a hold the session does not know — a set not routed yet, a VM-wide suspend in progress, something external — and is never released.

Then, for each context covering the owner:

- **A bystander** of a stop-all is resumed once and joins the context's `released` and `unblocked` sets. A stop later published from that context is partial; `jdwp_resume_until_event`, `jdwp_get_current_thread` and `jdwp_get_breakpoint_context` say so.
- **The event thread** of a queued set cancels that set (`cancel`). A thread set is settled at once — its `EventSet.resume()` is the thread's resume, and the context is RELEASED — so its later processing has nothing left to release and cannot spend the count of a stop the thread reaches meanwhile. A stop-all resumes only the event thread, which joins `released`; the set keeps its other threads until `finish` releases it with compensation. A cancelled set never publishes, and a pending step it would have landed is dropped. On the dispatcher, `begin` returns `false` and `settleCancelled` runs only what needs no stopped thread: an arming hit re-arms, a step's request is deleted, a class prepare still promotes its deferred breakpoints. Conditions, logpoints and stops are dropped.

A set with a fast breakpoint's stop event is never cancelled: its request carries `KEEPS_THREAD_PROPERTY`, because handling it, even to skip it, reads the stop's arguments from the frame.

Records: `EVALUATION_UNBLOCKED` (the owner, the evaluating thread, the sets) and `EVENT_CANCELLED`.

Limits: a spin or a native wait (status RUNNING) is not detected, nor is a dependency on a thread a published stop holds. `jdwp_resume` or `jdwp_reconnect` recover those.

### Attach

After `jdwp_connect` or `jdwp_wait_for_attach` connects, outside the connection service's monitor, `acknowledgeStart` waits up to 500 ms for the VMStart set. A suspending VMStart is published as a held stop and marked seen; the attach reply says the VM is suspended at start. A `none` VMStart (`suspend=n`) is history only. If none arrives — a re-attach: the JDWP agent reports VMStart only to its first connection — a later one is published unseen, and the next `jdwp_resume_until_event` presents it.

### Testing

`FakeSuspendModel` (test sources) models the JDWP agent's suspend bookkeeping behind JDI mocks — per-thread counts with the count-0 no-op, `vm.suspend` / `resume` with the global count that newborn threads inherit, `EventSet.resume()` by policy, an invocation's borrowed count, invocability, thread status — so the session's tests assert the counts the target would have rather than which calls were made.
