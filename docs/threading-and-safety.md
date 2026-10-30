# Threading and safety

The MCP server runs alongside a live target JVM and pokes at its internals through JDI. The safety story rests on four pillars: a small, named set of threads; SYNC MCP dispatch that serialises tool calls; the `SuspensionSession`, which knows who owns every suspension the debugger causes ([event-pipeline.md](event-pipeline.md) § 14); and the `EvaluationGuard` leases that keep an invocation from deadlocking on a breakpoint or spending a suspension it does not own. This chapter walks through each.

## 1. Threads in the server JVM

Every concurrency decision in the code orbits these actors.

| Thread | Source | Role |
|---|---|---|
| **Spring `main`** | `JDWPMcpServerApplication.main` | Boots the context, then idles. No HTTP listener (`spring.main.web-application-type=none`). |
| **MCP STDIO worker** | `MultiVersionStdioServerTransportProvider` (Spring AI MCP) | Reads JSON-RPC frames from `System.in`, dispatches to `@McpTool` methods one at a time, writes responses to `System.out`. |
| **`jdi-event-listener`** | Spawned by `JdiEventListener.start(vm)` on every `connect()` | Daemon. Drains `vm.eventQueue().remove()` and routes event sets. Exactly one at a time — `start()` calls `stop()` first. Never calls into the target itself. |
| **`jdi-event-dispatcher`** | Single-thread executor created by `JdiEventListener.start(vm)`, shut down by `stop()` | Daemon. Processes the routed event sets in arrival order — handlers, logpoint and condition evaluation, fast-logpoint arming, classpath pre-warm — and ends each with `SuspensionSession.finish`: publish a stop, or release the set. |
| **`jdi-suspension-watchdog`** | `SuspensionWatchdog.start(session)`, called by `JdiEventListener.start(vm)` | Daemon, one per session. Every 500 ms samples the dispatcher's invocations and frees the owner of a monitor a stuck one waits for ([event-pipeline.md](event-pipeline.md) § 14). Stops once its session is closed. |
| **`jdi-invoker-N`** | `InvocationRunner.newPool()`, a cached pool `JDWPTools` creates | Daemon, created on demand. Runs a tool's invoking work — an evaluation, `toString()`, force-loading a class — while the MCP worker waits at most 20 s (§ 5). A hung job pins its thread until the connection ends. |

The server also starts narrower daemon helpers — `jdi-health-monitor`, `jdi-health-probe`, `jdi-liveness-probe`, the `jdwp-probe` discovery pool, `fast-logpoint-teardown`, `fast-logpoint-discovery` — and Spring has its own logging and scheduling threads. JDI's own socket-I/O threads run inside the `jdk.jdi` module and are invisible to us.

The point is: tool calls do not run concurrently with each other. Event sets do not run concurrently with each other either — they are processed in order on the one `jdi-event-dispatcher` thread; only a death set and a set raised inside an MCP-driven evaluation are handled on the listener thread (see the walkthrough in § 5). The true concurrency is between the MCP worker (with the invoker job it waits for), the listener (routing), the dispatcher and the watchdog, and they meet in the `SuspensionSession` monitor. One more case: a tool that answered `[STILL_RUNNING]` leaves its job running on its invoker thread while the next tool call runs. A second job on the same target thread is refused, and the late result goes only to the history of the session the job started in.

## 2. SYNC MCP dispatch

`application.properties:9` sets `spring.ai.mcp.server.type=SYNC`. The Spring AI MCP framework serialises tool invocations on the STDIO dispatch thread. Implications:

- **Request order = execution order = response order.** No reordering, no interleaved responses, no out-of-order replies. The client always sees responses in the order it issued requests.
- **A long tool call blocks the next one.** `jdwp_evaluate_expression` on a cold cache (classpath discovery + ECJ compilation) can take many seconds; nothing else moves until it returns. The MCP_TOOL_TIMEOUT in the plugin's MCP server entry (`.claude-plugin/plugin.json`) is sized for this (120 s). Tools that invoke in the target wait at most 20 s for it (§ 5), so a blocked invocation cannot take the user's tools away.
- **No reactor pool, no Netty.** Consistent with `web-application-type=none`. The JVM stays small.

The trade is simpler reasoning about state. We do not have to defend `BreakpointTracker.registerBreakpoint` against being called twice in parallel from two tool calls because that cannot happen.

