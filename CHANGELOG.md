# Changelog

All notable changes to the `jdwp-debugging` plugin are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/), and this
project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
