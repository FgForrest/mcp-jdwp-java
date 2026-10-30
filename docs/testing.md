# Testing

The server has ~850 `@Test` methods across ~80 files. The naming is mostly self-explanatory and the test support layer is small — but a few decisions are worth knowing about up front, especially the deliberate absence of any forked-JVM tests in CI.

## 1. The split

There are four logical tiers of tests, all running under JUnit 5:

| Tier | Examples | Approach |
|---|---|---|
| **Pure unit** | `BreakpointTrackerTest.PendingBreakpoints` (`BreakpointTrackerTest.java:32-143`), `EvaluationGuardTest`, `EventHistoryTest`, `ThreadFormattingTest`, `JDWPToolsParseCharInputTest`, `JdwpAgentArgParserTest`, `DiagnoseReportRendererTest` | No JDI types involved. Plain JUnit assertions against pojo behaviour. |
| **Mocked-JDI unit** (the bulk) | every `JdiEventListener*Test`, `JDIConnectionService*Test`, `JDWPTools*Test`, `SuspensionSession*Test`, `SuspensionWatchdogTest`, `InvocationRunnerTest` | `BreakpointRequest`, `ThreadReference`, `EventSet`, `Location`, etc. mocked via Mockito; `SuspensionSession*Test` and `SuspensionWatchdogTest` put the `FakeSuspendModel` count model behind the mocks (§ 2). |
| **In-process integration** | `InMemoryJavaCompilerPackageIntegrationTest` (`evaluation/InMemoryJavaCompilerPackageIntegrationTest.java:36`), `JdwpHandshakeProbeTest`, `JvmDiscoveryServiceConfirmAllTest` | Runs real Eclipse JDT against a tiny in-process classpath, or talks to a `FakeJdwpServer` over a loopback socket. |
| **Sandbox** | The `jdwp-sandbox/` module | Deliberately broken classes designed to fail under JDWP. Skipped by default. |

**No test forks a real Java process or attaches to a real JDWP port.** `ripgrep`-ing the test sources shows zero uses of `ProcessBuilder` or `AttachingConnector.attach`. The closest thing to "real network" is `FakeJdwpServer` — an in-process loopback `ServerSocket` that returns the 14-byte JDWP handshake.

Live-JVM verification is done **manually** via the sandbox module, not automatically in CI. See § 4.

**Opt-in exceptions.** `fastlogpoint/FastLogpointLiveTest` forks a JVM with a JDWP agent and attaches, because bytecode redefinition, breakpoint re-creation and the live-bytecode guard cannot be faked with mocks. It is skipped unless `-Djdwp.live=true` is set, so CI never runs it. Run it with `mvn -pl jdwp-mcp-server test -Djdwp.live=true -Dtest=FastLogpointLiveTest`; add `-Djdwp.live.jacocoAgent=<path to org.jacoco.agent-<version>-runtime.jar>` to also check that arming is refused for a JaCoCo-instrumented class. It prints the per-hit cost of a fast logpoint against `jdwp_set_logpoint`.

`fastlogpoint/FastBreakpointLiveTest` does the same for fast breakpoints: stops in both modes and policies, condition errors, inspection and steps at the user's frame, evaluations that overlap a stop-all, a clear while a thread is stopped in the method (the restore waits for its release, also for two entries in one class), and watchers by fast-breakpoint ID. Same opt-in: `mvn -pl jdwp-mcp-server test -Djdwp.live=true -Dtest=FastBreakpointLiveTest`.

