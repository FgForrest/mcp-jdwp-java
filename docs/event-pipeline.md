# Event pipeline

JDI delivers every interesting target-VM event — breakpoint hits, steps, exceptions, field accesses, class loads, VM start, VM death — to a single queue inside the server JVM. The `JdiEventListener` is the daemon thread that drains it. Every decision it makes (suspend? auto-resume? evaluate a condition? promote a deferred breakpoint?) sits in one ~1000-line file, and the contract that all the other components depend on is documented here.

## 1. The listener thread

> **Background — why a JDI debugger must drain its event queue**
>
> JDI events ride the same JDWP socket as command replies. The target VM's JDWP agent serialises events into composite event packets and writes them to the socket; the debugger reads them out via `VirtualMachine.eventQueue().remove()`. The socket has a finite OS-level send buffer. If the debugger does not drain the queue fast enough, the agent's writes start to block on socket `send()`.
>
> The agent emits events from JVMTI callbacks, which fire on the threads that triggered the event — application threads running breakpointed code, the loading thread for a `ClassPrepareEvent`, and so on. A blocked write inside one of those callbacks blocks that thread, and through it any other thread waiting on the same JVMTI lock. In the worst case, the entire target VM stalls because the debugger forgot to read.
>
> This is why the listener is a dedicated daemon whose only job is to drain the queue. It must not block on anything that could in turn require the queue to advance — otherwise the deadlock above is the result. Every blocking operation in this codebase that the listener could hit is structured to be either bounded (the 500 ms `stop()` join) or non-blocking (`ConcurrentHashMap`, `ConcurrentLinkedDeque`, `CountDownLatch.countDown` which never blocks).

One thread, named `jdi-event-listener`, daemon, spawned by `JdiEventListener.start(vm)` (`JdiEventListener.java:200-212`). Exactly one runs at a time — `start()` calls `stop()` first (line 201) so reconnects never overlap two listeners.

The listener only drains and routes. Processing an event set can call into the target — logpoint expressions, breakpoint conditions, fast-logpoint arming, the classpath pre-warm all use `invokeMethod` — and a method invoked that way can hit a breakpoint of its own. The invocation cannot return until that second event is drained and its thread resumed. So the sets are processed on a second thread, `jdi-event-dispatcher` (a single-thread executor per connection, so they stay in arrival order and their evaluations stay serialized), while the listener keeps draining:

- a set whose thread is inside an MCP-driven evaluation (`EvaluationGuard`) is **suppressed** on the listener thread at once (`suppress`): each event is recorded as `*_SUPPRESSED` (a fast-logpoint arming hit is re-armed instead, a class prepare still promotes its deferred breakpoints) and the set is resumed, which lets the waiting invocation finish. This path never evaluates, arms or pre-warms — not even if the evaluation has ended by the time the set is handled (possible for a suspend-policy-`none` breakpoint, which does not hold the invocation up) — so the listener thread never calls into the target;
- a `VMDeath` / `VMDisconnect` set is processed on the listener thread after the dispatcher has finished the sets before it (bounded at 2 s); sets still queued after that are dropped unprocessed, so `VM_DEATH` stays the last entry (only a set already being processed — a long compile, say — can still finish after it);
- every other set goes to the dispatcher.

Whoever processes a set decides its resume, after all of its events — with one exception on the dispatcher: a set is not resumed when a later set of the same thread is already waiting. A suspended thread raises no events, so that thread was resumed meanwhile (`jdwp_resume`) and has stopped again; the older set's suspension is spent, and resuming it would release the newer stop. Because a set can wait on the dispatcher, the handlers re-apply the rule JDI applies when a set leaves its queue to one case: an event whose request was **deleted** meanwhile (a cleared logpoint, a class redefined by a fast logpoint) is dropped and does not keep its set suspended — otherwise the stale hit would be untracked and park its thread for good. An event whose tracked request was merely **disabled** meanwhile still counts as a hit — it was one when it happened.

Lifetime:

- Started by `JDIConnectionService.connect(...)` after the JDI attach succeeds (`JDIConnectionService.java:316`).
- Stopped by `cleanupSessionState` (line 429) on `disconnect`, on reconnect-to-different-target, and on `notifyVmDied`.
- Self-terminates when the loop catches `VMDisconnectedException` / `IllegalStateException` from `vm.eventQueue().remove()` (`JdiEventListener.java:333-342`).

