# Threading and safety

The MCP server runs alongside a live target JVM and pokes at its internals through JDI. The safety story rests on three pillars: a deliberately small number of threads, SYNC MCP dispatch that serialises tool calls, and the `EvaluationGuard` that prevents recursive breakpoint deadlocks. This chapter walks through each.

## 1. Threads in the server JVM

There are exactly four logical actors. Every concurrency decision in the code orbits these four.

| Thread | Source | Role |
|---|---|---|
| **Spring `main`** | `JDWPMcpServerApplication.main` | Boots the context, then idles. No HTTP listener (`spring.main.web-application-type=none`). |
| **MCP STDIO dispatcher** | `MultiVersionStdioServerTransportProvider` (Spring AI MCP) | Reads JSON-RPC frames from `System.in`, dispatches to `@McpTool` methods one at a time, writes responses to `System.out`. |
| **`jdi-event-listener`** | Spawned by `JdiEventListener.start(vm)` on every `connect()` | Daemon. Drains `vm.eventQueue().remove()` and routes event sets. Exactly one at a time — `start()` calls `stop()` first (`JdiEventListener.java:201`). Never calls into the target itself. |
| **`jdi-event-dispatcher`** | Single-thread executor created by `JdiEventListener.start(vm)`, shut down by `stop()` | Daemon. Processes the routed event sets in arrival order — handlers, logpoint and condition evaluation, fast-logpoint arming, classpath pre-warm — and decides each set's resume. |

Daemons that Spring spins up for logging and scheduling exist, but the application owns no others. JDI's own socket-I/O threads run inside the `jdk.jdi` module and are invisible to us.

The point is: tool calls do not run concurrently. Breakpoint events do not run concurrently with each other (they are processed in order on the one `jdi-event-dispatcher` thread; only a set raised inside an MCP-driven evaluation is handled on the listener thread at once — see the walkthrough in §5). The only true concurrency is **between** the MCP dispatcher and the event threads — one tool method reading state that an event handler is writing. That hand-off is engineered explicitly.

## 2. SYNC MCP dispatch

`application.properties:9` sets `spring.ai.mcp.server.type=SYNC`. The Spring AI MCP framework serialises tool invocations on the STDIO dispatch thread. Implications:

- **Request order = execution order = response order.** No reordering, no interleaved responses, no out-of-order replies. The client always sees responses in the order it issued requests.
- **A long tool call blocks the next one.** `jdwp_evaluate_expression` on a cold cache (classpath discovery + ECJ compilation) can take many seconds; nothing else moves until it returns. The MCP_TOOL_TIMEOUT in `.mcp.json` is sized for this (120 s).
- **No reactor pool, no Netty.** Consistent with `web-application-type=none`. The JVM stays small.

The trade is simpler reasoning about state. We do not have to defend `BreakpointTracker.registerBreakpoint` against being called twice in parallel from two tool calls because that cannot happen.

The trade-off the listener pays: it runs on its own thread and must coordinate with the dispatcher through carefully chosen primitives.

## 3. Concurrency primitives in use

There is **no global VM lock**. What looks like one is the `synchronized` monitor on `JDIConnectionService` itself, used to serialise mutations of the singleton against tool calls that read it. Specifically, the methods `connect`, `disconnect`, `notifyVmDied`, `getVM`, `getConnectionStatus`, `ensureConnected`, `findOrForceLoadClass`, and `getObjectFields` are `synchronized`. JDI itself is thread-safe — the lock exists so that an auto-reconnect from one tool call cannot race a `connect` from another.

Beyond that, the codebase uses targeted lock-free primitives where the hot path warrants them:

