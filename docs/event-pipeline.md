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

The listener only drains and routes. Processing an event set can invoke target code through compiled conditions and logpoint expressions, fast-probe arming (including a fast breakpoint’s capture), or classpath prewarm. A target method called this way can hit a breakpoint of its own. The invocation cannot return until that second event is drained and its thread resumed. So the sets are processed on a second thread, `jdi-event-dispatcher` (a single-thread executor per connection, so they stay in arrival order and their evaluations stay serialized), while the listener keeps draining:

- a `VMDeath` / `VMDisconnect` set is processed on the listener thread after the dispatcher has finished the sets before it (bounded at 2 s). First the in-target probes are read (`FastLogpointService.readBeforeExit`, field reads only) and the set is resumed — a `VMDeathRequest` with `SUSPEND_ALL`, kept while fast probes exist, holds the target at its exit until then — and the exiting hook snapshots what the exit takes with it (`JDIConnectionService.captureExit`, for the `[VM_DEATH]`, `jdwp_reset` and `jdwp_disconnect` replies). Then the session is closed, and the set gets no context. Sets still queued after that are dropped unprocessed, so `VM_DEATH` stays the last entry; a set already being processed — a long compile, say — finishes on the closed session, which drops its buffered history and publishes nothing (§ 3);
- a set whose thread is inside an MCP-driven evaluation (it holds a lease, § 14) is **suppressed** on the listener thread at once (`suppress`): it is routed as a context and finished immediately. In between, each event is recorded as `*_SUPPRESSED` (a step's request is deleted too); a fast-logpoint arming hit is re-armed and a fast breakpoint's stop skipped instead; a class prepare still promotes its deferred breakpoints. With no stop candidate, `finish` releases the set, which lets the waiting invocation finish. This path never evaluates, arms or pre-warms — not even if the evaluation has ended by the time the set is handled — so the listener thread never calls into the target;
- every other set is routed as a QUEUED context (`SuspensionSession.route`) and handed to the dispatcher.

Only a set's own context releases its suspension. The dispatcher's `finish` ends each set's processing. It publishes the set's first stop candidate as a **held stop**, or attempts to release the set. A tool releases a published stop after presentation. Tools can also recover a failed automatic release or retry an eligible watchdog release. Every retry still spends only the suspension its context owns. § 14 has the full model.

Because a set can wait on the dispatcher, the handlers re-apply the rule JDI applies when a set leaves its queue to one case: an event whose request was **deleted** meanwhile (a cleared logpoint, a class redefined by a fast logpoint) is dropped and does not keep its set suspended — otherwise the stale hit would be untracked and park its thread for good. An event whose tracked request was merely **disabled** meanwhile still counts as a hit — it was one when it happened.

Lifetime:

- Started by `JDIConnectionService.connect(...)` after the JDI attach succeeds.
- Stopped by `cleanupSessionState` on `disconnect`, on reconnect-to-different-target, and on `notifyVmDied`.
- Self-terminates when the loop catches `VMDisconnectedException` / `IllegalStateException` from `vm.eventQueue().remove()`: it waits for the dispatcher (bounded), closes the session and records `VM_DEATH`.

`stop()` closes the session, which wakes stop waiters with `CLOSED` (§ 9), stops the watchdog and shuts down the dispatcher. An `invokeMethod` in flight fails once the VM goes. It then interrupts the listener, joins it for up to 500 ms, and runs the VM-death hook. It still calls `breakpointTracker.fireNextEvent()`, but no production tool waits on the tracker's latch any more.

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
2. **An exception thrown from a handler defaults to suspend.** Several handlers catch, record the error and return `true`; an exception escaping `process` sets `keep` in `dispatch`. A set kept without a candidate (an untracked breakpoint) is published as a plain breakpoint stop of its thread. The principle is fail-safe — if something is broken, the agent gets a chance to inspect; we never silently resume on an unexpected error. One exception: a set routed as one that never stops (`mayStop` false — only logpoints, exception and field logpoints, a fast logpoint's arming hit, class prepares) never publishes. The redefinition rule (`breakpoints.md` § 5d) did not count its thread, so the method it stopped in may have been redefined meanwhile; `SuspensionSession.finish` releases it, and the handler's error stays in the history.

The remote classpath pre-warm runs before publishing the first suspending stop. Local project discovery starts at attach on its own daemon thread and is cancelled when that connection ends. Compiled dispatcher evaluations use the remote classpath while local discovery is pending. If local discovery finishes during the remote walk, the dispatcher merges the ready result at publication without another remote walk. A remote-only compile that misses a type retries on the same hit if the current local result is ready. A fast probe that needs a running scan or whose retry result is superseded defers to a later hit. The deferral adds no stop. The event set resumes only when no other event in it requests a stop.

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

On the dispatcher none of them takes effect at once. While a set is processed (`SuspensionSession.beginProcessing`, a thread-local), the snapshot becomes a **stop candidate** of the set's context, the history entry is **buffered** in the context, and `fireNextEvent()` returns without doing anything. The fast-breakpoint service's `publishStop` follows the same sequence. It includes the optional capture in the stop candidate and in the `FAST_BREAKPOINT` history entry. Publication keeps the first accepted candidate's identity and includes captures from all accepted candidates, keyed by breakpoint ID.

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
| | Either mode + condition false | false | No stop, log entry, or chain effect |
| | Log-only + true or absent condition | false | Evaluate, log, chain effect |
| | Suspending + true or absent condition | **true** | Candidate, history, chain effect when `firingId != null` |
| | Condition evaluation fails | By mode | Record `CONDITION_ERROR` and process the hit as if the condition passed |
| `WatchpointEvent` | Reentrancy-guarded | false | `FIELD_BREAKPOINT_SUPPRESSED` |
| | Untracked | **true** | Defensive |
| | Condition false | false | No chain effect |
| | Logpoint | false | Evaluate, log, chain effect |
| | Suspending | **true** | Candidate, history, chain effect |
| `ClassPrepareEvent` | always | (never stops) | Promotion side effect only; the CPR's own `SUSPEND_EVENT_THREAD` policy parks the loading thread until `finish` releases the set |
| `VMStartEvent` | always | **true** | `VM_START` candidate: the VM stays suspended so the agent can set up. Compiled evaluation needs a later stop (§ 14) |
| `VMDisconnect` / `VMDeath` | always | exits loop | Death set only, processed on the listener without a context; sets `running=false`, runs the death hook |

Fast logpoints and fast breakpoints are decided by `FastLogpointService` (`handleArmingHit` → `STOPPED` / `RESUME`, `handleStopHit`) under the same candidate rules; see [breakpoints.md](breakpoints.md) § 5a and § 5c. Arming and removal never redefine a method a held thread is in (§ 5d).

A few things stand out from the matrix:

- **Untracked events suspend.** If JDWP delivers a breakpoint or watchpoint event for a request the tracker has no record of (extremely rare — the only way is concurrent registration races during cleanup), the dispatcher errs on the side of inspection. Log a warning, suspend, let the agent figure it out. The alternative (silent resume) would mask real bugs. A request deleted while its set waited is the exception — that event is dropped (§ 1).
- **Conditional-false skips chain effects.** A condition that evaluates to `false` is "not a meaningful hit", so it does not count as a trigger fire for the chain dependency graph. Documented inline in `handleBreakpointEvent`.
- **Logpoints always suspend briefly.** A logpoint expression needs a held frame. The JDI request uses `SUSPEND_EVENT_THREAD`; the dispatcher evaluates, records, and the handler returns `false`, so `finish` releases the set. From the agent's perspective the thread never paused — but JDI did pause it long enough for the evaluation to be legal.

## 6. Conditional-breakpoint evaluation

`evaluateConditionWithBindings` routes line, exception and field breakpoint conditions on the dispatcher. Navigation uses the frame held by the event set and takes no invocation lease. The watchdog samples dispatcher leases, so navigation leaves queued suspensions alone. Compiled conditions configure the compiler classpath and use the event's existing invocation admission.

- **Fail-safe.** A parser, planner, compilation, execution or result-type error records `CONDITION_ERROR` and treats the condition as true. A suspending breakpoint stops and a logpoint processes its log expression.
- **Boolean results.** Both primitive `BooleanValue` and boxed `java.lang.Boolean` are accepted.

For a fast breakpoint's arming hit, `FastLogpointService` uses the compiled evaluator for the condition, then claims the true-hit gate before running any compiled capture. Capture reads a fresh frame and takes a separate lease on the same event thread (§ 14), outside the service monitor. Fast-probe arming wrappers keep returned objects reachable in the target through the debugger's read under the lease, then clear the reference. The dispatcher publishes the capture after checking that the entry and its claimed generation are still current. If capture compilation needs a type while local classpath discovery is pending, the dispatcher reopens its gate and defers arming to a later hit.

## 7. Logpoint evaluation

Logpoints piggyback on the same models as suspending breakpoints — they are just breakpoints whose metadata sets `BreakpointMetadata.logpointExpression`. Field logpoints use `FieldBreakpointSpec.logOnly = true`.

When a logpoint fires:

1. Run the same reentrancy and tracking checks as a normal BP.
2. If a condition is set, evaluate it. False → return false, no log, no chain.
3. Route the log expression through `evaluateAndFormat`. Navigation reads the held frame and object values. Compilation configures the classpath and invokes on the event thread.
4. Record `LOGPOINT` to event history with the formatted result.
5. Apply chain effects.
6. Return `false` → no candidate; unless another event of the set stops, `finish` releases the set.

Exception and field logpoints follow the same path after adding their event bindings. All three are subject to the same fail-safe rule: if the log expression fails, record `LOGPOINT_ERROR` / `EXCEPTION_LOG_ERROR` / `FIELD_LOGPOINT_ERROR` with the failure reason and continue.

Line, exception and field logpoint results share the same formatter. Boxed primitives are rendered by reading their `value` field. Other objects appear as `Object (type)` with no inspection-cache entry. The formatter also handles field-event values, including `$oldValue` and `$newValue`, and invokes no target methods.

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

`jdwp_resume_until_event` runs `SuspensionSession.nextStop` under the session monitor. Selecting a stop, releasing, and registering as a waiter are atomic with respect to publication. The monitor's condition (`wait` / `notifyAll`) lets the dispatcher publish stops during the wait. For a supplied `threadId`, both `jdwp_resume_until_event` and `jdwp_resume_thread` verify that their session belongs to the VM they fetched. If a reconnect replaced the session in between, the call returns `[CONNECTION_CHANGED]` before lookup. Use `jdwp_get_threads` to verify the target and look up a fresh ID, since the replacement VM can reuse thread IDs. The tools look in the matching session's contexts, including recorded watchdog failure owners, pending steps, manual holds and unpaid lease debts before enumerating the VM's threads. This covers virtual threads omitted from enumeration. If enumeration fails during a wait, the tool checks the same session again so a transport-loss reply can name a stop published during lookup.

1. **A matching unseen stop exists** → select the oldest match without releasing any holds. Omit `threadId` to match any thread, or supply it to match only stops whose event thread has that ID. The selected stop becomes current on an open session. A closed session includes the selected stop in its VM-death reply.
2. **The session is closed** (disconnect, death, reconnect) → `CLOSED`.
3. **`jdwp_reset` changed the generation** → `RESET`. A thread-targeted wait can only present stops from the generation it started in.
4. **A targeted wait has a recovery hold** → `RESUME_FAILED` if a failed automatic release covers T or retains a temporary suspension on T after a failed rollback, or if T has unpaid lease debt. A recovery on an unrelated thread leaves the wait running.
5. **A targeted wait has an ordinary blocker** → return a refusal naming it. Every pass checks for an evaluation on T, a queued or processing context covering T, or a foreign unseen stop-all covering T. Routing a covering context wakes the waiter before publication.
6. **First pass only** → without `threadId`, release eligible holds and retry failed releases as `jdwp_resume` does (§ 14), unless any step is pending. With `threadId=T`, release only the ordinary holds on T and skip the release when T has a pending step. A failed attempt returns `RESUME_FAILED`, or `CLOSED` if the release reported a disconnect.
7. **An unfiltered wait has a recovery hold** → `RESUME_FAILED` for any failed automatic release or unpaid lease debt still in the session. Automatic-release failures and new lease debt wake parked waiters.
8. **Deadline passed** → `TIMEOUT`. Otherwise wait for the remaining time and select again.

With `wait=true`, a step waits for the context that ended its own request (§ 14). Its wait skips the release pass. It lets the dispatcher finish processing a stop on the stepping thread and returns early if a foreign context blocks progress. A reset invalidates the retained completion even if another call already presented that stop.

For `[RESUME_FAILED]`, use `jdwp_resume_thread(threadId=…)` with the reported thread ID when available. For an automatic release, this is the event thread, which may differ from the waiting thread. Retrying a failed automatic stop-all releases its VM-wide suspension. `jdwp_resume` also releases every other presented stop and every `jdwp_suspend_thread` hold, including any stop-all a pending step relies on.

The tools render stops as `Event fired …` using their captured location when available, including the user-code location of a fast breakpoint. When an unfiltered wait hides probe frames, the header shows logical frame 0. If that read fails for an exception inside a probe, the fallback labels the captured location `Probe throw site` without another frame read. Live frame data is included only when the same context holds the thread before and after the reads. If a live read fails, the captured report can still identify the thread, event kind, breakpoint ID and available location. The `[VM_GONE]` reply names any selected stop. A closed session reports `[VM_DEATH]` with the selected stop, including when it closes during an unsuccessful frame read. Replies that report a selected stop include its published fast-breakpoint captures, including for a targeted wait, a step with `wait=true`, or a reply after transport loss. Waits and presenting getters use the selected session's epoch for exit details and omit diagnostics they cannot attribute to that connection. The timeout, release summary and continuation advice survive optional diagnostic failures. A targeted wait reports the count of other threads' unseen stops when the count is positive.

Wait replies keep confirmed release progress and any failures, including on VM death or reset. A global release summary counts pending sets at the end of the wait while the session is open. The reset reply lists any holds it could not release.

Apart from reset and session close, a published stop stays held until a tool presents it. Skipping another thread's stop leaves it eligible for a later presentation. After presentation, its hold stays owned by the session until the release succeeds.

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
| A routed event set | its context | `finish` when it publishes no stop, or a user release after presentation or a failed automatic release |
| A set raised inside an evaluation | its context | the listener (`suppress`), or a user retry if that release fails |
| `jdwp_suspend_thread` | a manual hold | `jdwp_resume`, `jdwp_resume_until_event`, `jdwp_resume_thread`, a step, `jdwp_reset` |
| An invocation's borrowed count | the JDWP agent | the agent, when the invocation completes |
| Unpaid lease debt | the session | the resume tools or `jdwp_reset` |
| The `jdwp_dump_locks` snapshot | the tool | the tool: `vm.suspend()` … `vm.resume()` in `finally` |

One monitor guards all session state. It spans the session's short JDWP commands — suspend, resume, a step's request creation — but never event processing, an invocation or a wait (waits use the monitor's own condition, which releases it). The session holds a weak VM identity for connection checks. After close, it refuses live thread lookup and can still supply captured stop reports for terminal replies. A late completion from an old dispatcher cannot touch the next connection's state.

### Contexts

Every routed set becomes a `SuspensionSession.Context`: an id (never reused), the generation it was routed in, the event set, its policy, its event thread, and a state.

```
QUEUED ──begin──▶ PROCESSING ──finish──▶ HELD ──user release / reset──▶ RELEASED
   │                  │
   └──────────────────┴──finish (no stop), cancel──▶ RELEASED
                      └──finish (resume failed)──▶ RELEASE_FAILED ──retry──▶ RELEASED
```

- **QUEUED** — routed, waiting for the dispatcher.
- **PROCESSING** — the dispatcher runs its handlers.
- **HELD** — published as a stop; the user owns it. It is *unseen* until a presenting tool shows it.
- **RELEASE_FAILED** — processing ended without publishing. The context still owns the suspension and carries the exception from its failed automatic release.
- **RELEASED** — its suspension has been released; it leaves the session.

A context **claims** thread T when it is not RELEASED, T is not in its `released` set, and its policy is ALL, or EVENT_THREAD with T as its event thread. A `none` context claims nothing. It **covers** T when it claims T and T is not in its `exempt` set (a lease's exemption, below). `released` holds the threads a user release or the watchdog took out of a stop-all; `unblocked` the subset the watchdog took.

### Presentation

Tools that present stops select them under the session monitor.

- `jdwp_get_current_thread` and `jdwp_get_breakpoint_context` call `presentCurrentReport()`. They show the current stop while it is held on its event thread, otherwise the oldest unseen stop becomes current. Both getters use the captured report if live status or frame reads fail after selection. If the session closed during those reads, they name the selected stop in `[VM_DEATH]`. With nothing held they fall back to the last published snapshot (`BreakpointTracker.getLastBreakpoint()`) for display.
- `jdwp_resume_until_event` presents the oldest unseen stop matching its optional `threadId` (§ 9). A step with `wait=true` presents the stop that ended that step. When another call already presented it, the step reply identifies the same stop without changing the current stop.

The current stop is a live context, not a thread. It stops being current once its event thread is released, even while a stop-all keeps holding the other threads. Tools whose `threadId` is omitted use `currentStop()`, which presents nothing; with no current stop they answer "No current stop", adding how many stops wait unseen.

### Releases

- **`releaseAll()`** — `jdwp_resume` and the first pass of an unfiltered `nextStop` when no stop is unseen and no step is pending. Releases presented stops, oldest first, and manual holds. It retries failed automatic releases, unpaid lease debts and eligible failed watchdog releases. Unseen stops stay held and are listed as "not yet seen". Queued and processing sets otherwise stay parked and count as "still being processed".
- **`releaseThread(T)`** — `jdwp_resume_thread`. Retries T's unpaid lease debt, eligible failed watchdog releases on T, and failed automatic releases of sets whose event thread is T. An automatic retry releases the whole finished set. The reply names the VM-wide suspension for a stop-all. Ordinary releases cover T's manual hold, its own presented thread stop and T's share of a presented stop-all. T joins each such stop-all's `released` set and the other threads stay parked. An active evaluation, a queued or processing context, an unseen stop or a failed automatic release on another event thread can block the ordinary release.
- **Targeted `nextStop`** checks for recovery holds and ordinary blockers on every pass, and can release ordinary holds only on its first pass. With no holds to release it waits for T's next stop. The explicit `jdwp_resume_thread` reports when there was nothing to release.
- **`suspendThread(T)`** — `jdwp_suspend_thread`. Refuses a thread that is already suspended; otherwise `T.suspend()` and a manual hold. Holds do not stack.

Each ownership change commits after its JDI resume succeeds. A failed attempt leaves the stop, share, or manual hold available for retry. The resume replies list the successful releases and identify each failed hold with its JDI exception. A `VMDisconnectedException` closes the session and stops the release pass.

Releasing a context (`release`) is compensated:

- **Stop-all.** Every thread in `released` and `exempt` is suspended once more before the set's own `EventSet.resume()` (a `vm.resume()`), so the release nets zero on them. A released thread that has stopped again keeps its new stop. Compensation skips a collected thread.
- **Thread or `none` set:** `EventSet.resume()` (nothing for `none`).
- **The anchor of a running invocation** (below) keeps the borrower's share: a stop-all suspends the borrowing thread once more before its `vm.resume()`; a thread stop of the borrower alone skips its resume. Either way the share becomes the lease's *debt*, resumed when the lease ends. Every other thread is released at once.

If compensation or the set resume fails while the VM is reachable, the session undoes the temporary suspensions it added. If an undo also fails, the context keeps ownership of that extra suspension. A retry reuses it if compensation is still needed, or removes it when the original hold needs no compensation. A lease that ends can use an existing temporary suspension to restore its share of a context it was taken out of.

### Steps

A step (`jdwp_step_over` / `_into` / `_out`) runs entirely under the session monitor (`SuspensionSession.step`):

1. admission checks for the same blockers as a targeted release, refuses unpaid lease debt on T, and requires a hold on T;
2. the installer, under the `EventRequestManager`'s monitor, deletes T's old step request and creates the new one — policy `SUSPEND_EVENT_THREAD`, class exclusions before the count filter — and enables it;
3. the request is stamped with the generation and recorded as T's pending step;
4. T's ordinary holders are released with the same success checks as `releaseThread`.

If a release fails, the session deletes the step request and drops its pending entry. The tool returns without waiting for a landing even when `wait=true`. The reply reports earlier successful releases and all failures. A disconnect returns `[VM_DEATH]` and directs the user to reattach.

With `SUSPEND_EVENT_THREAD`, only the stepping thread stops on landing. The arming summary reports the session’s ownership immediately after release, including failed automatic releases and unpaid lease debt. It reports remaining stop-alls independently of JDI enumeration, which can omit virtual threads. Stop-alls with the same state and origin are counted together. The arming summary shows up to five thread names across the stepping thread, stop-all origins and samples, and other hold groups. Further groups are summarized by count. The appended outcome report for `wait=true` is outside that budget because it identifies the stop. The summary says all remaining threads run only when no stop-all or outstanding recovery remains. Publication records the ending context on the armed-step handle and clears the pending entry. The landing is a `STEP` stop. Any other stop published on the stepping thread ends the step, deletes its request and records `STEP_INTERRUPTED`.

The step tools return after arming by default. With `wait=true` they retain the armed-step handle. The reply appends the report of its landing or interrupting stop, or identifies it as already presented by another call. The wait uses the default 30 s deadline. A timeout leaves the step armed and gives `jdwp_resume_until_event(threadId=T)` as the continuation.

Example, from stop-all hold B (every thread at count 1):

1. The step releases T: T at 0, B's `released` is {T}.
2. The step lands as context S (thread policy): T at 1.
3. A step with `wait=true` or `jdwp_resume_until_event(threadId=T)` presents S.
4. `jdwp_resume` releases B first — it compensates T (T at 2), then `vm.resume()` (T at 1, the others at 0) — then S (T at 0). Everything runs.

### Reset

`jdwp_reset` deletes every breakpoint and step request first — a thread the release lets run can then no longer leave a stop behind — and then calls `SuspensionSession.reset()`:

- the generation advances;
- the session attempts to release every held stop and manual hold. It retries failed automatic releases, unpaid lease debts and eligible failed watchdog releases. The reply counts recovered releases and reports any failures;
- pending step requests are deleted;
- the current stop is cleared and stop waiters wake with `RESET`.

Queued and processing sets of the old generation finish normally but cannot publish: `finish` releases them and drops their buffered history. Step requests carry their generation (request property `mcp.generation`), so a step event of an earlier generation routed after the reset is stale and its set is released too. Other requests are not stamped; an event of a request the reset deleted is dropped by the deleted-request rule (§ 1).

If the release pass disconnects, the reply keeps the progress already made and directs the user to reattach.

### Leases and admission

Every invocation the server makes in the target runs inside an `EvaluationGuard` lease ([threading-and-safety.md](threading-and-safety.md) § 5). The outermost level of a thread's lease calls `SuspensionSession.lease(T)`, which admits the invocation or refuses it with a reason (`EvaluationRefusedException`), atomically under the monitor.

- **Anchor.** A single-threaded invocation borrows one count of its thread: the JDWP agent resumes the thread once when the invocation starts and suspends it once when it completes. The anchor is the context that count belongs to. For the dispatcher it is the set being processed, when T is that set's event thread and the policy is not `none`. Any other caller needs T stopped at an event of its own that a tool has presented: a HELD, reported context with T as its event thread that is invocable — VMStart is not; the JDWP agent does not enable invocation at VM start.
- **Refusals.** An evaluation already running on T; a context covering T that is queued, processing or unseen; T only a bystander of another thread's stop-all; T held only by `jdwp_suspend_thread`; T not stopped by this session at all. Each is refused with its reason.
- **Count check.** T's suspend count must carry every hold the session knows: the anchor, every other covering context, and a manual hold. A count below that means a hold was undone outside the session — refused, nothing is resumed. A count above it is a set not routed yet, which routing exempts.
- **Exemptions.** T is resumed once out of every other context covering it and joins that context's `exempt`; a manual hold of T is resumed too. The borrowed count is then T's only one, and the invocation can run. A stop-all routed while leases are active exempts every leased thread other than its own event thread (`route`).
- **End (`unlease`).** The session clears an event-set exemption after its count is restored, using an existing compensation, a successful suspend or an available lease debt count. If a suspend fails and there is no debt to use, the session logs the failure and continues. The session repays any remaining lease debt last. A rejected repayment leaves the count session-owned and wakes next-stop waiters.
- **Frame tools first.** While a frame tool reads T (`reserve`), a lease of T waits until it is done — at most 2 s, then it is refused — or the invocation would run T under the tool ([threading-and-safety.md](threading-and-safety.md) § 5).
- **Transfer.** If the anchor is released while its invocation runs (`jdwp_resume`, `jdwp_reset`, `nextStop`'s release), the borrower keeps its share (see Releases). `jdwp_resume_thread` and a step on a leased thread are refused ("an evaluation is running on T").

**A lease spans invocations only, never frame reads.** JDI invalidates every `StackFrame` of a thread whenever the debugger resumes it, however many suspensions remain, and both an invocation and an exemption are such a resume. So the evaluator reads its frame first (the locals, `this`, the frame's class loader) and leases the thread only around its invocations; the fast-logpoint arming takes one lease per invocation group, not one across the whole arming. Frame tools that meet invalidated frames re-read them while a published stop or a manual hold keeps the thread at its stop ([threading-and-safety.md](threading-and-safety.md) § 5).

### The watchdog

A dispatcher invocation — a condition, a logpoint, a fast-logpoint arming — can block on a monitor owned by a thread that a set queued behind it keeps parked. The dispatcher never reaches that set, so the watchdog can release the monitor owner's share under the checks below. A tool's own invocation has a bounded wait through `InvocationRunner` ([threading-and-safety.md](threading-and-safety.md) § 5).

`SuspensionWatchdog` runs per session on the daemon thread `jdi-suspension-watchdog`; the listener starts it with the session, and it stops once the session is closed. Every 500 ms it samples the session's dispatcher leases (`dispatcherInvocations()`). The lease object is the sample's token, so samples never carry over to a later invocation on the same thread. An invocation is aged from the sample that first sees its thread running; a sample that finds the thread suspended — host-side work between two invocations of the lease — starts the next invocation afresh (#47 § 2). A thread JDWP shows as blocked (MONITOR) or running is asked for the monitor it waits for, during a balanced suspension; running is asked too because JDWP shows a thread that invokes from inside its event's handling as running even while it waits for a monitor (#47 § 4). An invocation older than 3 s that waits for the same monitor in two consecutive samples has its monitor's owner looked up, and the decision made, during one balanced suspension of the invoking thread: suspend → `currentContendedMonitor().owningThread()` → `unblock(token, owner, monitor)` → resume. Suspended, the invoking thread cannot take the monitor and move on before the decision. `unblock` re-checks everything under the session monitor and releases nothing unless:

- the token is still a live dispatcher lease;
- the owner is not leased, not reserved by a frame tool, and not manually held;
- no HELD context covers the owner, and a covering context whose event thread is the owner is still QUEUED, not VMStart, and has no request marked `KEEPS_THREAD_PROPERTY`;
- at least one context covers the owner, and the owner's suspend count equals their number. A count above that is a hold the session does not know — a set not routed yet, a VM-wide suspend in progress, something external — and is never released;
- the owner, thus proven parked, still owns the monitor — not one it left before it stopped at a new event, which would cancel a stop that never blocked the evaluation.

Then, for each context covering the owner:

- **A bystander** of a stop-all is resumed once and joins the context's `released` and `unblocked` sets. A stop later published from that context is partial; `jdwp_resume_until_event`, `jdwp_get_current_thread` and `jdwp_get_breakpoint_context` say so.
- **The event thread** of a queued set cancels that set (`cancel`). A thread set is settled at once — its `EventSet.resume()` is the thread's resume, and the context is RELEASED — so its later processing has nothing left to release and cannot spend the count of a stop the thread reaches meanwhile. A stop-all resumes only the event thread, which joins `released`; the set keeps its other threads until `finish` releases it with compensation. A cancelled set never publishes, and a pending step it would have landed is dropped. On the dispatcher, `begin` returns `false` and `settleCancelled` runs only what needs no stopped thread: an arming hit re-arms, a step's request is deleted, a class prepare still promotes its deferred breakpoints. Conditions, logpoints and stops are dropped.

A set with a fast breakpoint's stop event is never cancelled: its request carries `KEEPS_THREAD_PROPERTY`, because handling it, even to skip it, reads the stop's arguments from the frame.

A failed watchdog resume leaves the context's ownership and any pending step intact. The watchdog can retry on a later tick. A resume tool or reset can retry the same release if the original lease is still active, the same thread still owns the monitor, and the current sets and suspend count still qualify. Success is recorded only after the resume completes.

Records: `EVALUATION_UNBLOCKED` (the owner, the evaluating thread, the sets) and `EVENT_CANCELLED`.

Limits: a spin or a native wait (no contended monitor) is not detected, nor is a dependency on a thread a published stop holds. `jdwp_resume` or `jdwp_reconnect` recover those.

### Attach

After `jdwp_connect` or `jdwp_wait_for_attach` connects, outside the connection service's monitor, `acknowledgeStart` waits up to 500 ms for the VMStart set. A suspending VMStart is published as a held stop and marked seen; the attach reply says the VM is suspended at start. A non-suspending VMStart is history only. A reattach or a delayed start event can leave the handshake without a VMStart. A later suspending start is published unseen, and the next `jdwp_resume_until_event` presents it. `requireVmStart=true` refuses existing sessions and detaches unless the handshake observed a suspending VMStart. The presence of suspended threads alone is used only for permissive attach guidance.

### Testing

`FakeSuspendModel` (test sources) models the JDWP agent's suspend bookkeeping behind JDI mocks — per-thread counts with the count-0 no-op, `vm.suspend` / `resume` with the global count that newborn threads inherit, `EventSet.resume()` by policy, an invocation's borrowed count, invocability, thread status — so the session's tests assert the counts the target would have rather than which calls were made.