`stop()` (`JdiEventListener.java:243-263`) interrupts and joins for up to 500 ms. Before returning it fires the VM-death latch and `breakpointTracker.fireNextEvent()` so any tool method blocked in `jdwp_resume_until_event` wakes up rather than hanging until its timeout.

## 2. The loop

`listen(...)` drains and routes (see §1); `process(EventSet)` handles one set. Skeleton:

```java
while (running) {
    EventSet eventSet = vm.eventQueue().remove();   // blocks
    route(eventSet);   // process(eventSet) here, or on the dispatcher
}

void process(EventSet eventSet) {
    boolean shouldSuspend = false;
    for (Event e : eventSet) {
        shouldSuspend |= dispatchByType(e);
    }
    if (!shouldSuspend) {
        eventSet.resume();
    }
}
```

Two details matter:

1. **`shouldSuspend` is OR-accumulated across the entire `EventSet`.** JDWP can deliver multiple events together — for example, a `BreakpointEvent` and a `StepEvent` on the same thread at the same location. If **any** handler in the set demands suspension, the listener does **not** call `resume()` and the entire set stays parked. The JDI suspension policy on each individual request decides which threads are parked; the listener's decision is purely "shall I resume them now or leave them suspended for the agent?".
2. **An exception thrown from a handler defaults to suspend.** Several handlers explicitly catch in a try/catch that records the error and returns `true` (e.g. `JdiEventListener.java:443-446` for breakpoint). The principle is fail-safe — if something is broken, the agent gets a chance to inspect; we never silently resume on an unexpected error.

### Dispatch by type

`process` routes each event by `instanceof`:

| Event type | Handler | Lines |
|---|---|---|
| `BreakpointEvent` | `handleBreakpointEvent` | 367-447 |
| `StepEvent` | `handleStepEvent` | 459-505 |
| `ExceptionEvent` | `handleExceptionEvent` | 528-604 |
| `WatchpointEvent` (access + modification) | `handleWatchpointEvent` | 682-760 |
| `ClassPrepareEvent` | `handleClassPrepareEvent` | 979-1072 |
| `VMStartEvent` | inline (line 305-308) — keep VM suspended |
| `VMDeathEvent` / `VMDisconnectEvent` | inline (line 309-317) — terminate loop |

## 3. The suspending-event contract

Every handler that decides to suspend follows the same three-step sequence, in the same order:

1. **Snapshot** — write the event's metadata into `BreakpointTracker.lastBreakpoint` (a `volatile LastBreakpoint` record). `setLastBreakpointThread(thread, breakpointId, EventKind, …)`.
2. **History** — append a `DebugEvent` to `EventHistory`. This is the line the agent's `jdwp_get_events` reads.
3. **Latch** — call `breakpointTracker.fireNextEvent()`. This wakes any tool method blocked in `jdwp_resume_until_event`.

The order is load-bearing. The latch is the synchronisation point with the dispatcher thread; if we fired the latch before writing the snapshot, the dispatcher could wake up, read a stale snapshot, and return the wrong context. So: snapshot → history → latch, always. Example at `JdiEventListener.java:496-500` (step) and `433-441` (breakpoint).

## 4. The reentrancy check — first thing every handler does

Every suspending handler starts with the same guard check:

```java
if (evaluationGuard.isEvaluating(event.thread())) {
    eventHistory.record("BREAKPOINT_SUPPRESSED", …);
    return false;  // auto-resume
}
```

(Line numbers: 377-388 for breakpoint, 469-484 for step, 530-544 for exception, 684-699 for watchpoint.)

If the firing thread is currently executing MCP-driven code via `invokeMethod`, suppress. Do **not** update `lastBreakpoint`. Do **not** fire the latch. Record the suppression event for diagnostics, and let the loop call `eventSet.resume()` on the way out. See [threading-and-safety.md](threading-and-safety.md) for the why.

This means a recursive hit during expression evaluation produces only a history entry — no perceived suspension, no state corruption of the outer breakpoint context.

## 5. The suspension-decision matrix

