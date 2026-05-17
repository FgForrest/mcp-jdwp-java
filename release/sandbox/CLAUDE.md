# Sandbox house rules for the agent

You are debugging in the **JDWP test-flight sandbox**. This is not a real codebase. Every Java class in `src/main/` is deliberately broken and exists so the user can practice debugger-driven bug-hunting through the `jdwp-debugging` plugin.

## Read this before you touch anything

The scenarios are designed to expose bugs that **source-level reading misses** (mutation through ostensibly read-only paths, hash-key drift after key mutation, reflective field writes, partial-init races, etc.). If you read the source code first and pattern-match a fix, you have defeated the point of the exercise — and you will likely *also* miss the actual bug because the symptoms can have several plausible code-level explanations and only one of them is the one the JVM actually does at runtime.

## Rules

1. **Do not read sandbox source files until you have a debugger-driven hypothesis.** You may read the failing test (it states the symptom; that is the legitimate problem statement). Production sources under `src/main/java/.../` are off-limits until you can finish this sentence at the breakpoint: *"My evidence is X, my hypothesis is Y, the source I'd like to read to confirm is Z."*
2. **Do not fetch the main project README's "Reveal root cause" sections.** Those are spoilers. The user wants to verify the debugger experience works for them, not be told the answer by a search.
3. **Do not modify sandbox source files.** Even if you spot the bug, the goal here is diagnosis, not patching. The broken state is load-bearing.
4. **Use the `java-debug` skill that ships with the `jdwp-debugging` plugin.** Its "Core Workflow" section is the canonical sequence. The full set of JDWP tools is exposed via `mcp__plugin_jdwp-debugging_jdwp-inspector__*`.

## Canonical per-flight workflow

1. **Ask the user which flight.** If they said "play flight #N" or named a test class, use that. Otherwise list the flights from `README.md` and ask.
2. **Launch the test under JDWP.** Run in a background shell:
   ```
   mvn test -Dtest=<TestClass> -DskipTests=false -Dmaven.surefire.debug
   ```
   This hangs at "Listening for transport dt_socket at address: 5005". That is correct — it is waiting for an attach.
3. **Attach.** `jdwp_wait_for_attach(port=5005)`. If 5005 is occupied or the launch used a different port, fall back to `jdwp_diagnose()` and pick the discovered port.
4. **Set breakpoints from the symptom outward.** Start at the failing assertion or the line nearest the user-visible wrongness. Use line breakpoints, conditional breakpoints, exception breakpoints, and field watchpoints as the situation calls for. Many flights are *much* harder to crack with the wrong tool; pick deliberately.
5. **Inspect with `jdwp_get_breakpoint_context`** — one call returns the firing thread, the top frames, locals (including `this`), and the field dump of `this`. That replaces four separate get_* calls.
6. **Form a hypothesis at the breakpoint, then read the source to confirm.** State the hypothesis to the user *before* reading. This is how you avoid "I read it and it must mean X" — at runtime, the JVM tells you what it actually does.
7. **Between flights:** `jdwp_reset()` clears breakpoints and cached object refs without dropping the connection. Then ask the user to launch the next test.

## Tool reminders

- **`jdwp_resume_until_event`** blocks until the next breakpoint / step / exception event, or times out (default 30s). On timeout, the response includes a structured diagnostic — *read it* rather than blindly retrying with a larger timeout.
- **`jdwp_set_field_breakpoint(className, fieldName, mode)`** suspends on every JVM-level field read (`mode="access"`) or write (`"modification"`), including reflective writes via `Field.set` and `Unsafe`. This is the only way to catch a write whose call site does not name the setter.
- **`jdwp_set_exception_breakpoint`** catches at the throw site, *before* the stack unwinds — invaluable when wrappers (`CompletionException`, `RuntimeException` cause chains) obscure the original.
- **`jdwp_assert_expression(expr, expected)`** is your sanity check: "is the hash what I think it is?", "is the total what I think it is?". Returns MATCH or MISMATCH with the actual value.
- **`jdwp_evaluate_expression(expr)`** is your "what is this right now?" tool. Use freely.

## Reporting back

After each flight, tell the user:
- **What the bug actually is** (root cause in one sentence).
- **Which JVM observation revealed it** (the specific tool call + the value that contradicted the assumption from source reading).
- **What you would change to fix it** — but do NOT make the change. The sandbox is intentionally broken.

After all flights, score against the scorecard in `README.md`. Top of card is "Bug terminator — 6/6".

## What you may freely do

- Read `README.md` in this folder.
- Read the failing test classes (`src/test/java/.../*Test.java`).
- Use any tool in the `jdwp-debugging` plugin.
- Run `mvn` commands in the project.
- Ask the user clarifying questions about the flight they're on.

## What you must not do

- Read sandbox `src/main/java/...` files before debugging.
- Fetch the main project README's `<details>Reveal root cause</details>` blocks.
- Edit sandbox source files.
- Skip flights because they look hard.
- Convert the game into a code-review pass.

Good hunting.
