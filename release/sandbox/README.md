# JDWP Test Flights — Sandbox Edition

Nine deliberately broken Java scenarios. Each one compiles, looks reasonable, and **fails its test with a confusing message**. Your job: attach Claude Code via the `jdwp-debugging` plugin and let the debugger find the root cause that source-level reading would miss.

Each flight is built so that one tool group is the cleanest way in, and lists a **par** — the minimum tool calls that reveal the root cause. Across the ten, the suite exercises expression eval, exception breakpoints, field watchpoints, event history, marked instances, logpoints, runtime mutation, multi-thread inspection, and in-target probes.

This is the companion sandbox for the [jdwp-debugging Claude Code plugin](https://github.com/FgForrest/mcp-jdwp-java). Install the plugin first, then come back here.

## Prerequisites

| Need | Version |
|---|---|
| JDK | 17 or newer |
| Maven | 3.9 or newer |
| Claude Code | Latest |
| Plugin | `jdwp-debugging` 2.0.0+ installed |

Verify:

```bash
java -version       # 17+
mvn -version        # 3.9+
claude --version    # any recent
```

In Claude Code, the `jdwp-debugging` plugin must be listed and active. The plugin ships a `java-debug` skill the agent will pick up automatically when you ask it to debug.

## How to play one flight

Two terminals.

**Terminal 1** — launch the broken test under JDWP, suspended at startup:

```bash
mvn test -Dtest=SessionStoreTest -DskipTests=false -Dmaven.surefire.debug
```

The test process **hangs** at "Listening for transport dt_socket at address: 5005" — that's correct. It's waiting for a debugger.

**Terminal 2** — open Claude Code in this folder and paste the assignment prompt below.

Claude will attach, set breakpoints, evaluate expressions, and walk you through what the JVM actually does. When it's solved, kill the test (or let it finish) and move on to the next flight.

Between flights, ask Claude to run `jdwp_reset()` — it clears breakpoints and cached state without dropping the connection.

## The assignment prompt

Paste this into Claude Code to start a flight. Replace `#N (TestClass)` with the flight you want.

```
You are about to play JDWP test flight #1 (SessionStoreTest) on port 5005.

The rules:
1. The /java-debug skill is your playbook. Use it.
2. Sandbox source under src/main/java/.../ is OFF LIMITS until you can finish this
   sentence at a breakpoint: "My evidence is X, my hypothesis is Y, the source I'd
   like to read to confirm is Z." The failing test class itself is fair game — it's
   the problem statement.
3. Don't fix the bug. Diagnose it. The broken state is load-bearing for future flights.
4. Don't read this project's main README or the spoiler blocks anywhere — that's cheating.
5. Score yourself: count the JDWP tool calls you made to crack this flight. Each flight
   has a "par" — the minimum tool count that cleanly reveals the root cause. Hitting par
   = ⭐⭐⭐. Within 2× par = ⭐⭐. Solved at all = ⭐. Report the count when you finish.

Now attach via jdwp_wait_for_attach(port=5005), set the breakpoints the flight calls
for, and tell me:
  - What the bug actually is (root cause in one sentence)
  - Which JVM observation revealed it (the tool call + the value that contradicted
    the source-level assumption)
  - What you would change to fix it (but don't make the change)
  - Your tool-call count for this flight

Good hunting.
```

If you'd rather Claude pick the flight: replace the first line with `You are about to play one JDWP test flight — pick one from README.md and tell me which.`

## House rules

`CLAUDE.md` in this folder briefs the agent on the game's rules: don't read source files first, debug-driven hypothesis only. **Do not edit `CLAUDE.md`** if you want a fair flight — its rules exist so the experience exercises the debugger rather than static analysis.

## The flights

### #1 The Phantom Session

**Difficulty:** Moderate | **Test:** `SessionStoreTest` | **Package:** `session` | **Par:** 4

**Symptom:** `retrieve() returned null` — a session was stored, upgraded, and then... vanished from the map.

**Hint:** The session is still *in* the HashMap. The HashMap just can't *find* it anymore.

### #2 The Swallowed Exception

**Difficulty:** Hard | **Test:** `EventBusTest` | **Package:** `events` | **Par:** 4

**Symptom:** `expected stock < 100 but was 100` and an empty error summary — the order was supposed to reserve inventory, but nothing happened and nobody complained.

**Hint:** The handler runs on a background thread. Nothing in your code catches its failure — so there's no catch block to break on. Catch the throw itself.

### #3 The Time Traveler's Config

**Difficulty:** Hard | **Test:** `ConfigurationProviderTest` | **Package:** `config` | **Par:** 4

**Symptom:** `expected timeout=5000 but was 0` — the timeout was set during construction, yet it reads back as the default.

**Hint:** The value *was* set correctly. Something wrote over it afterward. There's no half-built object to inspect — the damage is in the order of writes.

### #4 The Audit That Lies

**Difficulty:** Hard | **Test:** `TransferServiceTest` | **Package:** `bank` | **Par:** 4

**Symptom:** `expected discrepancy=0 but was non-zero` — money is neither created nor destroyed, yet the audit says the books don't balance.

**Hint:** The transfer moves money in two steps. The audit snapshot is taken between them.

### #5 The Field That Lies

**Difficulty:** Hard | **Test:** `UserProfileTest` | **Package:** `userprofile` | **Par:** 3

**Symptom:** `expected: <Alice> but was: <alice>` — the welcome message rendered correctly, yet the user's stored display name has silently changed casing.

**Hint:** You will not find the write by ripgrep'ing for `setDisplayName` — the public setter is never called. A line BP on the setter never fires. Whatever path the write travels, the field's value still flips. Let the JVM tell you exactly when it changes, regardless of how the write reaches the field.

### #6 The Doppelgänger Cart

**Difficulty:** Moderate | **Test:** `CheckoutTest` | **Package:** `cart` | **Par:** 6

**Symptom:** `expected 45.0 but was 0.0` — the cart goes through pricing and discounting, yet its total never changes.

**Hint:** The object you get back from the pipeline is not the object you passed in. Prove it.

### #7 The Heisenbug Race

**Difficulty:** Hard | **Test:** `RaceCounterTest` | **Package:** `race` | **Par:** 3

**Symptom:** `expected 2 but was 1` — two threads each increment a counter once, but one increment vanishes.

**Hint:** Two threads, one lost update. Suspending a thread to look changes the timing — watch the reads without stopping anything.

### #8 The Magic Patch

**Difficulty:** Warm-up | **Test:** `DateParserTest` | **Package:** `parser` | **Par:** 3

**Symptom:** `NumberFormatException: For input string: "15 "` — a date string that looks right fails to parse.

**Hint:** The input looks *almost* right. Rather than rebuild with a fix, patch the value in place and see if the test goes green.

### #9 The Polite Standoff

**Difficulty:** Hard | **Test:** `TransferDeadlockTest` | **Package:** `deadlock` | **Par:** 4

**Symptom:** The test hangs and then fails its join — both transfers never complete.

**Hint:** Nothing is executing. No exception is thrown. Find out what each thread is waiting for. (Don't try to evaluate expressions on the stuck threads — the debugger refuses, and that refusal is a clue.)

### #10 The Oversold House

**Difficulty:** Hard | **Test:** `BoxOfficeTest` | **Package:** `boxoffice` | **Par:** 3

**Symptom:** `[Tickets sold for 300 performances of 200000 seats] expected: 60000000L but was: 60001634L` — every seat is checked before it is sold, yet the season ends oversold by a few tickets per performance.

**Hint:** Eight windows, 480 million sale attempts in a few seconds, a handful of bad ones per performance. Anything that stops a thread on every pass turns the sale into hours, and the extra tickets stop appearing while you watch. The check has to run where the code runs.

## Scorecard

**Flights solved** — did you find the root cause?

| Solved | Rating                                                               |
|--------|----------------------------------------------------------------------|
| 0-2    | The JVM is winning. Check your setup.                                |
| 3-5    | Solid start. You're getting the hang of breakpoint-driven debugging. |
| 6-9    | Impressive. You found bugs that would take hours with println.       |
| 10     | Bug terminator. Nothing survives your debugger.                      |

**Style** — how close to par? ⭐⭐⭐ at par tool count, ⭐⭐ within 2×, ⭐ solved at all. A flawless run is 30 stars across the ten flights.

## Where the spoilers live

Each flight has a root-cause writeup with a debug path — but in the **main project README** on GitHub, not in this zip. Open the [main project README's "Find the Bug — test flights" section](https://github.com/FgForrest/mcp-jdwp-java#find-the-bug--test-flights) once you've solved (or given up on) a flight, and look under each flight's `Reveal root cause` toggle.

The reveals are deliberately not in this zip so the agent that you ask to play can't accidentally read them.

## Troubleshooting

- **`Listening for transport dt_socket at address: 5005` then nothing happens.** Correct — that's the JVM waiting. Go to the other terminal and tell Claude to attach.
- **Claude reports "port 5005 not listening".** Did the test start? In terminal 1, do you see the "Listening for transport" line? If not, the test hasn't reached the suspend point — likely a compile failure earlier in the log.
- **Port 5005 already taken.** Another debugger is using it. Either kill the other process or change ports: `-Dmaven.surefire.debug="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5006"`, then attach with `port=5006`.
- **Plugin doesn't seem active.** In Claude Code, `/plugins list` — `jdwp-debugging` must be present and enabled. If not, `/plugins install` it from the marketplace.

## Have fun

The point isn't to solve them fastest. The point is to feel what a runtime-aware debugger gives you that a code reader can't see. If you finish all ten and want more, the same techniques apply to real bugs in your own code.