- **`ConcurrentHashMap`** — `JDIConnectionService.objectCache` (`JDIConnectionService.java:81`), every map on `BreakpointTracker` (lines 49, 58, 62, 66, 70, 74, 82, 88, 92, 98, 104, 110, 115, 160, 167, 176), `EvaluationGuard.depthByThreadId` (`EvaluationGuard.java:51`).
- **`ConcurrentLinkedDeque`** — `EventHistory.events` (`EventHistory.java:35`). Producer is the listener thread (calls `record`), consumers are MCP tool methods (call `getRecent`). Non-blocking on both sides. `getRecent` returns a detached copy so iteration is safe.
- **`synchronized` mutators on `BreakpointTracker`** — pair operations such as register-then-link or arm-then-fire must be atomic so the listener cannot read a half-written state. See `registerPendingBreakpoint` (`BreakpointTracker.java:370`), `tryPromotePending` (line 843), `armNextEventLatch` (line 1518), `fireNextEvent` (line 1532), `reset` (line 1569).
- **`volatile`** — `JDIConnectionService.vm`, `cachedClasspath`, `discoveredJdkPath`, `targetMajorVersion`, `lastConnectAttempt`, `lastConnectError`; `BreakpointTracker.lastBreakpoint`, `nextEventLatch`, `pendingFire`; `JdiEventListener.listenerThread`, `running`, `vmDeathHook`. Each marks a single reference that one thread writes and others read without locking.
- **`AtomicInteger`** for synthetic breakpoint IDs (`BreakpointTracker.idCounter:45`). **`AtomicBoolean`** for the one-shot VM-death-hook gate (`JdiEventListener.vmDeathHookInvoked:87`).
- **`CountDownLatch`** for `jdwp_resume_until_event` (`BreakpointTracker.nextEventLatch:139`). The tool method `await`s; the listener `countDown`s when an event fires.

One ad-hoc lock worth calling out: `synchronized(erm)` in `JDWPTools.java:1771` — used to make the delete-then-create `StepRequest` pair atomic, because JDI allows only one `StepRequest` per thread at a time.

## 4. `INVOKE_SINGLE_THREADED`