The trade-off the listener pays: it runs on its own thread and must coordinate with the dispatcher through carefully chosen primitives.

## 3. Concurrency primitives in use

There is **no global VM lock**. Two monitors matter:

- **`JDIConnectionService`** — its `synchronized` methods (`connect`, `disconnect`, `notifyVmDied`, `reconnectPreservingSpecs`, `getConnectionStatus`, `ensureConnected`, `getObjectFields`) and the monitor sections of `getVM` and `findOrForceLoadClass` serialise mutations of the singleton against tool calls that read it; the latter two leave the monitor before they call into the target. JDI itself is thread-safe — the lock exists so that an auto-reconnect from one tool call cannot race a `connect` from another.
- **`SuspensionSession`** — one monitor per connection guards all suspension state: contexts, manual holds, leases, reservations, pending steps, the current stop. It is held across short JDWP commands (suspend, resume, a step's request creation) but never across event processing, an invocation or a wait. `nextStop` and `acknowledgeStart` wait on the monitor's own condition, which releases it; `finish` (on publication, and for a VM-start set), `reset` and `close` call `notifyAll`.

Beyond those, the codebase uses targeted lock-free primitives where the hot path warrants them:

- **`ConcurrentHashMap`** — `JDIConnectionService.objectCache`, every map on `BreakpointTracker`, `EvaluationGuard.holds` and `depthByThreadId`, `InvocationRunner.running` (the job per target thread).
- **`ConcurrentLinkedDeque`** — `EventHistory.events`. Producers are the listener, the session (on the dispatcher and the watchdog), invoker threads and tool methods; consumers are MCP tool methods (`getRecent`). Non-blocking on both sides. `getRecent` returns a detached copy so iteration is safe.
- **`synchronized` mutators on `BreakpointTracker`** — operations that mutate several maps at once must be atomic so the dispatcher cannot read a half-written state: `registerPendingBreakpoint`, promotion, `clearAll`, `reset`; `tryPromotePending` re-acquires the monitor for its recheck-and-mutate section. The next-event latch pair (`armNextEventLatch` / `fireNextEvent`) is still there, but no production tool waits on it any more — `jdwp_resume_until_event` waits on the session (§ 7).
- **`volatile`** — `JDIConnectionService.cachedClasspath`, `discoveredJdkPath`, `targetMajorVersion`, `lastConnectAttempt`, `lastConnectError`; `BreakpointTracker.lastBreakpoint`, `suspensionSession`; `JdiEventListener.listenerThread`, `running`, `dispatcher`, `session`, `watchdog`, `vmDeathHook`; `EvaluationGuard.session`. Each marks a single reference that one thread writes and others read without locking.
- **Thread-local** — `SuspensionSession`'s processing marker: on the thread that processes a set, the tracker's snapshot becomes the set's stop candidate and `EventHistory.record` buffers into the set ([event-pipeline.md](event-pipeline.md) § 3).
- **`AtomicInteger`** for synthetic breakpoint IDs (`BreakpointTracker.idCounter`) and for an invoker job's state, whose compare-and-set settles the job's completion against its caller's timeout. **`AtomicBoolean`** for the one-shot VM-death-hook gate (`JdiEventListener.vmDeathHookInvoked`).

One ad-hoc lock worth calling out: `synchronized(erm)` in the step installer (`JDWPTools.doStep`) — used to make the delete-then-create `StepRequest` pair atomic, because JDI allows only one `StepRequest` per thread at a time. It runs inside `SuspensionSession.step`, so the session monitor is taken first (§ 8).

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
> The plugin makes the opposite trade: keep the target frozen during evaluation so inspected state stays consistent, and accept that an expression like `someBlockingQueue.take()` will hang. That is a price worth paying for an agent that needs reproducible reads. A hang no longer takes the user's tools with it: a tool's invocation is bounded at 20 s (§ 5), and a stuck dispatcher invocation is watched ([event-pipeline.md](event-pipeline.md) § 14).

A single-threaded invocation borrows exactly one of the thread's suspensions: the JDWP agent resumes the thread once to run it and suspends it once when it completes. The thread runs only if that count was its last one.

Before an invocation, cheap checks reject an obviously unusable thread: the evaluation tools refuse a thread that is not suspended or is blocked on a monitor or in `Object.wait()` (`checkSafeToInvoke`), and `JDIConnectionService.isUsableForInvoke` (suspended, with frames) picks a thread for force-loading. The deciding check is the session's admission when the lease is taken (§ 5): the thread must be stopped at an event of its own that a tool has presented. A bystander of another thread's stop-all, a `jdwp_suspend_thread` hold or a VM-start stop is refused with a reason instead of an `IncompatibleThreadStateException`.

Use sites:

- `JdiExpressionEvaluator.systemClassLoader` — the `getSystemClassLoader()` fallback.
- `RemoteCodeExecutor` — the wrapper method invoke, `ClassLoader.defineClass`, `Class.forName`.
- `ClasspathDiscoverer` and `JdkDiscoveryService` — `System.getProperty`, the classloader walk.
- `JDIConnectionService.findOrForceLoadClass` — `Class.forName`.
- `JDWPTools.jdwp_to_string` — `Object.toString`.
- `FastLogpointService` — reading the class file (`getResourceAsStream`, `read`, `close`).

Every one of these sites can produce a breakpoint event on the same thread, which leads to the next section.

## 5. The `EvaluationGuard` — leases on invoking threads

This is the most subtle correctness mechanism in the codebase. Read `EvaluationGuard.java` and the lease part of `SuspensionSession.java` before changing anything that crosses a JDI invoke boundary.

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

The MCP server runs an expression by calling `invokeMethod` on a suspended target-VM thread T. While the expression runs, T executes user bytecode that may re-enter the line that the agent originally breakpointed. JDWP delivers the resulting `BreakpointEvent` to the server's event queue. If the pipeline followed its normal path, it would publish a stop and keep T suspended — but T is **exactly the thread** the outer `invokeMethod` is waiting on. Cross-thread deadlock.

The borrowed count (§ 4) adds two more problems. If another thread's stop-all also holds T, the borrowed count is not T's last one and the invocation never runs. And if a tool releases the stop the invocation borrows from while it runs, that release must not be lost or spend the wrong count.

### The mechanism

Every MCP-driven `invokeMethod` path runs inside a lease:

```java
try (EvaluationGuard.Lease lease = evaluationGuard.lease(thread)) {
    // ... defineClass, invokeMethod, ...
}
```

`EvaluationGuard` keeps one **hold** per target thread, keyed by `uniqueID()`: the Java thread running the invocation chain, and the session that admitted it.

- **Same chain re-enters.** `lease(T)` from the Java thread that already holds T returns a nested lease that ends nothing — classpath discovery inside an evaluation, say.
- **Other chains are refused.** `lease(T)` from any other Java thread throws `EvaluationRefusedException` ("an evaluation on thread T is already running"). The JDWP agent runs one invocation per thread, and a second chain would spend the suspension the first one borrows. The hold is taken with `putIfAbsent`, so two racing chains cannot both win.
- **Admission.** The outermost lease is also taken in the bound session: `SuspensionSession.lease(T)` admits the invocation or refuses it with a reason, checks T's suspend count against the holds it knows, and takes T out of every other stop that suspends it ([event-pipeline.md](event-pipeline.md) § 14, "Leases and admission"). A refusal removes the hold again.
- **End.** Closing the outermost lease calls `SuspensionSession.unlease(T)` first — T's own events stay suppressed meanwhile — and then removes the hold by identity, so a hold of an earlier connection never removes one of the current connection.
- **Bind.** `bind(session)`, called by `JdiEventListener.start`, binds the new connection's session and drops every hold of the previous one: an old lease neither suppresses nor admits anything on the new connection, and ends only in its own session.
- **Read.** `isEvaluating(ThreadReference)` is true while T has a hold. `enter(long)` / `exit(long)` remain as a depth-only marker without a session or ownership; no production path uses them.

The listener checks `isEvaluating` before it routes a set (`fromEvaluatingThread`). A set raised by the invoked code is **suppressed** on the listener thread at once: routed as a context, recorded as `BREAKPOINT_SUPPRESSED` / `STEP_SUPPRESSED` / `EXCEPTION_SUPPRESSED` / `FIELD_BREAKPOINT_SUPPRESSED`, and released. T resumes inside the in-VM call, the call completes, and the outer `invokeMethod` returns. The handlers repeat the check for a set routed before the evaluation began ([event-pipeline.md](event-pipeline.md) § 4).

Lease sites:

- `JdiExpressionEvaluator` — around the remote execution (with the `getSystemClassLoader` fallback), and around classpath discovery in `configureCompilerClasspath`.
- `FastLogpointService` — the arming, once around reading the class file and once around defining the probe.
- `JDIConnectionService.findOrForceLoadClass` — around `Class.forName`.
- `JDWPTools.jdwp_to_string` — around `toString()`.

### Why a lease spans invocations only

JDI invalidates every `StackFrame` of a thread whenever the debugger resumes it, however many suspensions remain, and an invocation does the same. Taking T out of another stop at lease entry is such a resume, and so is a stop-all routed during the lease. A lease that spanned frame reads would let either invalidate frames the evaluator was still reading. So the evaluator reads the frame first — its locals, `this`, the frame's class loader — and leases T only around its invocations; the arming takes one lease per invocation group, not one across the whole arming. A resume elsewhere can still invalidate the frame while the evaluator reads it; it then reads the same frame afresh (T's one frame at the same location) and repeats the reads, at most three times.