> **Background — JDI's three suspend policies, and what an `EventSet` is**
>
> Each JDI `EventRequest` (a `BreakpointRequest`, `ExceptionRequest`, watchpoint, etc.) carries a `SuspendPolicy` chosen at registration time. The three constants — all defined on `EventRequest` — are:
>
> - **`SUSPEND_NONE`** — the firing event does not suspend any thread. The target keeps running; the event is delivered to the debugger purely for observation. Used by tracing tools that want a log without ever stopping execution.
> - **`SUSPEND_EVENT_THREAD`** — only the thread that triggered the event is suspended. Other threads keep running. This is the IntelliJ "Suspend: Thread" mode and the default for most kinds of breakpoint in this plugin.
> - **`SUSPEND_ALL`** — every thread in the target VM is suspended. The whole world stops. Costs more in target-thread context switches but gives a consistent global snapshot.
>
> When multiple requests fire at the same location at the same time, the JVM groups their events into a single **`EventSet`**. The set's effective suspend policy is the strongest of its members: any `SUSPEND_ALL` upgrades the whole set, otherwise any `SUSPEND_EVENT_THREAD` keeps the firing thread parked, otherwise the set is `SUSPEND_NONE`. After processing the set, the debugger calls `EventSet.resume()` to undo whatever suspension the set caused — note this is *not* `vm.resume()`, it is a paired counterpart to the set's own policy. The listener loop uses exactly this pattern (`JdiEventListener.java:321-323`).

For each event type, the handler returns `true` (the listener keeps the thread suspended) or `false` (the listener resumes the set). This is the full matrix.

| Event | Branch | Handler returns | Notes |
|---|---|---|---|
| `BreakpointEvent` | Reentrancy-guarded | false | `BREAKPOINT_SUPPRESSED`, no snapshot |
| | Untracked (no matching BP in registry) | **true** | Defensive — log warning, suspend |
| | Logpoint + condition false | false | No history, no chain effect |
| | Logpoint + condition true (or no condition) | false | Evaluate, log, auto-resume, chain effect |
| | Plain BP, condition false | false | No chain effect |
| | Plain BP, condition true (or no condition) | **true** | Snapshot, history, latch, chain effect |
| | Handler exception | **true** | Fail-safe |
| `StepEvent` | Reentrancy-guarded | false | `STEP_SUPPRESSED` |
| | Normal step | **true** | Delete the one-shot `StepRequest`, snapshot, history, latch |
| `ExceptionEvent` | Reentrancy-guarded | false | `EXCEPTION_SUPPRESSED` |
| | Log-only + condition false | false | |
| | Log-only + condition true | false | Evaluate, log, auto-resume, chain effect |
| | Suspending | **true** | Snapshot, history, latch, chain effect when `firingId != null` |
| `WatchpointEvent` | Reentrancy-guarded | false | `FIELD_BREAKPOINT_SUPPRESSED` |
| | Untracked | **true** | Defensive |
| | Condition false | false | No chain effect |
| | Logpoint | false | Evaluate, log, auto-resume, chain effect |
| | Suspending | **true** | Snapshot, history, latch, chain effect |
| `ClassPrepareEvent` | always | (no `shouldSuspend`) | Promotion side effect only; CPR's own SUSPEND_EVENT_THREAD policy briefly parks the loading thread until the loop calls `eventSet.resume()` |
| `VMStartEvent` | always | **true** | Keep VM suspended so the agent can set up |
| `VMDisconnect` / `VMDeath` | always | exits loop | Sets `running=false`, fires latch, runs death hook |

A few things stand out from the matrix:

- **Untracked events suspend.** If JDWP delivers a breakpoint or watchpoint event for a request the tracker has no record of (extremely rare — the only way is concurrent registration races during cleanup), the listener errs on the side of inspection. Log a warning, suspend, let the agent figure it out. The alternative (silent resume) would mask real bugs.
- **Conditional-false skips chain effects.** A condition that evaluates to `false` is "not a meaningful hit", so it does not count as a trigger fire for the chain dependency graph. Documented inline at `JdiEventListener.java:428`.
- **Logpoints always suspend briefly.** A logpoint expression needs to call `invokeMethod`, which requires a suspended thread at an invocation event. So the JDI request uses `SUSPEND_EVENT_THREAD` and the listener evaluates, records, then returns `false` to resume. From the agent's perspective the thread never paused — but JDI did pause it long enough for the evaluation to be legal.

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
6. Return `false` → listener loop calls `eventSet.resume()`.

