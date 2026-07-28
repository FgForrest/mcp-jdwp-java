# JDWP Batch Collector

The batch collector is the non-interactive counterpart of the MCP server. Both adapters share
`jdwp-core`, but they intentionally serve different execution modes:

- `jdwp-mcp-server`: interactive, model-driven inspection;
- `jdwp-batch-collector`: deterministic plan execution and file output.

The collector does not require instrumentation or source changes in the target application. The
target JVM only needs JDWP enabled and classes compiled with debug information when local-variable
capture is required.

## Build

```powershell
mvn -pl jdwp-batch-collector -am clean package
```

The executable artifact is:

```text
jdwp-batch-collector/target/jdwp-batch-collector.jar
```

## Target JVM

Run the chosen JUnit test in a JVM that exposes JDWP:

```text
-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005
```

`suspend=y` is recommended because it lets the collector attach and install all tracepoints before
the test begins. Set `resumeOnAttach` to `true` in the plan so collection starts the test
automatically after installation.

## Run

```powershell
java --add-modules jdk.jdi `
  -jar jdwp-batch-collector/target/jdwp-batch-collector.jar collect `
  --plan examples/wafer-scheduler-debug-plan.json `
  --output output/wafer-order-trace
```

CLI `--host` and `--port` override the target stored in the plan.

The output directory contains:

- `raw-trace.jsonl`: one bounded JSON event per line;
- `collection-manifest.json`: collection timing, completion reason, hit counts, and installed
  breakpoint counts.

## Plan schema

Each tracepoint identifies a source line and optionally a method. The collector supports classes
that are already loaded and classes loaded later through `ClassPrepareRequest`.

Capture is deliberately bounded:

- `maxFrames`: maximum stack frames;
- `maxDepth`: object traversal depth;
- `maxItems`: array elements or object fields per value;
- `maxStringLength`: maximum captured string length;
- `maxHits`: maximum events emitted by that tracepoint.

The collector reads values through JDI only. It does not invoke methods in the target JVM. This
avoids side effects in scheduling code and makes raw evidence safer to reproduce.

## Current boundary

Version 1 collects source locations, stack frames, local variables, `this`, primitive values,
arrays, and bounded object fields. Expression evaluation and domain normalization are deliberately
outside the collector. A later normalizer converts `raw-trace.jsonl` to scheduling domain events
such as `candidate_generated`, `constraint_filtered`, and `candidate_selected`.

Do not attach the MCP server and batch collector to the same JVM at the same time. Re-run the
deterministic unit test for interactive follow-up.
