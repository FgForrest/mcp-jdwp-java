# JDWP Test Flights — Sandbox Edition

Six deliberately broken Java scenarios. Each one compiles, looks reasonable, and **fails its test with a confusing message**. Your job: attach Claude Code via the `jdwp-debugging` plugin and let the debugger find the root cause that source-level reading would miss.

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
mvn test -Dtest=OrderProcessorTest -DskipTests=false -Dmaven.surefire.debug
```

The test process **hangs** at "Listening for transport dt_socket at address: 5005" — that's correct. It's waiting for a debugger.

**Terminal 2** — open Claude Code in this folder and type:

```
Use JDWP to play test flight #1 (OrderProcessorTest). Port 5005.
```

Claude will attach, set breakpoints, evaluate expressions, and walk you through what the JVM actually does. When it's solved, kill the test (or let it finish) and move on to the next flight.

Between flights, ask Claude to run `jdwp_reset()` — it clears breakpoints and cached state without dropping the connection.

## House rules

`CLAUDE.md` in this folder briefs the agent on the game's rules: don't read source files first, debug-driven hypothesis only. **Do not edit `CLAUDE.md`** if you want a fair flight — its rules exist so the experience exercises the debugger rather than static analysis.

## The flights

### #1 The Vanishing Pennies

**Difficulty:** Warm-up | **Test:** `OrderProcessorTest` | **Package:** `order`

**Symptom:** `expected 71.982 but was 71.0` — the order total loses its decimal part somewhere between calculation and return.

**Hint:** The calculation is correct. Something *after* it changes the total. Who would mutate an order during logging?

### #2 The Phantom Session

**Difficulty:** Moderate | **Test:** `SessionStoreTest` | **Package:** `session`

**Symptom:** `retrieve() returned null` — a session was stored, upgraded, and then... vanished from the map.

**Hint:** The session is still *in* the HashMap. The HashMap just can't *find* it anymore.

### #3 The Swallowed Exception

**Difficulty:** Moderate | **Test:** `EventBusTest` | **Package:** `events`

**Symptom:** `expected stock < 100 but was 100` and no error summary — the order was supposed to reserve inventory, but nothing happened and nobody complained.

**Hint:** There are actually *two* bugs. One is hiding the other. Start with the exception — why isn't the error summary showing anything?

### #4 The Time Traveler's Config

**Difficulty:** Hard | **Test:** `ConfigurationProviderTest` | **Package:** `config`

**Symptom:** `expected timeout=5000 but was 0` — the configuration exists but its timeout field is still at the default value.

**Hint:** The config object is assigned to the shared field *before* it's fully initialized. A reader thread sees the reference but reads a half-constructed object.

### #5 The Audit That Lies

**Difficulty:** Hard | **Test:** `TransferServiceTest` | **Package:** `bank`

**Symptom:** `expected discrepancy=0 but was non-zero` — money is neither created nor destroyed, yet the audit says the books don't balance.

**Hint:** The transfer moves money in two steps. The audit snapshot is taken between them.

### #6 The Field That Lies

**Difficulty:** Hard | **Test:** `UserProfileTest` | **Package:** `userprofile`

**Symptom:** `expected: <Alice> but was: <alice>` — the welcome message rendered correctly, yet the user's stored display name has silently changed casing.

**Hint:** You will not find the write by ripgrep'ing for `setDisplayName` — the public setter is never called. A line BP on the setter never fires. Whatever path the write travels, the field's value still flips. Let the JVM tell you exactly when it changes, regardless of how the write reaches the field.

## Scorecard

| Solved | Rating                                                               |
|--------|----------------------------------------------------------------------|
| 0-1    | The JVM is winning. Check your setup.                                |
| 2-3    | Solid start. You're getting the hang of breakpoint-driven debugging. |
| 4-5    | Impressive. You found bugs that would take hours with println.       |
| 6      | Bug terminator. Nothing survives your debugger.                      |

## Where the spoilers live

Each flight has a root-cause writeup with a debug path — but in the **main project README** on GitHub, not in this zip. Open the [main project README's "Find the Bug — test flights" section](https://github.com/FgForrest/mcp-jdwp-java#find-the-bug--test-flights) once you've solved (or given up on) a flight, and look under each flight's `Reveal root cause` toggle.

The reveals are deliberately not in this zip so the agent that you ask to play can't accidentally read them.

## Troubleshooting

- **`Listening for transport dt_socket at address: 5005` then nothing happens.** Correct — that's the JVM waiting. Go to the other terminal and tell Claude to attach.
- **Claude reports "port 5005 not listening".** Did the test start? In terminal 1, do you see the "Listening for transport" line? If not, the test hasn't reached the suspend point — likely a compile failure earlier in the log.
- **Port 5005 already taken.** Another debugger is using it. Either kill the other process or change ports: `-Dmaven.surefire.debug="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5006"`, then attach with `port=5006`.
- **Plugin doesn't seem active.** In Claude Code, `/plugins list` — `jdwp-debugging` must be present and enabled. If not, `/plugins install` it from the marketplace.

## Have fun

The point isn't to solve them fastest. The point is to feel what a runtime-aware debugger gives you that a code reader can't see. If you finish all six and want more, the same techniques apply to real bugs in your own code.