### Why `long` for the key, `ThreadReference` for the read

The `uniqueID()` call can throw `ObjectCollectedException` if the underlying thread has died. The lease lives across an `invokeMethod` round-trip where exactly that can happen — if the target thread crashes during the in-VM call, closing the lease must still clean up. Re-querying `thread.uniqueID()` in `close` would mean the cleanup itself throws, leaving the hold dangling.

So the lease captures the `long` once, when it is taken and the thread is provably alive, and closes with that value. The hot-path read takes a `ThreadReference` because JDI guarantees that the thread carried by a live event is itself live at the moment of delivery.

### A recursive-breakpoint walkthrough

```
1. Agent: jdwp_evaluate_expression(threadId=T, expression="this.compute(3)")
   The MCP worker hands the job to InvocationRunner and waits ≤ 20 s.

2. On jdi-invoker-N: configureCompilerClasspath (its own lease), the
   frame read (locals, this, class loader), the wrapper compile.

3. evaluationGuard.lease(T): hold {chain = jdi-invoker-N, session}.
   SuspensionSession.lease(T) admits — T is stopped at its own presented
   breakpoint B — and takes T out of any other stop covering it.

4. RemoteCodeExecutor: defineClass, then
   invokeMethod(thread=T, …, INVOKE_SINGLE_THREADED).
   The invoker thread BLOCKS waiting for the JDWP reply.

5. Target VM: T runs the wrapper's evaluate() method, which calls
   this.compute(3). compute() re-enters line 22 where B lives.
   JDWP fires a BreakpointEvent on T.

6. jdi-event-listener: fromEvaluatingThread → true (T has a hold).
   suppress: route a context, record BREAKPOINT_SUPPRESSED, finish —
   no candidate, so the set is released. T resumes inside the call.

7. compute(3) completes. The wrapper's evaluate() returns, the JDWP reply
   sails back, invokeMethod returns. The lease closes: unlease(T) puts T
   back into the stops it was taken out of, then the hold is removed.

8. The job completes; the MCP worker returns the result string
   (past 20 s: [STILL_RUNNING] now, EVALUATION_COMPLETED_LATE later).
```