> **Background — two ways a thread can be "suspended" in JDI**
>
> JDI tracks a *suspend count* per thread. A thread is considered suspended whenever its count is greater than zero; resume decrements it; a thread resumes "for real" only when the count reaches zero. Two distinct mechanisms increment the count:
>
> - **Event-driven suspension.** When a `BreakpointEvent`, `StepEvent`, `ExceptionEvent`, `MethodEntryEvent`, etc. fires, the JVM suspends the firing thread (or every thread, depending on the request's `SuspendPolicy`) *at an invocation event*. The thread is parked at a precise bytecode boundary, with a valid stack frame the debugger can inspect.
> - **Manual suspension.** `ThreadReference.suspend()` lets the debugger pause a thread on demand, wherever it happens to be — possibly mid-method, possibly inside the JVM's own runtime code, possibly between bytecodes. The thread's bookkeeping is in a less well-defined state.
>
> Only the first kind is a valid context for `invokeMethod`. JDI enforces this — calling `invokeMethod` on a manually-suspended thread throws `IncompatibleThreadStateException`. The reason is mechanical: `invokeMethod` works by *borrowing* the suspended thread to run new code on top of its existing stack. To do that safely, the runtime needs the thread to be at a known good point — exactly what the event-suspended state guarantees.

Every JDI method invocation in this codebase uses `ObjectReference.INVOKE_SINGLE_THREADED` (or its static counterpart). The flag tells JDI: do **not** resume other threads to satisfy this call. The target thread must already be suspended at a method-invocation event (breakpoint, step, exception, or class-prepare). JDI itself enforces this — invoking on an unsuspended thread, or one suspended via `ThreadReference.suspend()` instead of at an event, throws `IncompatibleThreadStateException`.

Without `INVOKE_SINGLE_THREADED`, JDI would resume **all** threads in the target VM long enough to execute the invocation. That would defeat the entire suspend-and-inspect model — the other threads would mutate state mid-inspection, and any expression that touches shared data would race with the application.

> **Why does the default behavior even resume all threads?** Without the single-threaded flag, JDI worries about deadlocks the *other* way around: the invoked code might try to acquire a lock that another thread holds, or wait on a queue that no other thread is feeding. If all other threads stay suspended, that wait blocks forever. The default behaviour trades the inspection-safety guarantee for liveness, on the assumption that the debugger user knows what they are doing.
>
> The plugin makes the opposite trade: keep the target frozen during evaluation so inspected state stays consistent, and accept that an expression like `someBlockingQueue.take()` will hang. That is a price worth paying for an agent that needs reproducible reads.

The check `JDIConnectionService.isUsableForInvoke` (`JDIConnectionService.java:226-232`) is what gates this: `t.isSuspended() && t.frameCount() > 0`. Tool methods that need to invoke fail fast with a clear message rather than letting JDI throw.

Use sites:

- `JdiExpressionEvaluator.findClassLoader` — `getSystemClassLoader()` fallback (`evaluation/JdiExpressionEvaluator.java:473`).
- `RemoteCodeExecutor` — the wrapper method invoke (`evaluation/RemoteCodeExecutor.java:85`), `ClassLoader.defineClass` (line 155), `Class.forName` (line 244).
- `ClasspathDiscoverer` — `System.getProperty`, classloader walk (`evaluation/ClasspathDiscoverer.java:130, 168, 253, 291, 337`).
- `JDIConnectionService.findOrForceLoadClass` — `Class.forName` (`JDIConnectionService.java:1069`).
- `JDWPTools.jdwp_to_string` — `Object.toString` (`JDWPTools.java:709`).

Every one of these sites can produce a breakpoint event on the same thread, which leads to the next section.

## 5. The `EvaluationGuard` — recursive-breakpoint protection

This is the most subtle correctness mechanism in the codebase. Read `EvaluationGuard.java` end-to-end (98 lines, well-commented) before changing anything that crosses a JDI invoke boundary.

> **Background — the classic cross-thread JDWP deadlock**
>
> This is a well-known failure mode of any debugger that combines breakpoints with expression evaluation. The pattern is generic:
>
> 1. Thread T is suspended at breakpoint B.
> 2. The debugger evaluates an expression on T, which under the hood calls JDWP `ObjectReference.InvokeMethod` (or its static / interface variants). The debugger now waits for that command's reply on the JDWP socket.
> 3. The invoked code, running on T inside the target VM, re-enters the line where B is installed.
> 4. The VM fires another `BreakpointEvent` for B on thread T and delivers it via the JDWP event channel.
> 5. The debugger's event consumer sees the new breakpoint hit and, by default, suspends T to let the user inspect it.
> 6. T is now suspended again, but the outer `InvokeMethod` is still waiting for T to *finish* the invocation. It cannot — T is parked. The debugger waits forever (or until the JDWP request times out, which in some implementations is "never").
>
> IntelliJ, Eclipse, and JDB all hit this in different forms; each has worked around it differently. The `EvaluationGuard` is this codebase's solution.

### The problem

The MCP server runs an expression by calling `invokeMethod` on a suspended target-VM thread T. While the expression runs, T executes user bytecode that may re-enter the line that the agent originally breakpointed. JDWP delivers the resulting `BreakpointEvent` to the server's event queue. The listener, if it follows the normal path, would call `setLastBreakpointThread(T)` and not resume T — but T is **exactly the thread** the outer `invokeMethod` is waiting on. Cross-thread deadlock: the MCP server hangs until the JDI invocation times out.

### The mechanism

The guard is a per-thread, counted, lock-free map:

```java
private final ConcurrentMap<Long, Integer> depthByThreadId = new ConcurrentHashMap<>();
```

It exposes three operations (`EvaluationGuard.java:63-95`):

- `enter(long threadUniqueId)` — increments the depth for this thread.
- `exit(long threadUniqueId)` — decrements; removes the entry at depth 0.
- `isEvaluating(ThreadReference thread)` — returns `containsKey(thread.uniqueID())`.

Every MCP-driven `invokeMethod` path is wrapped in a try/finally:

```java
long guardedThreadId = thread.uniqueID();        // capture ONCE
evaluationGuard.enter(guardedThreadId);
try {
    // ... compile, defineClass, invokeMethod, ...
} finally {
    evaluationGuard.exit(guardedThreadId);
}
```

The listener, on every suspending event, checks `isEvaluating(event.thread())` first. If the thread is in the map, the event is **suppressed**: a `BREAKPOINT_SUPPRESSED` / `STEP_SUPPRESSED` / `EXCEPTION_SUPPRESSED` / `FIELD_BREAKPOINT_SUPPRESSED` entry is recorded in the event history, and the handler returns `false` so the listener loop's `eventSet.resume()` runs. T resumes, the in-VM call completes, and the outer `invokeMethod` returns.

Suppression sites:

- Breakpoint: `JdiEventListener.java:377-388`
- Step: `JdiEventListener.java:469-484`
- Exception: `JdiEventListener.java:530-544`
- Watchpoint: `JdiEventListener.java:684-699`

`enter` / `exit` sites:

- `JdiExpressionEvaluator.evaluate(...)` — `evaluation/JdiExpressionEvaluator.java:530-531` and `629`.
- `JdiExpressionEvaluator.configureCompilerClasspath(...)` — same file, lines 652-653 and 684.
- `JDIConnectionService.findOrForceLoadClass(...)` — `JDIConnectionService.java:1066-1067` and 1071.
- `JDWPTools.jdwp_to_string` — `JDWPTools.java:705-706` and 711.

### Why counted, not boolean

Layered call sites stack. `configureCompilerClasspath` calls into `discoverClasspath`, which itself issues `invokeMethod` calls on the same thread. The classpath-discovery path enters the guard at line 652-653; the inner `evaluate` enters it again at line 530-531; both `exit` symmetrically in their `finally` blocks. A boolean flag would clear on the first `exit` and leave the rest of the outer evaluation unprotected.

### Why `long` for mutations, `ThreadReference` for the read

The `uniqueID()` call can throw `ObjectCollectedException` if the underlying thread has died. The mutation path lives across an `invokeMethod` round-trip where exactly that can happen — if the target thread crashes during the in-VM call, the `finally` block must still clean up the guard entry. Re-querying `thread.uniqueID()` in `exit` would mean the cleanup itself throws, leaving the depth map dangling forever.

So we capture the `long` once, at the top of the call, when the thread is provably alive (we just used it to call `invokeMethod`), and pass the value through to the `finally`. The hot-path read in the listener takes a `ThreadReference` because JDI guarantees that the thread carried by a live event is itself live at the moment of delivery (documented in `EvaluationGuard.java:32-36`).

### A recursive-breakpoint walkthrough

```
1. Agent: jdwp_evaluate_expression(threadId=T, expression="this.compute(3)")
   MCP dispatch thread enters JdiExpressionEvaluator.evaluate.

2. evaluator captures guardedThreadId = frame.thread().uniqueID()
   evaluator calls evaluationGuard.enter(guardedThreadId)
   depthByThreadId[T] = 1

3. evaluator compiles wrapper, calls RemoteCodeExecutor.execute
   → defineClass on the target's classloader (one round-trip per byte!)
   → invokeMethod(thread=T, …, INVOKE_SINGLE_THREADED)
   MCP dispatch thread BLOCKS waiting for JDWP reply.

4. Target VM: T runs the wrapper's evaluate() method, which calls
   this.compute(3). compute() re-enters line 22 where the user's
   original breakpoint lives. JDWP fires a BreakpointEvent on T.

5. jdi-event-listener: handleBreakpointEvent picks it up.
   First action (line 377-388): isEvaluating(event.thread()) → true.
   Record BREAKPOINT_SUPPRESSED. Return false.

6. Listener loop sees no event in the set demanded suspension.
   eventSet.resume() runs. T resumes inside the in-VM call.

7. compute(3) completes. Wrapper's evaluate() returns. JDWP reply
   sails back. invokeMethod returns to RemoteCodeExecutor.execute.
   evaluator's finally runs: evaluationGuard.exit(guardedThreadId).
   depthByThreadId[T] removed.

8. MCP tool returns the result string to the agent.
```

No lock is held across `invokeMethod` (the guard is lock-free; the `JDIConnectionService` monitor is released long before this point). T is never re-suspended by the listener while the server is waiting on it. The same mechanism covers nested `Class.forName`, `toString`, `getSystemClassLoader`, and step events fired during evaluation.

The event pipeline drives evaluations of its own — logpoint expressions, breakpoint conditions, fast-logpoint arming, the classpath pre-warm. Those run on the `jdi-event-dispatcher` thread, so step 3 blocks the dispatcher, not the listener. Step 5 still needs a free listener: it takes T's new event set off the queue, sees that T is evaluating, and processes that set itself instead of queueing it behind the dispatcher, which is the thread waiting for T. (When these evaluations ran on the listener thread, step 5 never happened and the debugger hung.)

### Why does `exit` survive a missing entry?

`EvaluationGuard.java:74-76`:

```java
public void exit(long threadUniqueId) {
    depthByThreadId.compute(threadUniqueId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
}
```

A `null` entry produces another `null` — a no-op. The defensive read is there because the caller might enter a finally block from a path that never made it to `enter` (e.g. an exception before the `enter` line). Half-finished cleanup must never throw; the alternative would be wrapping every `exit` in its own try/catch at the call site.

## 6. Transport-loss handling — `VMDisconnectedException` and friends

Everything above assumes the JDWP socket is alive. When it dies mid-call, the codebase has a centralised classifier:

- **Listener thread** (`JdiEventListener.java:309-339`) — catches `VMDisconnectedException | IllegalStateException` from `vm.eventQueue().remove()`. Records `VM_DEATH`, clears `running`, fires the next-event latch, runs the VM-death hook. Same path for in-loop `VMDeathEvent` / `VMDisconnectEvent` and `InterruptedException` from `stop()`. The hook is single-shot via the `AtomicBoolean` CAS.
- **Tool layer** (`JDWPTools.java`) — `isVmGone` (lines 1086-1099) and `isTransportFailureFrame` (lines 1105-1117) walk the cause chain (bounded by `MAX_CAUSE_DEPTH = 8`) checking for `VMDisconnectedException`, `SocketException`, `EOFException`, plus message substrings like "Connection refused", "Broken pipe", "handshake failed" (full list at lines 1021-1030). When detected, the tool returns a `[VM_DEATH]` or `[VM_GONE]` envelope instead of an opaque stack trace. See [diagnostics.md](diagnostics.md) for the exact envelope strings.

`ObjectCollectedException` (the JDI signal that a target-VM object has been GC'd out from under us) is caught at:

- `JDWPTools.jdwp_mark_instance` (`JDWPTools.java:2769`) — refuses to mark a corpse.
- `MarkedInstanceRegistry.mark` (`marks/MarkedInstanceRegistry.java:66, 74`) — same.
- `EvaluationGuard` documents at `EvaluationGuard.java:22, 82` why the hot read cannot throw it.

The principle: every JDI call that crosses the wire can fail, and every failure has a single classifier above it. Code that calls JDI directly is allowed to throw; code that calls JDI on behalf of an MCP tool routes through the classifier.

## 7. Things that look concurrent but aren't

Reading the code, you may wonder about some apparent races. They are usually one of these patterns:

- **`volatile` reference, immutable target.** `BreakpointTracker.lastBreakpoint` is a `volatile LastBreakpoint` record. The record is immutable. The listener writes a new instance; the dispatcher reads the field once. There is no torn read — the write is atomic, the value is frozen.
- **`synchronized` arm/fire pair, lock-free read.** `armNextEventLatch` and `fireNextEvent` are both `synchronized` on `BreakpointTracker`. The tool method "arm then wait" is also `synchronized` for the arm half. The listener calls `fireNextEvent` (synchronized) but the latch await itself is not — `CountDownLatch.await()` releases the monitor implicitly because we await on the latch, not on the tracker.
- **`ConcurrentHashMap` with `merge` / `compute`.** `EvaluationGuard.enter` uses `merge(key, 1, Integer::sum)`. That is one atomic operation, not a read-then-write. Same for `exit`'s `compute`. Multiple entries from the same thread (impossible given single-threaded SYNC dispatch) would still be safe.

If you find yourself adding a lock to "fix" something here, first check whether the existing primitive is already doing what you want.

## 8. Don't hold locks across JDI invocations

This is the cardinal rule. `invokeMethod` can take seconds. A `synchronized` block that contains `invokeMethod` will block every other tool call for that long. Worse, if the same path can re-enter through the listener (it can, via the guard), holding a monitor across the boundary is a deadlock waiting to happen.

`EvaluationGuard.java:40` documents this directly: "No locks are held across `invokeMethod` calls." The `JDIConnectionService` monitor is released by the time any tool method calls `evaluate` or `to_string`. The guard does the job a lock would do, without the deadlock potential.

When adding new code that crosses the JDI boundary, audit the call site for monitors held above. If in doubt: capture the values you need, drop the lock, then make the JDI call.