`fastlogpoint/SuspensionOwnershipLiveTest` drives suspension ownership (issue #45) against the same forked target (`LiveSession`, fixture `FastLogpointTarget`): its `lock-holder` thread holds `FastLogpointTarget.LOCK`, so an evaluation can be made to wait on a monitor whose owner a stop-all parks. It checks that the watchdog frees a dispatcher evaluation stuck behind a queued stop-all, and that `jdwp_resume` releases only the stop it holds. Same opt-in: `mvn -pl jdwp-mcp-server test -Djdwp.live=true -Dtest=SuspensionOwnershipLiveTest`.

## 2. Mocking JDI

> **Background — why testing JDI is hard**
>
> JDI is one of the rare Java APIs with no first-party in-process fake. `com.sun.jdi.VirtualMachine` and its dozens of friends (`ThreadReference`, `ObjectReference`, `ReferenceType`, `StackFrame`, `Location`, `Method`, `Field`, every event subtype) are all interfaces, but every concrete implementation in OpenJDK lives in `com.sun.tools.jdi` and is tied to a real JDWP socket — there is no `LocalVirtualMachine` or "in-memory backend" that a test could spin up. Constructing them requires either a forked target JVM or substantial reflection.
>
> The two practical options are: (a) fork a real Java process and attach to it via `SocketAttachingConnector`, or (b) mock the interfaces. Option (a) is heavy (each test pays a 1–3 s JVM startup), brittle (timing, port conflicts, OS differences), and complicates CI. Option (b) is fast and deterministic but tedious — JDI chains are long (`event.thread().referenceType().name()`) and each link needs a stub.
>
> This codebase commits hard to option (b). The support classes below exist purely to keep the mock-chain setup compact.

Every JDI type is mocked with Mockito. A few test-support classes centralise the patterns so individual tests stay short:

### `JdiEventListenerTestSupport`

`JdiEventListenerTestSupport.java:47`. Factory methods for the event objects the listener consumes:

- `mockEventSet` (line 147)
- `mockThread` (line 153)
- `mockBreakpointEvent` (line 166)
- `mockStepEvent` (line 184)
- `mockExceptionEvent` (lines 203, 213)
- `mockException` (line 247)

Each factory wires the JDI `Location → ReferenceType → name()` chain that the production code walks. A test method composes them into a believable `EventSet` rather than rebuilding the chain from scratch.

### `JDIConnectionServiceTestSupport`

`JDIConnectionServiceTestSupport.java:36`. Provides:

- `newServiceWithMocks` (line 47), `newServiceWithCollaborators` (lines 63, 74) — factory methods.
- `setVm(service, mockVm)` (line 103) — **plants a mocked `VirtualMachine` into the private `vm` field by reflection**, bypassing the real attach path. This is what lets `JDIConnectionService*Test` exercise tool-level behaviour without ever calling `connector.attach(args)`.
- `setLastSuccessfulAttach` (line 118) — plants `lastHost` / `lastPort` for auto-reconnect tests.

### `JDWPToolsTestSupport`

`JDWPToolsTestSupport.java:28`. Factory for the seven-argument `JDWPTools` constructor — it has a lot of collaborators, and the support class assembles them with mocks for unit tests.

### `FakeSuspendModel`

A model of the JDWP agent's suspend bookkeeping (jdk21u `threadControl.c`, `invoker.c`, `eventHelper.c`) behind Mockito-built JDI mocks: per-thread suspend counts, where a resume at count 0 is a no-op; `vm.suspend()` / `resume()` with a global count that a thread started meanwhile inherits; event sets that suspend by policy and make their thread invokable, and `EventSet.resume()` that undoes exactly that; a single-threaded invocation that borrows one count and restores it on completion; thread status for the watchdog. Tests of suspension ownership assert the counts the target would really have (`counts()`), not which calls were made. `FakeSuspendModelTest` pins the model itself to that agent behaviour.

Built on it:

- `SuspensionSessionTest` — publication, presentation, releases with compensation, steps, `jdwp_reset`, leases and admission, ownership transfer; every scenario ends on the suspend counts.
- `SuspensionSessionUnblockTest` — what `SuspensionSession.unblock` may release for the watchdog, how a cancelled set is settled, and what it never touches.
- `SuspensionWatchdogTest` — sampling driven by hand (`tick()`, an injected clock): the minimum age, two consecutive monitor samples, samples that belong to one invocation, a closed session stopping the watchdog.

Mockito only, around the same feature:

- `EvaluationGuardTest` — the depth marker, plus leases: a nested lease, a competing chain refused, a reconnect (`bind`) dropping the earlier connection's holds.
- `InvocationRunnerTest` — the bounded wait: the result or the rethrown failure, `[STILL_RUNNING]` then exactly one late record, a busy thread refused, no late record in a later connection, a job given up before it started never run, a monitor's owner named with a balanced suspension.
- `JDWPToolsFrameToolsFreshFramesTest` — the frame tools re-read invalidated frames only while a hold the session knows lasted, and refuse a thread an evaluation runs on.

### `TestReflectionUtils`

`TestReflectionUtils.java:24` is a single-method helper used to invoke private methods (e.g. `getCollectionView`) on services. It is not the dominant pattern — most reflection happens inline in the support classes. New tests should reach for the support classes first.

## 3. Testing the event listener without a real event queue

`JdiEventListenerTestSupport.runListenerWith` (line 60) is the pattern for driving the listener through a synthesised event:

1. Stub `EventQueue.remove()` against a `BlockingQueue<Object>` pre-loaded with one caller-supplied `EventSet` followed by a `VMDisconnectedException` sentinel.
2. Start the listener.
3. Wait on a `CountDownLatch(2)` for both `EventQueue.remove()` takes.
4. Sleep 30 ms (line 84) to let the listener's catch block settle before assertions.

The sentinel exits the listener cleanly. The companion `runListenerWithEventThenDeathEvent` (line 100) substitutes a synthesised `VMDeathEvent` / `VMDisconnectEvent` for the sentinel, exercising the in-loop graceful-death branch instead of the disconnect-exception branch.

Used end-to-end in `JdiEventListenerEvaluationSuppressionTest.java:75` and many others. Tests assert via `tracker.getLastBreakpointThread()`, `verify(eventSet).resume()`, and `assertLatestEventType` (`JdiEventListenerTestSupport.java:262`) which **deliberately ignores the trailing harness-induced `VM_DEATH` entry**.

The 30 ms sleep is the only timing dependency in the test suite. It is a known flake hotspot if CI is heavily loaded; if you see intermittent failures in `JdiEventListener*Test`, that is the place to look first.

## 4. The sandbox module

`jdwp-sandbox/` is a separate Maven module of **deliberately broken** Java classes. Each one has a paired test that is **expected to fail** when run normally — the failure is the exercise. Examples:

- `bank/TransferService.java:21` — non-atomic transfer with a mid-state audit snapshot. Test "money invariant" fails because the audit captures the intermediate state.
- `deadlock/Account.java` — two transfers acquire locks in opposite orders and deadlock. Test fails when its join times out; diagnosed purely by thread inspection, no breakpoints.
- `recursion/RecursiveCalculator.java` — actually correct! Used to verify the recursive-breakpoint protection works end-to-end with a real JVM.

The full set is documented in the top-level [`README.md`](../README.md) as the "test flights".

### Skip-by-default

`jdwp-sandbox/pom.xml:25-34` declares `<skipTests>true</skipTests>` as a **project property** (not surefire config). The reactor-level default `./mvnw test` skips them, but a CLI override works:

```bash
./mvnw -pl jdwp-sandbox test -DskipTests=false
```

This keeps a plain `./mvnw test` green while leaving the sandbox runnable on demand — typically under JDWP, for manual verification of the MCP server against a real target.

The property-not-surefire-config distinction matters: it survives surefire upgrades and works with any plugin that respects `skipTests`.

## 5. Test-naming conventions

- **`XxxTest`** — standard unit / mock test. The default.
- **`XxxLiveTest`** — exercises a real production code path with a hand-built mock object graph (e.g. `JDIConnectionServiceCollectionViewLiveTest.java:47-233` builds a fake HashMap from `mock(ObjectReference.class)`). Despite the name, **there is no real JVM** — except in the opt-in `fastlogpoint/FastLogpointLiveTest` and `SuspensionOwnershipLiveTest` (§ 1). "Live" means "exercising the live production code path against realistic mock state". A misleading name, but the convention is what it is.
- **`XxxChainTest`** — tests for the breakpoint-chaining feature (`BreakpointTrackerChainTest`, `JdiEventListenerChainTest`).
- **`XxxIntegrationTest`** — single example, `InMemoryJavaCompilerPackageIntegrationTest`. Actually invokes ECJ for end-to-end compilation. New tests that genuinely cross a real boundary should follow this name.
- **`XxxTestSupport`** — shared scaffolding / factories. Package-private, `final` with private constructor.
- **`Fake*`** — in-process fakes: `FakeJdwpServer`, and `FakeSuspendModel`, the suspend-count model behind JDI mocks.

Many tests use nested `@Nested` classes named after behavioural areas (`PendingBreakpoints`, `ActiveBreakpoints`, `SuspendPolicy` in `BreakpointTrackerTest.java:34, 147, 176, 274, 349`). The pattern lets one file own a coherent slice of behaviour without becoming a 3,000-line linear scroll.

## 6. Compile-time enforcement

NullAway runs as part of `javac` via Error Prone. The configuration is in `jdwp-mcp-server/pom.xml`:

- Line 21-26 — declares `org.jspecify:jspecify:1.0.0`.
- Line 92 — the Error Prone plugin arg: `-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:JSpecifyMode=true -XepExcludedPaths:.*/src/test/.*`. NullAway runs as ERROR (not warning), respects `@NullMarked`, and **excludes tests** from the check.
- Lines 107-118 — `annotationProcessorPaths` registers `error_prone_core` and `nullaway`.
- Line 84 — `<fork>true</fork>` so `-J--add-exports` / `--add-opens` (lines 96-105) reach the forked `javac`.

`@NullMarked` applies at every production package via the per-package `package-info.java` files (root + `evaluation`, `evaluation/exceptions`, `watchers`, `discovery`, `marks`, `transport`). Tests are intentionally not annotated — `@NullMarked` would noisily warn on every Mockito stub that returns `null`.

This means a `./mvnw -pl jdwp-mcp-server compile` that emits any `[NullAway]` diagnostic fails the build. Treat NullAway output as compile errors.

The `jdwp-sandbox` module is intentionally **not** `@NullMarked` — its classes are deliberately broken debugging targets and nullability annotations would muddy the exercises.

## 7. Coverage gaps worth knowing

Some areas are deliberately under-covered:

- **No automated live-JVM coverage in CI.** The full `JDIConnectionService.connect()` path, the JDI event-queue drainer against real JDI, and `RemoteCodeExecutor.defineClass()` injection are never exercised against a real target VM in CI. Verification is the sandbox — manual.
- **`transport/` is untested.** `MultiVersionStdioServerTransportProvider.java` and `StdioTransportConfig.java` have no test files. The override is small and the upstream behaviour is what we're working around, so the absence is defensible — but a smoke test that asserts `protocolVersions()` returns four entries would be cheap and useful.
- **`marks/` and `watchers/` have no dedicated test directories.** Coverage is indirect via `JDWPToolsMarkInstanceTest`, `JDIConnectionServiceMarkLifecycleTest`, `JDWPToolsAttachWatcherTest`, etc.
- **`ClasspathDiscoverer` and `RemoteCodeExecutor` have no `*Test` files** under `evaluation/`. Only `InMemoryJavaCompiler*`, `JdiExpressionEvaluator{GetDeclaredType, Rewrite}Test`, and `JdkDiscoveryService*Test` are present. Both `ClasspathDiscoverer` and `RemoteCodeExecutor` cross the JDWP wire, so unit-testing them well requires a substantial mock harness — the team has so far preferred to exercise them via the sandbox.
- **`JDWPMcpServerApplication`** has no smoke test. It is a one-line `SpringApplication.run`, so the value would be low, but a "context loads" test would catch broken Spring wiring early.

If you're adding code in any of those areas, consider adding the test that wasn't there. The patterns in the support classes will make it cheap.

## 8. Running the tests

The day-to-day commands:

```bash
# Everything green by default
./mvnw test

# Just the MCP server (skips the sandbox module entirely)
./mvnw -pl jdwp-mcp-server test

# Run the sandbox test flights manually (most will fail by design)
./mvnw -pl jdwp-sandbox test -DskipTests=false

# A specific sandbox flight under JDWP, suspended on port 5005
./mvnw -pl jdwp-sandbox test -Dtest=RecursiveCalculatorTest \
       -DskipTests=false -Dmaven.surefire.debug
```

The `-Dmaven.surefire.debug` form starts the test JVM with `-agentlib:jdwp=...,address=5005,suspend=y` — the standard recipe for attaching the MCP server to a sandbox target.

## 9. Adding a test

The shape of a typical test in this codebase:

```java
class FeatureTest {

    private final TestSupport support = new TestSupport();

    @Test
    void description_of_behaviour() {
        // 1. Arrange — build mocks via the support class.
        EventSet eventSet = support.mockEventSet(...);

        // 2. Act — drive the production code.
        listener.handle(eventSet);

        // 3. Assert — read state through the tracker / history / mock verify.
        assertThat(tracker.getLastBreakpointThread()).isEqualTo(...);
    }
}
```

If your test needs JDI types, reach for the support class first. If your test needs a real classpath (e.g. an ECJ-compilation test), look at `InMemoryJavaCompilerPackageIntegrationTest` for the pattern. If your test needs a real JDWP target, you are writing a sandbox test flight, not a unit test — put it in `jdwp-sandbox` and run it manually.

The rule is "tests fast, fast tests" — anything that needs a real JVM goes to the sandbox. The unit suite must stay snappy.