No lock is held across `invokeMethod`: the guard's maps are lock-free, and the session monitor is held only inside `lease` and `unlease`, not across the call. T is never re-suspended by the pipeline while the server is waiting on it. The same mechanism covers nested `Class.forName`, `toString`, `getSystemClassLoader`, and step events fired during evaluation.

The event pipeline drives evaluations of its own — logpoint expressions, breakpoint conditions, fast-logpoint arming, the classpath pre-warm. Those run on the `jdi-event-dispatcher` thread, so step 4 blocks the dispatcher, not the listener. Step 6 still needs a free listener: it takes T's new event set off the queue, sees that T is evaluating, and handles that set itself instead of queueing it behind the dispatcher, which is the thread waiting for T. A dispatcher invocation borrows the count of the set being processed; it does not go through `InvocationRunner`, and the `SuspensionWatchdog` watches it instead ([event-pipeline.md](event-pipeline.md) § 14).

### Bounded waits — `InvocationRunner`

Invoked code can block — on a lock a parked thread holds, on a `join`. MCP tool calls are serialized, so a tool blocked in `invokeMethod` would leave the user no tool to release that thread with. So the tools that invoke — `jdwp_evaluate_expression`, `jdwp_assert_expression`, `jdwp_evaluate_watchers`, `jdwp_to_string`, and the six paths that force-load a class (`JDWPTools.forceLoad`) — hand their whole invoking work (classpath discovery, the frame read, the evaluation) to `InvocationRunner.run(thread, what, work)`:

- The job runs on the `jdi-invoker-N` pool; the MCP worker waits at most `InvocationRunner.TIMEOUT_MS` (20 s, well under the 120 s MCP tool timeout).
- A target thread with a job still running refuses another one at once, before anything is read from it (`[STILL_RUNNING] … wait for EVALUATION_COMPLETED_LATE`). A job of an ended connection refuses nothing: its thread ID may name a thread of the next VM.
- A job runs only at the stop it was started at. `run` records `holdsOn(T)` and runs the work under `SuspensionSession.admittedUnder`; every lease of T inside is refused once none of those holds is left — the user released the stop after `[STILL_RUNNING]`, and T may have run on and stopped again, even at the same line, which is another activation.
- The caller's timeout and the job's completion are settled by one compare-and-set on the job's state, so a job yields exactly one of: its result, or `[STILL_RUNNING]` now and `EVALUATION_COMPLETED_LATE` in the history later. A job the caller gave up on before it started never runs.
- The `[STILL_RUNNING]` reply names what blocks the thread when JDI can tell: its status, and for a thread blocked on a monitor, the monitor's owner — asked for with a balanced suspend → `currentContendedMonitor().owningThread()` → resume, on the pool, waited for at most 2 s (the probe still resumes what it suspended when the reply gives up on it). It points at the recovery tools: `jdwp_resume_thread` for a parked thread the job waits for, `jdwp_resume` for the user's stops (the evaluating thread stays suspended until the evaluation ends), `jdwp_reconnect` for everything.
- The job keeps its lease until it completes. A late result is recorded only in the session the job started in, never in a later connection's. A hung job pins its pool thread until the connection ends.

### Frame tools during an evaluation

`jdwp_get_stack`, `jdwp_get_locals`, `jdwp_set_local` and `jdwp_get_breakpoint_context` run their work through `JDWPTools.withFreshFrames(thread, …)`:

