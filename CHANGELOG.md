# Changelog

All notable changes to the `jdwp-debugging` plugin are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/), and this
project adheres to [Semantic Versioning](https://semver.org/).

## [2.10.1] — 2026-07-11

### Fixed — locals survive missing debug info, and attach/error messages stop lying

Two diagnostics defects predating 2.10.0, found while revalidating the 2.10.0 fixes.
`jdwp_get_locals` returned the opaque `Error: null` — discarding even the `this`
receiver it had already resolved — whenever the current method was compiled without a
local-variable table (`-g:vars`), because the underlying `AbsentInformationException`
carries no message. Separately, `jdwp_wait_for_attach` labelled an already-connected
session as "suspended at VM_START", which is actively misleading after a same-port
target restart, where the fast-path can hand back a stale handle.

- **`jdwp_get_locals` degrades gracefully** — a missing `LocalVariableTable` now keeps
  the `this` line and appends a frame-tailored hint (instance frames point at the
  receiver and `jdwp_get_fields`; static frames say there is no receiver) instead of
  replacing the whole response with `Error: null`.
- **No more `Error: null` or bare `Error:`** — tool error replies now fall back to the
  exception's simple class name when the detail message is null or blank (~35 catch
  sites across `JDWPTools` and `JDIConnectionService`).
- **`jdwp_wait_for_attach` is honest about already-connected sessions** — the
  `VM_START` guidance is emitted only on a genuine fresh attach; a pre-existing session
  is described as such and points at `jdwp_diagnose` / `jdwp_disconnect` for the
  same-port-restart recovery path.

Follow-up defects tracked in #36; the two deferred items there (attach PID-identity
race, off-classpath expression-eval diagnostics) remain open.

## [2.10.0] — 2026-07-11

### Fixed — exception breakpoints no longer NPE, and broken conditions fail loud

`jdwp_set_exception_breakpoint` and `jdwp_set_exception_logpoint` could fail with
the opaque `Cannot invoke "Object.hashCode()" because "key" is null` and leave
null-named pending entries behind — whenever the exception class name arrived
`null`. A null class took the deferred "class not loaded" path and then reached
`ConcurrentHashMap.containsKey(null)`. The class argument now short-circuits with a
clear error before any registry or VM work, and — see Breaking below — the tools
accept the class under the same `className` parameter the other breakpoint tools
use, so a client that reuses `className` everywhere binds correctly instead of
dropping the value to `null`. Resolves #34.

Separately, a breakpoint / logpoint / field-watchpoint **condition** that failed to
compile or threw at runtime used to suspend on every hit while only logging
server-side — so an agent could believe it was filtering when it was not (most
often a private-field reference, which the bare-field auto-rewrite cannot touch).
Such failures now record a `CONDITION_ERROR` event, making the fall-open decision
visible in `jdwp_get_events` / `jdwp_resume_until_event`.

- **Fail-fast class guard** — a null or blank class name returns
  `Error: className is required …` instead of an NPE, leaving the breakpoint
  registry untouched.
- **Loud, not silent, fail-open** — conditions still suspend on evaluation failure
  (so nothing is missed), but each failure emits a `CONDITION_ERROR` event naming
  the breakpoint, location, condition, and cause. The `java-debug` skill now
  documents the `getDeclaredField` / `setAccessible` block-form workaround for
  private-field conditions.

### Breaking — exception-tool class parameter renamed to `className`

`jdwp_set_exception_breakpoint` and `jdwp_set_exception_logpoint` previously named
their class argument `exceptionClass`; it is now `className`, consistent with
`jdwp_set_breakpoint` and `jdwp_set_field_breakpoint`. Clients that read the tool
schema on each call (all LLM-driven clients) adapt automatically; only a caller
hard-coded to send `exceptionClass=` needs updating. This naming inconsistency was
the root cause of the null-class NPE fixed above.

## [2.9.0] — 2026-05-28

### New — expression evaluation resolves types the remote classpath can't see

Every evaluated expression, logpoint, conditional breakpoint and watcher is
compiled against the target VM's classpath, which the server discovers by walking
the live classloader hierarchy. That walk is the source of truth, but it does not
see everything: Tomcat's webapp loader, Spring Boot's dev-tools restart loader, and
hand-rolled `URLClassLoader`s routinely hide their JARs from `getURLs()`. When that
happened, evaluation failed with a bare `X cannot be resolved` and no way forward.
The server now augments the remote classpath with a **local-project classpath**
computed from the machine the MCP server runs on, filling exactly those gaps.
Resolves #32.

- **Three additive sources** — the `JDWP_EXTRA_CLASSPATH` environment variable
  (verbatim entries you control), a depth-bounded filesystem scan for
  `target/classes` / `target/test-classes` under the server's working directory,
  and Maven `dependency:build-classpath` for each `pom.xml` it finds. The result is
  unioned remote-first, so live target-VM types always win resolution and local
  entries only fill what the remote walk missed.
- **Never blocks the debugger** — the cold-cache Maven resolution (which can take
  1–3 minutes on a fresh `~/.m2`) is never paid on the JDI event thread. The first
  breakpoint warms only the fast remote discovery; the local fallback, including
  Maven, is resolved lazily on your first actual evaluation and memoized for the
  rest of the connection.
- **Inspectable via `jdwp_diagnose`** — a new "Local project classpath" section
  reports the working directory, whether a `pom.xml` was found, a per-source entry
  breakdown, and the three-way `JDWP_EXTRA_CLASSPATH` state
  (`unset` / `set, no entries` / `set`). The diagnose path is non-blocking — it
  peeks the cache and never triggers discovery.
- **Disable it** by launching the server from a directory with no `pom.xml` and no
  `target/classes`, and leaving `JDWP_EXTRA_CLASSPATH` unset — the merged classpath
  is then identical to the remote-discovered one.

### Fixed — file-safety and cleanup hardening

The new discovery paths touch the filesystem and shell out to Maven, so they are
held to a strict safety contract: no file is ever deleted or moved, Maven output
stays under its own `target/` directory, and symlinks are never followed by either
the filesystem scan or the Maven-output harvester (a symlinked `target/`, `pom.xml`
or wrapper script is rejected rather than traversed outside the project tree).

- **Leak-proof Maven subprocess** — a timed-out or interrupted Maven run forcibly
  destroys the whole process tree it owns (never unrelated siblings), and the wait
  for the tree to exit is bounded for the tree as a whole rather than per-process.