Symmetric paths for `EXCEPTION_LOG` (`JdiEventListener.java:617-658`) and `FIELD_LOGPOINT` (`JdiEventListener.java:799-837`). All three are subject to the same fail-safe rule: if the log expression fails, record `LOGPOINT_ERROR` / `EXCEPTION_LOG_ERROR` / `FIELD_LOGPOINT_ERROR` with the failure reason and continue.

## 8. The `EventHistory` ring buffer

`EventHistory.java` is 85 lines. Internals:

- `MAX_EVENTS = 500` (line 34) — hard-coded, deliberately not configurable.
- `ConcurrentLinkedDeque<DebugEvent>` (line 35) — producer is the listener thread, consumers are MCP tool methods via `getRecent(int)`.
- `record()` (line 41-46) appends, then evicts oldest entries until size ≤ cap. FIFO.
- `getRecent(int n)` (line 53-57) returns a **detached copy** — `new ArrayList<>(events)` then sublist. The consumer can iterate freely while the listener continues appending.
- `clear()` (line 59-61) — called from `jdwp_reset`, `jdwp_clear_events`, and `cleanupSessionState`.

A `DebugEvent` (line 76) is the record `(Instant timestamp, String type, String summary, Map<String, String> details)`. The `type` strings are the canonical vocabulary:

```
BREAKPOINT, STEP, EXCEPTION,
EXCEPTION_LOG, EXCEPTION_LOG_ERROR,
LOGPOINT, LOGPOINT_ERROR,
FIELD_ACCESS, FIELD_MODIFICATION,
FIELD_LOGPOINT, FIELD_LOGPOINT_ERROR,
BREAKPOINT_SUPPRESSED, EXCEPTION_SUPPRESSED, FIELD_BREAKPOINT_SUPPRESSED, STEP_SUPPRESSED,
CHAIN_ARMED, CHAIN_DISARMED, CHAIN_BROKEN,
BP_PROMOTION_FAILED,
VM_START, VM_DEATH
```

(Documented at `EventHistory.java:22-26` plus the strings actually emitted by `JdiEventListener.java` — `STEP_SUPPRESSED` at line 473, `BP_PROMOTION_FAILED` at line 960.)

## 9. The next-event latch — `jdwp_resume_until_event`

`jdwp_resume_until_event` is the blocking alternative to "resume + poll events + poll events". The mechanism:

1. Tool method calls `breakpointTracker.armNextEventLatch()` (`BreakpointTracker.java:1518`) — creates a fresh `CountDownLatch(1)` in a `volatile` field.
2. Tool method calls `vm.resume()`.
3. Tool method calls `latch.await(timeoutMs, MILLISECONDS)` — releases the tracker's monitor and parks.
4. When a suspending event fires, the listener's third step (after snapshot + history) is `breakpointTracker.fireNextEvent()` (`BreakpointTracker.java:1532`), which counts down the latch.
5. Tool method wakes up, reads `lastBreakpoint`, returns the formatted context.

The "arm then resume then await" sequence is order-sensitive: if the listener fires before the latch is armed, the count-down lands on a non-existent latch. The tracker's `synchronized` mutators ensure the arm-then-fire pair is atomic with respect to other tracker mutations.

Spurious wake-ups are possible (the listener fires the latch on `VM_DEATH` and on disconnect, even though no breakpoint hit). The tool method always checks `lastBreakpoint != null` and the connection state after waking; it returns a `[VM_DEATH]` envelope rather than a fake event context if the wake-up was a disconnect.

## 10. `EventKind` — what kind of event the snapshot was

`BreakpointTracker.java:1582-1589` defines:

```java
public enum EventKind { BREAKPOINT, STEP, EXCEPTION }
```

Attached to `LastBreakpoint` snapshots (`BreakpointTracker.java:1603-1614`). The canonical constructor normalises `EXCEPTION` snapshots to `id=null` so a stale BP id from a previous step does not leak into "Event fired" headers. The listener writes `EventKind.BREAKPOINT` at line 496 and `EventKind.EXCEPTION` at line 586.

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

The listener owns only one piece of mutable state: the `vmDeathHookInvoked` atomic. Everything else lives in `BreakpointTracker`, `EventHistory`, `EvaluationGuard`, or `JDIConnectionService`. Keeping the listener thin is a deliberate choice — it is the busiest piece of code in the system and the easiest place to introduce a race. New event-handling logic almost always belongs in a service the listener delegates to, not in a new field on the listener itself.