- **Refusal.** A thread an evaluation runs on is refused ("an evaluation is running on thread T — its frames are, or are about to be, the invoked code's"), before the suspended check: during an invocation the thread runs, and its frames belong to the invoked code. A job that outlived its tool's wait counts for its whole life (`InvocationRunner.isRunning`), also while it still compiles and holds no lease.
- **Reservation.** For the whole call the thread is reserved in the session (`reserve` / `unreserve`); the watchdog never releases a reserved thread.
- **Re-read.** A resume elsewhere — another stop-all's release, the fast-logpoint arming's VM-wide suspend — invalidates the frames the tool read, though the thread stays suspended. On `InvalidStackFrameException` the tool reads fresh frames and rebuilds its whole reply, at most three times (`FRAME_ATTEMPTS`) — but only while a hold the session knows lasted across the attempt. `SuspensionSession.holdsOn(T)` (the ids of the contexts holding T, a lease's exemption aside, plus the manual hold) is taken before the attempt and after its failure, and the two must intersect. Context ids are never reused, and a manual hold cannot be dropped and re-taken during a serialized tool call, so a common hold proves T never ran past its stop and the fresh frames are the same activations. Otherwise T may have run on and stopped elsewhere; the tool answers that it was resumed meanwhile, and `jdwp_set_local` adds that nothing was written.
- **Writes.** `jdwp_set_local` is retried only on that exception, which JDI raises before sending the write or when the target rejects the frame — either way the write did not land.

Without a session (no connection yet), nothing is reserved or retried.

## 6. Transport-loss handling — `VMDisconnectedException` and friends

Everything above assumes the JDWP socket is alive. When it dies mid-call, the codebase has a centralised classifier:

- **Listener thread** (`JdiEventListener.listen`) — catches `VMDisconnectedException | IllegalStateException` from `vm.eventQueue().remove()`. Waits for the dispatcher (bounded), closes the `SuspensionSession` — which wakes a `jdwp_resume_until_event` waiter — records `VM_DEATH`, clears `running`, runs the VM-death hook. An in-loop `VMDeathEvent` / `VMDisconnectEvent` takes the same path; an `InterruptedException` from `stop()` closes the session, clears `running` and runs the hook. The hook is single-shot via the `AtomicBoolean` CAS.
- **Tool layer** (`JDWPTools.java`) — `isVmGone` (lines 1086-1099) and `isTransportFailureFrame` (lines 1105-1117) walk the cause chain (bounded by `MAX_CAUSE_DEPTH = 8`) checking for `VMDisconnectedException`, `SocketException`, `EOFException`, plus message substrings like "Connection refused", "Broken pipe", "handshake failed" (full list at lines 1021-1030). When detected, the tool returns a `[VM_DEATH]` or `[VM_GONE]` envelope instead of an opaque stack trace. See [diagnostics.md](diagnostics.md) for the exact envelope strings.

`ObjectCollectedException` (the JDI signal that a target-VM object has been GC'd out from under us) is caught at:

- `JDWPTools.jdwp_mark_instance` (`JDWPTools.java:2769`) — refuses to mark a corpse.
- `MarkedInstanceRegistry.mark` (`marks/MarkedInstanceRegistry.java:66, 74`) — same.
- `EvaluationGuard` documents at `EvaluationGuard.java:22, 82` why the hot read cannot throw it.

The principle: every JDI call that crosses the wire can fail, and every failure has a single classifier above it. Code that calls JDI directly is allowed to throw; code that calls JDI on behalf of an MCP tool routes through the classifier.

## 7. Things that look concurrent but aren't

Reading the code, you may wonder about some apparent races. They are usually one of these patterns:

- **`volatile` reference, immutable target.** `BreakpointTracker.lastBreakpoint` is a `volatile LastBreakpoint` record. The record is immutable. The session writes a new instance when it publishes a stop; tools read the field once, for display. There is no torn read — the write is atomic, the value is frozen.
- **Select-or-wait under one monitor.** `SuspensionSession.nextStop` looks for an unseen stop, releases, and waits — all under the session monitor, waiting on its condition. Publication (`finish`) runs under the same monitor and calls `notifyAll`. A stop published between two tool calls is simply an unseen context the next call presents: there is no flag to consume and no latch to arm before resuming.
- **`putIfAbsent` / `remove(key, value)` on the guard's holds.** Taking a hold is one atomic operation, not a read-then-write: of two chains racing for the same thread, one wins and the other is refused. Removing by value means a stale lease never removes a newer hold.
- **Sampling outside, acting inside.** The watchdog reads thread status and asks for a monitor's owner without the session monitor; `unblock` then re-checks every condition under the monitor before it releases anything. A sample that went stale meanwhile changes nothing.

If you find yourself adding a lock to "fix" something here, first check whether the existing primitive is already doing what you want.

## 8. Don't hold locks across JDI invocations

This is the cardinal rule. `invokeMethod` can take seconds. A `synchronized` block that contains `invokeMethod` will block every other tool call for that long. Worse, if the same path can re-enter through the listener (it can, via the guard), holding a monitor across the boundary is a deadlock waiting to happen.

The `EvaluationGuard` Javadoc states it directly: "No locks are held across `invokeMethod` calls." The `JDIConnectionService` monitor is released by the time any tool method calls `evaluate` or `to_string`. The guard does the job a lock would do, without the deadlock potential.

When adding new code that crosses the JDI boundary, audit the call site for monitors held above. If in doubt: capture the values you need, drop the lock, then make the JDI call.

The `SuspensionSession` monitor follows the same rule, plus an order:

- **Never across an invocation, event processing or a wait.** The listener's `route` takes the same monitor, so anything slow under it stalls draining. Waits use the monitor's own condition (`wait`), which releases it.
- **Session before `EventRequestManager`.** `step` runs the step installer, which synchronizes on `erm`, under the session monitor.
- **`FastLogpointService` before the session.** `FastLogpointService.publishStop` runs under the service's monitor and reaches the session (the tracker's snapshot becomes a stop candidate, the history entry is buffered). So code under the session monitor never calls into `FastLogpointService` — `SuspensionSession` does not reference it at all.
- **JDWP probes of a running invocation stay outside.** The watchdog and `InvocationRunner`'s blocker report do their balanced suspend → `currentContendedMonitor()` → resume without the session monitor; only `unblock` acts inside it.