- **Robust under restricted environments** — descendant-process enumeration,
  unreadable directories, and an unset `user.dir` are all tolerated without
  aborting discovery or failing server startup.

## [2.8.2] — 2026-05-26

### Fixed — expression evaluation no longer stalls or misleads after a reconnect

Every evaluated expression, logpoint, conditional breakpoint and watcher compiles
against the target VM's classpath, which the server discovers once per connection
and caches. Three rough edges in that lifecycle made failures slow and confusing —
most visibly after a disconnect/reconnect cycle, where the first evaluation could
fail with a cryptic `io cannot be resolved` and no hint as to why. The warming,
caching and failure-reporting paths are now tightened.

- **First stop pre-warms the classpath** — the one-time discovery (it walks the
  target's classloader hierarchy and can take 1–3 s) now runs on the first
  suspending breakpoint / step / exception / watchpoint instead of lazily on your
  first `jdwp_evaluate_expression` or logpoint hit. The cost is paid predictably at
  the first stop rather than on the critical path of the first evaluation.
- **Reconnect no longer compiles against the old target** — the compiler's
  classpath is reset on reconnect. Previously a failed re-discovery left the
  previous target's classpath in place, so expressions could silently compile
  against stale classes.
- **Discovery failures say so** — when the classpath / JDK can't be discovered, the
  evaluation now fails with an actionable message ("Classpath discovery failed …
  application types cannot be resolved") instead of surfacing a bare JDT diagnostic
  like `io cannot be resolved`. Logpoints record the same as a `LOGPOINT_ERROR`.
  (resolves #30)

### Docs — README rewritten newbie-first

- **README restructured for first-time readers** — reordered to lead with what the
  plugin is and how to attach to a JVM before the deeper material, and fact-checked
  against the source so the tool list and behavior descriptions match what ships.

## [2.8.1] — 2026-05-25

### Fixed — straight answers when evaluation can't see what it needs

Two paths returned dead-ends that steered agents the wrong way.
`jdwp_resume_until_event` could wake without a stop and flatly blame a concurrent
`jdwp_reset` / `jdwp_disconnect` — telling you to re-arm breakpoints that were, in
fact, still armed on a live VM. And any expression evaluated on a stack frame from
a target compiled without a local-variable table failed with the opaque
`Expression evaluation failed: null`, even when the expression touched no locals at
all — the common case for field logpoints using only `$oldValue` / `$newValue`.
Both now do the check they were skipping.

- **`[NO_EVENT]` checks before it advises** — the no-stop branch now probes VM
  liveness and the breakpoint registry. If the VM is alive with breakpoints still
  armed, it says so and tells you to simply call again (do *not* re-arm); only a
  genuinely cleared session gets the re-setup guidance. `[NO_EVENT]` is now also
  listed in the tool's documented return values. (resolves #27)
- **Expressions evaluate without a local-variable table** — a frame from a target
  built without `-g:vars` (plain `javac`, `-g:none`, or a stripped release) no
  longer aborts the whole evaluation. Locals are skipped when absent and the
  expression runs against `this` + the synthetic bindings; an expression that
  *does* name a missing local now gets a clear "cannot be resolved" compile error
  instead of `null`. (resolves #29)

### Docs — field watchpoints: what they catch, honestly

- **Reflective writes are not caught — corrected** — `docs/breakpoints.md` claimed
  a reflective `Field.set` / `Unsafe` write was "fully visible to a watchpoint",
  contradicting the java-debug skill and the JVM itself. Verified live on JDK 17: a
  modification watchpoint fires on constructor and direct assignments but stays
  silent on a reflective `Field.set` of the same field. The docs now say so, drop a
  test-flight spoiler, and add the reference-vs-contents distinction — a watchpoint
  fires on the field's slot, not on in-place mutation of the object it references.
  (resolves #28)

## [2.8.0] — 2026-05-25

### Fixed — disconnect no longer wipes your session in silence

The session-epoch work in 2.7.0 made the *event log* segment cleanly across a
VM restart, but the teardown tools themselves stayed mute about what they threw
away. `jdwp_disconnect` returned a bare `"Disconnected"` while it silently
cleared every breakpoint, watcher, mark, the object cache and the event
history; `jdwp_connect` switching to a different target tore down the prior
session just as quietly. An agent that had armed a dozen breakpoints got no
signal they had vanished. `jdwp_reset` and `jdwp_reconnect` already itemize what
they touch — this brings disconnect and connect up to the same parity.

- **`jdwp_disconnect` now reports what it cleared** — an itemized summary
  (breakpoints by kind, watchers, marks, event history, object cache) instead of
  one word. Counts are snapshotted before the wipe, so they reflect what the call
  actually discarded.
- **A reconnect hint, only when it matters** — when breakpoints or watchers were
  cleared, the reply points at `jdwp_reconnect` (re-attach to the same target,
  specs preserved) as the way to keep them across a VM restart. A bookkeeping
  disconnect with nothing set stays a terse one-liner — token-optimized.
- **`jdwp_connect` announces a target switch** — attaching to a different
  `host:port` now prepends a one-line "released previous session — N
  breakpoint(s), M watcher(s) cleared" notice, with the same reconnect pointer.
  (resolves #25)

## [2.7.0] — 2026-05-25

### New — see the lock graph, not just the threads

A 9-flight test-flight retrospective surfaced the same recurring friction:
`jdwp_get_threads` tells you a thread is in `MONITOR` status, but not *what*
it's blocked on or *who* holds the lock — so reconstructing a deadlock meant
suspending each thread and reading locals by hand. The new tool answers it in
one call, the natural sequel to 2.6.2's deadlock-inspection error message.

- **`jdwp_dump_locks`** — takes a balanced VM-wide suspend/resume snapshot,
  reads each thread's contended monitor and its owning thread, and prints the
  blocked-thread table plus any **deadlock cycle** (e.g.
  `transfer-A-to-B → transfer-B-to-A → …`). Cycle detection is a pure,
  JDI-free `DeadlockAnalyzer`. The suspend/resume is balanced, so a genuine
  deadlock stays put and a healthy VM is undisturbed; only synchronized-monitor
  contention shows (not `Object.wait()` / `java.util.concurrent` Locks).
  (resolves #23)

### Fixed — a misspelled breakpoint class no longer defers in silence

A breakpoint set on a fully-qualified class name that doesn't match anything
(`Config` for `Configuration`) deferred forever, with a message
indistinguishable from a genuinely not-yet-loaded class — a silent dead end
that cost a flight ~13 wasted calls.

- **Near-match class suggestions** — when a breakpoint defers because its class
  isn't loaded, the response now scans the loaded classes for resembling names
  (simple-name exact/prefix match + bounded edit distance) and appends a
  *"did you mean…"* hint. Applies to line, logpoint, field, and exception
  breakpoints; best-effort, never fails a breakpoint set. (resolves #24)
- **Length-safe event timestamps** — the event listing formatted times with a
  fixed `substring(11, 23)` that threw on a whole-second `Instant` (which prints
  without a fraction), collapsing `jdwp_get_events` into an error. It now uses a
  length-safe `HH:mm:ss.SSS` formatter.

### Changed — event history is now segmented by session

The `jdwp_get_events` log is deliberately preserved across a VM death (so the
`VM_DEATH` and the events leading to it stay readable), but on an auto-reconnect
to a relaunched target the old VM's events bled into the new session's stream
with no way to tell them apart.

- **Session-epoch tagging** — every event carries a monotonic session epoch,
  bumped on each attach; `jdwp_get_events` shows a per-line `s1`/`s2` tag and a
  divider at each new-VM boundary, so a death-then-reconnect reads as two clean
  segments without discarding the pre-death evidence. (resolves #25)

### Docs

- **`java-debug` skill discoverability** — a deadlock recipe built around
  `jdwp_dump_locks`; guidance to launch fast-finishing tests with `suspend=y` +
  `jdwp_wait_for_attach` so the VM can't die before breakpoints are armed; and a
  note that the event log survives a VM death (use `jdwp_clear_events` to start
  clean). (resolves #26)

## [2.6.2] — 2026-05-25

### Fixed — inspecting a deadlocked thread now points the way

A thread blocked on a monitor or parked in `Object.wait()` that you did not
stop at a breakpoint reports `Suspended: no` and never halts on its own — the
classic deadlock case. `jdwp_get_stack` answered that with a dead-end *"Thread
must be stopped at a breakpoint"*, and `jdwp_get_locals` had no suspend check at
all and leaked a raw `IncompatibleThreadStateException`. Both pointed away from
the one tool that actually helps: `jdwp_suspend_thread`, which freezes the
thread in place so its frames and locals become readable.

- **Actionable not-suspended error** — `jdwp_get_stack` and `jdwp_get_locals`
  now detect a MONITOR/WAIT thread that isn't JDI-suspended and name
  `jdwp_suspend_thread(id)` as the deadlock-inspection path (and `get_locals`
  gained the suspend check it was missing). The invoke-family tools
  (`evaluate_expression` / `to_string` / `assert_expression`) keep their
  existing guard — suspending such a thread makes it *inspectable*, not
  *invocable*. (resolves #22)

### Docs

- **Two `java-debug` skill notes from a live test-flight retro** — how to read a
  deadlocked thread (`jdwp_suspend_thread` it first), and the reminder that
  `jdwp_disconnect` does not stop the target JVM: the JVM owns its JDWP port, so
  a target with live non-daemon threads keeps the port bound after you
  disconnect — kill the lingering process before relaunching on the same port.

## [2.6.1] — 2026-05-24

### Fixed — connect / diagnose no longer alias a dead VM

The liveness check behind `jdwp_connect`'s "already connected" guard and the
`jdwp_diagnose` connection status probed the target with `vm.name()`, which JDI
caches after its first fetch — so a VM whose socket had already closed (an
orphaned test JVM left over from a previous debug session, or one mid-exit)
still read as alive. `connect` then short-circuited onto the stale VM and
`jdwp_diagnose` reported a live connection that wasn't; breakpoints never fired
and the disconnect only surfaced later.

- **Round-tripping liveness probe** — these two paths now issue a bounded
  `vm.allThreads()` (a real JDWP exchange) instead of the cached name: a closed
  socket is detected promptly and a wedged VM is bounded by a short timeout, so
  `connect` re-attaches to the VM you actually launched and `jdwp_diagnose`
  tells the truth. The cheap cached check stays on the per-tool-call hot path,
  where a false positive is harmless (the next real JDI call surfaces the
  disconnect). (resolves #21)

### Docs

- **"The Field That Lies" reads as a puzzle again** — the test-flight scenario
  had promised that a field-modification watchpoint catches the reflective
  `Field.set` that mutates the field; a flight on JDK 21 disproved it (JDI
  watchpoints fire on the `putfield` bytecode, not on reflective / `Unsafe`
  stores). The scenario no longer hands over the answer, and the `java-debug`
  skill gained a caveat: watchpoints miss reflective / `Unsafe` writes
  regardless of finality — bisect with line breakpoints + `identityHashCode`
  instead.

### CI

- **Actions pinned to Node 24** — `actions/checkout` and `actions/setup-java`
  were bumped past the Node 20 runtime deprecation in both the CI and release
  workflows.

## [2.6.0] — 2026-05-24

### New — tool responses now navigate to the next step

Every tool used to confirm what it did and stop; the calling agent had to
already know which tool to reach for next, and a logpoint gave no hint that its
output lands in the event log. An audit of the full tool surface drove a pass
that makes responses self-navigating — each one now points to the sensible next
tool (or says what to expect), so the set → run → read chain is visible in the
output instead of being carried only by the skill.

- **Logpoint confirmations point to the event log** — `jdwp_set_logpoint`,
  `jdwp_set_field_logpoint`, and `jdwp_set_exception_logpoint` now state that
  hits are recorded as they fire and read on demand with `jdwp_get_events`
  (logpoints never suspend and never push, so there is no "event fired" to react
  to).
- **Next-step pointers across the surface** — `jdwp_resume` points to the
  blocking `jdwp_resume_until_event`; `connect` / `reset` say to set breakpoints
  then resume; `set_local` / `set_field` point to step/resume; the inspectors and
  watcher / mark tools point to their natural follow-ups. Hints are terse footers
  added only where a next step is genuinely useful — tool descriptions were left
  untouched to avoid a per-turn token cost.
- **Failure paths navigate too** — a failed `jdwp_connect` now points to
  `jdwp_diagnose` to list local JVMs and their JDWP ports.

### Fixed

- **`$newValue` on field-access events** — a `mode="both"` field logpoint or
  condition that referenced `$newValue` failed to compile on every *access* hit
  (one `[FIELD_LOGPOINT_ERROR]` per read), because the binding was populated only
  for modification events. `$newValue` is now bound as a typed-null on access
  events, so the same expression compiles on both halves and renders `… -> null`
  on reads. (resolves #14)
- **Actionable `[NO_EVENT]` from `jdwp_resume_until_event`** — when the wait was
  released without a real stop (a concurrent `jdwp_reset` / `jdwp_disconnect`
  freeing the waiter, then nulling the snapshot), the tool returned a dead-end
  "this should not happen" message. It now returns an actionable `[NO_EVENT]`
  naming the likely cause and the recovery path. (resolves #15)

### Docs

- **`java-debug` skill guidance** — set a line breakpoint at the assertion as a
  safety net before a field watchpoint on short-running tests (avoids the
  `VM_DEATH` race where the write lands but the suspend never does); set the
  breakpoint at the `join()` / assertion line for concurrency flights, not right
  after `Thread.start()`; don't pipe the launch command through `tail` / `head`
  in a background shell. (resolves #16)

### CI

- **Test gate on push and PR** — a new GitHub Actions workflow runs the full
  `./mvnw test` suite (including the NullAway / Error Prone gate) on every push to
  `main` and every pull request, so regressions are caught on the server rather
  than only at release time. (resolves #18)

## [2.5.0] — 2026-05-24

### New — `excludeConstructors` flag on field watchpoints

A field watched for modification fires on every write, including the storm of
writes that happen inside the declaring class's constructors before any
interesting state exists — debugging a balance field meant stepping past three
`BankAccount` constructor writes before reaching the first real mutation. Both
`jdwp_set_field_breakpoint` and `jdwp_set_field_logpoint` now take an optional
`excludeConstructors` flag that filters those out.

- **`excludeConstructors=true`** — writes originating inside the declaring
  class's `<init>` / `<clinit>` are dropped at the listener: no event recorded,
  no chain trigger, no suspend. The watchpoint fires only on post-construction
  mutations. The check is a direct frame test — a method *called by* a
  constructor still counts as a real hit, so collaborators invoked during
  construction are not silently swallowed.

### New — invocation refused on `MONITOR` / `WAIT` threads

A thread that is JDI-suspended on top of a Java-monitor block or inside
`Object.wait()` reports `isSuspended() == true`, so the existing guards let it
through — but JDI's `invokeMethod` then resumes only that one thread, which
cannot make progress because the lock it needs is held by another suspended
thread (or the `notify()` that would wake it can never fire). With no
JDI-level timeout, the MCP server blocked indefinitely. This was the failure
mode anyone debugging a deadlock would hit.

- **Pre-flight guard** — `jdwp_evaluate_expression`, `jdwp_assert_expression`,
  `jdwp_to_string`, and `jdwp_evaluate_watchers` now refuse a thread in
  `THREAD_STATUS_MONITOR` / `THREAD_STATUS_WAIT` with an explicit error that
  points at `jdwp_get_stack` + `jdwp_get_threads` instead. The refusal turns a
  silent hang into a useful diagnostic — when you see it, you've usually found
  a deadlock or a missing notify.

### Docs

- **Skill prologue tightening** — the `java-debug` skill gained recipes for
  reconnecting after a target restart (`jdwp_reconnect` preserves breakpoints,
  watchers, and chain edges across the cycle), catching the failure point of an
  asserting test (`AssertionError` exception breakpoint), and calling a method
  on a non-public peer field via a block-mode reflection snippet. Two new
  gotchas cover the `MONITOR`/`WAIT` guard and the kill-and-reconnect recovery
  for the residual hang case.
- **Test-flight assignment prompt** — the sandbox README now ships a
  copy-pasteable prompt that briefs the agent on the game's house rules and a
  par-based scoring scheme (⭐⭐⭐ at par tool count, ⭐⭐ within 2×, ⭐ solved).

### Test flights — rebalanced to nine flights covering the full tool surface

The sandbox flight suite leaned heavily on field watchpoints (four of six
flights), so a strong score didn't prove the agent could wield the rest of the
toolset. The suite is now nine flights, each built so a *different* tool group
is the path of least resistance, and each carries a **par** — the minimum tool
calls that cleanly reveal the root cause — for style scoring.

- **Dropped** "The Vanishing Pennies" (mechanically a subset of "The Audit That
  Lies"). **Reshaped** two flights so a single breakpoint-context dump can no
  longer shortcut them: "The Swallowed Exception" now throws on a background
  executor task with no user-code catch frame (forcing a `caught=true` exception
  breakpoint + trigger gate), and "The Time Traveler's Config" now overwrites a
  correctly-set field from a reaper thread (forcing a field watchpoint + event
  history to read the write order).
- **Added** four flights for the under-exercised groups: *The Doppelgänger Cart*
  (marked instances — a copy-constructor swaps the object mid-pipeline),
  *The Heisenbug Race* (logpoints — a lost update you watch without suspending),
  *The Magic Patch* (`set_local` — confirm a fix by mutating live state), and
  *The Polite Standoff* (multi-thread inspection — a lock-ordering deadlock
  diagnosed with `jdwp_get_threads` + `jdwp_get_stack`, no breakpoints).

## [2.4.0] — 2026-05-24

### New — block-mode multi-statement expressions

Every place that takes a Java expression — `jdwp_evaluate_expression`,
`jdwp_assert_expression`, breakpoint conditions, logpoint expressions and
conditions, exception logpoints, field-watchpoint conditions/expressions, and
watchers — now also accepts a brace-wrapped statement body. Previously each
input had to be a single expression, so anything that needed an intermediate
local, a `try/catch`, or an early return could not be evaluated at a
breakpoint. Resolves #8.

- **`{ …; return X; }` block syntax** — when the trimmed input starts with `{`
  and ends with the matching `}`, it is spliced into the wrapper method body
  verbatim instead of being wrapped as a single expression. You write your own
  `return X;` to yield a value; a trailing `return null;` fallthrough guard
  keeps the wrapper type-correct if the block doesn't return on every path.
  Intermediate locals, `try/catch`, early returns, and loops are all available.
- **Literal-aware mode detection** — the brace match is tokenizer-aware and
  ignores braces inside string, char, and text-block literals, so `"{x}".length()`
  still routes through expression mode while
  `{ try { return foo(); } catch (Exception e) { return null; } }` routes
  through block mode.
- **Discoverable from any eval param** — all eight eval-bearing tool
  methods/params carry a "supports `{ …; return X; }` block syntax" suffix in
  their descriptions, so the feature surfaces wherever an eval-shaped parameter
  appears.
- **Auto-rewrite interaction** — the bare-field `this.field` auto-rewrite is
  skipped in block mode (an identifier-level rewriter cannot tell a field
  reference from a local declaration); block-mode users write explicit
  `this.field` / `_this.field`, which the keyword rewrite still handles.

## [2.3.0] — 2026-05-24

### New — deferred breakpoint/logpoint install diagnostics

When a breakpoint or logpoint targets a class that has not loaded yet, or a
line that compiles to more than one bytecode location, the install used to
succeed silently and leave you guessing why nothing fired. Two
no-behaviour-change diagnostics now make those cases visible. Resolves #9.

- **`CLASS_PREPARE` recorded in event history** — `jdwp_get_events` previously
  showed only `[VM_START, VM_DEATH]` for a session parked on a deferred
  breakpoint, so an agent could not tell "the class never loaded" from "the
  class loaded but the breakpoint never fired." The class-prepare event is now
  captured, making the distinction observable.
- **Multi-location warning** — when `locationsOfLine` returns more than one
  `Location` (typical for lambdas: one in the enclosing method, one in the
  synthetic `lambda$…$N`), the server emits a `BP_MULTI_LOCATION` event, a
  WARN log, and a `WARNING` suffix on the `jdwp_set_breakpoint` /
  `jdwp_set_logpoint` response. The bind logic is unchanged — it still binds
  the first location — so this is diagnostics only; a proper multi-location
  bind is tracked separately.

### Fixed — expression evaluation reaches a non-public `this` and its package-private members

Expression evaluation always emitted its wrapper class into a fixed
`mcp.jdi.evaluation` package, so a non-public `this` type and its
package-private fields were unreachable — the `this.field` auto-rewrite
short-circuited on the common case (e.g. a package-private
`BitmapSlicer.translator`). Resolves #7.

- **Wrapper emitted into `this`'s own package** when `this` is non-public and
  the package is addressable (not `java.*` / `javax.*` / `sun.*` / `jdk.*`,
  not a local/anonymous class). Same-package reachability then lets the
  wrapper dereference package-private types and members directly; the
  field-rewrite filter widens to public/protected/package-private (private
  still needs reflection).
- **Define-time fallback to the default package** — if a sealed package,
  module strong-encapsulation, or a restrictive classloader rejects
  `defineClass` for the application package, evaluation retries once in
  `mcp.jdi.evaluation`. The user expression never ran (the failure is in the
  define phase, before invoke), so the retry is safe and preserves the
  pre-target-package behaviour for public-only expressions.
- **Non-public interface types resolve to a reachable supertype** instead of
  dead-ending the declared-type walk.
- **Pipeline-exception subtype preserved** — a define-vs-invoke failure keeps
  its original `JdiEvaluationException` subtype and message instead of being
  flattened into a generic wrapper.

### Docs

- **Release workflow skill** — added a `release` skill capturing the
  plugin-release procedure (marketplace.json + CHANGELOG bump, annotated tag,
  cherry-pick/back-fill recovery), plus a fix to its predecessor-tag selection
  so release notes diff against the immediately-lower `v*` tag.

## [2.2.0] — 2026-05-23

### New — agent-driven JDI wedge recovery

When the target JVM stops responding to JDI commands (paused in a native
section, GC-stalled, frozen by another debugger client, or wedged behind a
deferred-class-load deadlock), the MCP server used to hang every subsequent
tool call indefinitely. Cancellation from the client was a no-op because the
worker was parked in a JDI native wait. The agent had no signal to
distinguish "the user hasn't hit my breakpoint yet" from "the JDI connection
is dead." Resolves #4.

Four cooperating pieces:

- **`JdiHealthMonitor`** — a daemon-thread service that observes JDWP
  traffic passively (every event drained by `JdiEventListener` brushes
  against `notifyTraffic`) and escalates to a single `vm.allThreads()`
  probe wrapped in `Future.get(5s)` after 30 s of silence. The snapshot
  (`RESPONSIVE` / `UNRESPONSIVE` / `DISCONNECTED` with last-traffic and
  last-probe timestamps) is read-only state for downstream renderers — the
  monitor never auto-recovers.
- **Soft wait protocol** — `jdwp_resume_until_event` and
  `jdwp_wait_for_attach` now lead their timeout responses with a structured
  `still_waiting` envelope citing the current JDI Health classification
  plus the `wait_more` / `reconnect` / `abort` options. No hard server-side
  backstop: the agent re-calls to wait more.
- **`jdwp_reconnect`** — `vm.dispose()`s the current connection and
  re-attaches to the last `host:port`, preserving the synthetic
  breakpoint-ID space, line / exception / field BP specs, conditions,
  logpoint expressions, chain edges, watchers, and event history. Marked
  instances, the object cache, last-suspended-thread context, and the
  classpath cache cannot survive `vm.dispose` and are dropped (the response
  enumerates what was preserved vs lost). A different target host:port
  needs `jdwp_connect`.
- **`jdwp_diagnose` extension** — a new JDI Health block renders status,
  last-traffic age, last-probe outcome, and the recommended action when
  the connection is wedged.

### Fixed — passive breakpoint registration (no more debugger-induced side effects)

Until this release, every `jdwp_set_*` tool (line, exception, field BPs and
logpoints) invoked `Class.forName(name)` inside the target VM when the
class was not yet visible to JDI. That changes target-application
behaviour the way a debugger never should: it triggers `<clinit>` early,
cascade-loads dependencies, can fire user breakpoints, and masks the
lazy-load diagnostics users attach a debugger to investigate. Resolves #3.

- **Default behaviour is now passive** — `JDIConnectionService.findLoadedClass`
  does `classesByName` + `allClasses` scan only (no `invokeMethod`).
  Unresolved classes are deferred via `ClassPrepareRequest`, matching
  IntelliJ / Eclipse / `jdb` semantics.
- **`forceLoad` opt-in** — all six set-breakpoint MCP tools accept a
  Boolean `forceLoad`; default `false`. Set `true` only when you need the
  bootstrap-class case for exception BPs (the primary motivator).
- **`BreakpointTracker.tryPromotePending`** now uses `findLoadedClass`
  exclusively; the unused `preferredThread` parameter is dropped.

### Fixed — deferred class-load deadlock in `BreakpointTracker.tryPromotePending`

`tryPromotePending` was `synchronized` and called `findOrForceLoadClass`
(which issues a target-VM `invokeMethod`) while holding the
`BreakpointTracker` monitor. The JDI event listener takes that same
monitor in `promotePendingFieldsForClass`, so a worker parked in
`invokeMethod` blocked the listener that had to deliver the reply,
permanently wedging the MCP server. Any subsequent MCP tool call queued
behind the worker on `JDIConnectionService`'s monitor, so the whole server
became unresponsive. Resolves #1.

- **`tryPromotePending` refactored** to snapshot the three pending maps
  under a brief synchronized block, drop the monitor before any JDI
  round-trip, and re-acquire it per entry for an atomic
  recheck-and-mutate.
- **`JDIConnectionService.findOrForceLoadClass`** split into three phases
  (lookup under monitor → `invokeMethod` outside monitor → post-invoke
  lookup on captured `vmRef`) so the same hazard cannot recur on the
  connection-service monitor.
- **`getVM()` restructured** to release its monitor before calling
  `tryPromotePending`, closing the reentrant-hold variant of the same
  cycle.
- **Two double-promotion races closed** — `promotePendingToActive` and
  `promotePendingExceptionToActive` are now `synchronized` and return
  `boolean`; `false` means "already promoted, caller must
  `erm.deleteEventRequest` the surplus request." All call sites updated.

### Fixed — review polish on the wedge-recovery feature

A round of code-review follow-ups before the release cut:

- **`jdwp_reconnect` leaves a recoverable tracker on attach failure** —
  active-map entries pointing at dead JDI request handles used to survive
  a failed reattach and trip `VMDisconnectedException` on the next
  `jdwp_reconnect`. The tracker is now restored to pure-pending state
  BEFORE the attach attempt.
- **`JdiHealthMonitor` probe / stop race closed** — the three snapshot
  publishes in `runActiveProbe` and the in-threshold publish in
  `probeTick` are wrapped in `synchronized(this)` blocks with a
  `vmRef.get() == vm` re-check, so a concurrent `stop()` cannot be
  overwritten back to `RESPONSIVE` / `UNRESPONSIVE`. `notifyTraffic()` is
  now `synchronized` and bails when `vmRef` is null, so a late
  event-listener drain after disconnect cannot revive a stopped monitor.
  `cancelProbeTask` switched from `cancel(false)` to `cancel(true)` to
  interrupt an in-flight probe promptly.
- **`vmDeathHook` detach window narrowed** during
  `reconnectPreservingSpecs` — the hook is now detached only around the
  listener `stop()` call (and re-attached in an inner `finally`) so any
  real `VMDeath` / `VMDisconnect` of the fresh VM during dispose, attach,
  listener start, or pending-BP promotion still invokes `notifyVmDied()`.
  The original intent — preventing `notifyVmDied → watcherManager.clearAll()`
  from firing during the intentional stop — is preserved.
- **Watchers preserved across `jdwp_reconnect`** — `JdiEventListener.stop()`
  invokes the VM-death hook; the hook now detaches around stop so the
  contract's "watchers survive" promise is honoured. Pinned by a new
  reconnect-watcher-preservation test.

## [2.1.2] — 2026-05-19

### Fixed — JDK discovery on SDKMAN / honors JAVA_HOME

Expression evaluation silently failed on machines where the local JDK was
installed via SDKMAN (typical macOS / Linux developer setup): the discovery
service only probed `/usr/lib/jvm`, `/opt/jdk-*`, and a few Windows paths,
so `~/.sdkman/candidates/java/17.0.18-tem/` never matched. The MCP server
logged `[ERROR] JDK path not discovered, cannot configure compiler` and
every `evaluate_expression` then returned `Compiler is not configured`.

- **SDKMAN paths now enumerated dynamically** —
  `~/.sdkman/candidates/java/<major>.*` entries are listed at discovery
  time and ordered with the newest patch first by numeric semver compare
  (so `17.0.18-tem` outranks `17.0.5-tem`, which a naive lexicographic
  sort would invert).
- **`JAVA_HOME` now used as a fast shortcut** — when set, the env var is
  honored ahead of the directory scan, but only after reading
  `<jdkHome>/release` and confirming the `JAVA_VERSION` major matches the
  target VM. A mismatched JAVA_HOME is skipped with a debug log instead of
  feeding the JDT compiler a wrong-version `--system` and producing
  cryptic class-file-version errors downstream.
- **Error message updated** to list `$JAVA_HOME`, `/opt/jdk-*`, and the
  SDKMAN path alongside the Windows / `/usr/lib/jvm` locations.

### Added — forensic elapsed-time logging on the hot path

So the next incident leaves enough breadcrumbs to localize the cost:

- `JDIConnectionService.discoverClasspath` logs `… in {}ms` on every
  exit branch (success, empty result, `JdkNotFoundException`, generic
  failure).
- `JdiExpressionEvaluator.evaluate` logs
  `Expression evaluated in {}ms (cache hit/miss)` on success and
  `Expression evaluation failed after {}ms: …` on exception. The
  cache-hit/miss tag separates compile-bound runs from invoke-bound ones.

## [2.1.1] — 2026-05-18

### Fixed — log file no longer dirties the user's working directory

The `mcp-jdwp-inspector.log` file used to land in whatever directory Claude
Code was launched from (typically the user's project root), because the
logback `FileAppender` resolved a relative path against CWD.

- **`.mcp.json` now passes `-DLOG_PATH=${CLAUDE_PLUGIN_ROOT}/logs/mcp-jdwp-inspector.log`**
  so plugin-launched runs write the log next to the plugin install. Logback
  creates the `logs/` subdirectory automatically.
- **`logback-spring.xml` fallback changed from CWD to `${java.io.tmpdir}`** —
  bare `java -jar` runs (tests, dev, debugging the server itself) now land
  the log in the OS temp dir instead of polluting CWD when `LOG_PATH` is
  unset.

`application.properties` documents the wiring so future readers know where
the log lives and why.

## [2.1.0] — 2026-05-18

### Fixed — output clarity and error envelopes (audit follow-ups)

A re-audit caught two leaks in the prior audit fix bundle plus a handful of
medium-severity polish items.

- **`jdwp_get_breakpoint_context` now uses the kind-aware event tag** —
  previously it still formatted `breakpoint=null` after a STEP or
  EXCEPTION snapshot (the F-RA2 sweep missed this third renderer).
- **`jdwp_evaluate_watchers` after STEP** — the STEP snapshot now
  inherits the BP id it stepped FROM, so watchers attached to that BP
  resolve at the step landing instead of silently emitting
  `No breakpoint ID available`.
- **Transport-loss exceptions now land on `[VM_DEATH]` / `[VM_GONE]`** —
  `SIGKILL` of the target JVM used to surface as
  `Error evaluating expression: Connection refused` (raw `IOException`).
  A new `isVmGone(Throwable)` predicate walks the cause chain matching
  `VMDisconnectedException`, `SocketException`, `EOFException`, plus
  observed message fragments (`Connection refused`, `Connection reset`,
  `Connection closed by peer`, `Broken pipe`, `Pipe closed`,
  `closed by the remote host`, `handshake failed`,
  `Bad file descriptor`). Walk is depth-bounded to defeat cause-chain
  cycles. `vmGoneEnvelope` walks to the deepest matching cause so the
  rendered reason names the actual transport failure instead of an outer
  wrapper's generic message.
- **STEP / EXCEPTION tag instead of stale `breakpoint=N`** — three
  renderers (`jdwp_resume_until_event`, `jdwp_get_current_thread`,
  `jdwp_get_breakpoint_context`) now show `via=step` / `via=exception`
  for STEP and EXCEPTION snapshots. BP snapshots keep `breakpoint=N`.

### Fixed — earlier audit bundle (already shipped in code; documenting here)

The fix bundle landed in code via the prior audit pass. Summary for the
release notes:

- Re-fetch `StackFrame` per watcher iteration so two watchers on one BP
  no longer crash the second with `InvalidStackFrameException`.
- `evaluate_expression` / `assert_expression` now thread marked-instance
  bindings so `$mark` identifiers resolve.
- Field watchpoint events bind `$oldValue` / `$newValue` / `$object`
  unconditionally — null-valued fields are observable.
- `BP_PROMOTION_FAILED` event recorded + surfaced inside `[VM_DEATH]`
  so deferred BPs on comment / blank lines report the failure to the
  agent instead of going dark.
- `resume_until_event` short-circuits via a `pendingFire` flag so a
  `step → otherTool → resume_until_event` sequence does not lose the
  STEP signal or overshoot the suspended state.
- `jdwp_attach_watcher` rejects unknown BP ids with an `[ERROR]`
  envelope instead of silently registering an inert watcher.
- `jdwp_diagnose` suppresses the disconnected `[DIAGNOSTIC]` block and
  filters the MCP server's own PID out of the JVM list.

### Output token savings

Output formats tightened so common turns spend fewer tokens:

- `jdwp_get_threads` defaults to a compact one-line-per-thread table
  (~25 lines for a 200-thread Tomcat vs ~1000 lines verbose); pass
  `verbose=true` for the legacy block format.
- `jdwp_overview` hides empty category headers by default; pass
  `showEmpty=true` to restore them.
- `jdwp_attach_watcher`, `jdwp_get_version`, empty `jdwp_get_events`,
  and empty `jdwp_reset` collapsed to one-line responses.
- `jdwp_diagnose` filters disconnected JVMs from the local-JVM list
  when a connection is live.

### Output gaps closed

- `jdwp_resume_until_event` includes `at Class:line` in the "Event
  fired" line — saves one round-trip per hit.
- `jdwp_step_*` responses include the start location so the agent can
  see where the step originated.
- `jdwp_evaluate_expression` echoes the expression
  (`Result of <expr>: <value>`) so several batched evals can be
  attributed.
- `jdwp_to_string` flags the default `Object.toString` form
  (`ClassName@hexhash`) so the agent knows to use `jdwp_get_fields`
  instead of trusting the string.
- `jdwp_wait_for_attach` success points at `jdwp_resume_until_event` as
  the next call.
- `jdwp_mark_instance` lists the tools that accept `$label` identifiers
  in its success footer.

### New — marked instances

Agent-chosen labels that pin a JDI `ObjectReference` in the target heap and
expose it to expression evaluation as a synthetic `$label` binding. Mirrors
IntelliJ IDEA's "Mark Object" feature; the agent can now reference a specific
instance by name inside conditional breakpoints, logpoint expressions,
watchers, and exception logpoints — across method boundaries where the local
variable name differs or is absent.

- `jdwp_mark_instance(label, objectId, pin=true)` — register a label.
  `disableCollection()` is called by default so the mark survives across
  events. Rejects collisions, reserved binding names (`exception`,
  `oldValue`, `newValue`, `object`, `fieldName`, `mode`, `_this`), Java
  keywords, and non-identifier labels with descriptive errors.
- `jdwp_unmark_instance(label)` — releases the pin and frees the slot.
- `jdwp_rename_mark(oldLabel, newLabel)` — keeps the underlying object and
  pin.
- Marks are auto-cleared on VMDeath, disconnect, and `jdwp_reset` (matching
  the object cache lifecycle).
- `jdwp_get_locals` and `jdwp_get_breakpoint_context` now append a
  "Marked instances visible to expressions" footer listing every live mark
  so the agent does not need a separate call to remember them.

### New — unified overview and clear

- `jdwp_overview(types?, filter?)` — single read-only listing of
  breakpoints, exception breakpoints, field breakpoints, logpoints,
  watchers, AND marked instances. Filter by type (comma-separated subset of
  `breakpoint, exception_breakpoint, field_breakpoint, logpoint, watcher,
  mark` or `all`) and/or by case-insensitive substring across class /
  label / expression / type. Chain status (`chain=trigger=#N`) is rendered
  inline for any BP that is part of a chain.
- `jdwp_clear(types, filter?)` — bulk-clear by type and/or filter. `types`
  is **required** to prevent an unguarded blanket wipe (use `'all'` to
  clear every kind). To preview a clear safely, call `jdwp_overview` with
  the same `types`/`filter` first — the matching rows are exactly what
  `jdwp_clear` would remove.

### Breaking — narrow list/clear tools removed

Replaced by the unified `jdwp_overview` / `jdwp_clear` pair. Agents or
scripts that referenced these will need to switch to the unified surface
(no functional gap — chain status, condition, and expression info all
render under `jdwp_overview`):

- **`jdwp_list_breakpoints`** — use `jdwp_overview(types="breakpoint")`.
- **`jdwp_list_exception_breakpoints`** — use `jdwp_overview(types="exception_breakpoint")`.
- **`jdwp_list_field_breakpoints`** — use `jdwp_overview(types="field_breakpoint")`.
- **`jdwp_list_all_watchers`** — use `jdwp_overview(types="watcher")`.
- **`jdwp_clear_all_breakpoints`** — use `jdwp_clear(types="breakpoint,exception_breakpoint,field_breakpoint")`
  or `jdwp_clear(types="all")` to also clear watchers/marks in one call.
- **`jdwp_clear_all_watchers`** — use `jdwp_clear(types="watcher")`.

Per-ID delete tools are unchanged: `jdwp_clear_breakpoint(id)` and
`jdwp_detach_watcher(id)` remain the canonical "delete this one thing" path.
`jdwp_list_watchers_for_breakpoint(breakpointId)` is also retained — it is
the only per-BP watcher query (the substring filter on `jdwp_overview` is
not breakpoint-id-aware).

## [2.0.1] — 2026-05-17

### Fixed — stdio handshake with MCP clients on protocol `2025-11-25`

Claude Code 2.1.143 (and any client requesting an MCP protocol newer than
`2024-11-05`) could not use the plugin: `/mcp` reported
`Failed to reconnect to jdwp-inspector: -32000` and every tool call was
silently dropped after `initialize`.

Root cause was upstream: `mcp-core`'s `StdioServerTransportProvider.protocolVersions()`
hardcodes `List.of("2024-11-05")` in every published version through
`2.0.0-M2` (the latest shipped with Spring AI `2.0.0-M6`). The server
downgraded the session in its `initialize` response, then `McpAsyncServer`
stopped responding to all subsequent requests.

The plugin now ships a local `MultiVersionStdioServerTransportProvider` that
advertises all four protocol versions known to the bundled SDK
(`2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`) and registers it as
`stdioServerTransport`, displacing Spring AI's `@ConditionalOnMissingBean`
auto-config bean. Existing clients on `2024-11-05` continue to negotiate
successfully.

## [2.0.0] — 2026-05-17

This release renames and removes several breakpoint-clear tools to unify them
around a single ID-keyed surface. Agents or scripts that referenced the old
tool names will need to update — the new surface is smaller and more
consistent. The headline new feature is **field watchpoints**.

### Breaking changes

- **`jdwp_clear_breakpoint(className, lineNumber)` — removed.** Clear by ID
  using the unified `jdwp_clear_breakpoint(id)` instead.
- **`jdwp_clear_breakpoint_by_id(id)` — renamed** to `jdwp_clear_breakpoint(id)`.
- **`jdwp_clear_exception_breakpoint(id)` — removed.** Exception breakpoints
  now clear through the unified `jdwp_clear_breakpoint(id)`.
- **`jdwp_set_exception_breakpoint` — `logOnly` and `expression` parameters
  removed.** The tool is now strictly suspending. For non-stopping exception
  tracing use the new dedicated `jdwp_set_exception_logpoint`.

After upgrade, the single rule is: **every breakpoint kind (line, exception,
field) is cleared by ID via `jdwp_clear_breakpoint(id)`**.

### New — field watchpoints

JDI watchpoints that suspend or log on every read / write of a specific
field — including reflective writes via `Field.set` and `Unsafe`, which line
breakpoints on a setter would miss entirely.

- `jdwp_set_field_breakpoint(className, fieldName, mode, ...)` — `mode` is
  `access`, `modification`, or `both` (IntelliJ-style, both directions bind
  to one synthetic ID).
- `jdwp_set_field_logpoint(...)` — non-stopping variant that records to
  event history.
- `jdwp_list_field_breakpoints()` — active + pending field BPs. *(Removed
  in Unreleased; replaced by `jdwp_overview(types="field_breakpoint")`.)*

Conditions and logpoint expressions get five synthetic bindings:
`$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`. Pending field
BPs promote **synchronously** on `ClassPrepareEvent`, so writes inside
`<clinit>` are captured.

### New — other tools

- **`jdwp_set_exception_logpoint(exceptionClass, expression, condition?, ...)`** —
  non-stopping exception trace with `$exception` bound to the thrown object.
- **`jdwp_diagnose()`** — single-call snapshot of attach state, active
  breakpoints, recent events, plus local JVM discovery and a VM-capability
  probe (reports whether the target supports field access / modification
  watchpoints, with a perf warning for full-class-attribute access
  watchpoints).

### Features

- **Chained breakpoints** with one-shot / sticky modes — trigger BPs arm
  dependent BPs, with cascade-on-clear semantics. New event types in history:
  `CHAIN_ARMED`, `CHAIN_DISARMED`, `CHAIN_BROKEN`.
- **Diagnostic timeout response** — when `jdwp_resume_until_event` times out,
  the response now includes a structured diagnostic block (active BPs,
  pending BPs, recent events) instead of a bare timeout string.
- **Test flight #6 — "The Field That Lies"** — a new sandbox scenario that
  exercises field-modification watchpoints by mutating a private field via
  reflection. A line BP on the public setter never fires; the field
  watchpoint does.

### Event history

New event types: `FIELD_ACCESS`, `FIELD_MODIFICATION`, `FIELD_LOGPOINT`,
`FIELD_LOGPOINT_ERROR`, `FIELD_BREAKPOINT_SUPPRESSED`, `CHAIN_ARMED`,
`CHAIN_DISARMED`, `CHAIN_BROKEN`.

### Docs

- README: new "Field breakpoints (watchpoints)" section, refreshed tool
  reference (46 tools), flight #6 added.
- `skills/java-debug/SKILL.md`: new recipes — "Who is overwriting this
  field?" and `<clinit>` capture — plus field-BP perf gotcha.
- `docs/EXPRESSION_EVALUATION.md`: synthetic-bindings table covering
  `$exception`, `$oldValue`, `$newValue`, `$object`, `$fieldName`, `$mode`.

## [1.0.3] and earlier

Prior baseline. See git history for details.
