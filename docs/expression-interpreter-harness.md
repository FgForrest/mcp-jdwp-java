# Expression interpreter harness

Phase 0 records the compile-and-inject evaluator on a synthetic Java fixture under `jdwp-mcp-server/src/test`. The fixture keeps its application state independent of the sandbox test flights.

## Run the harness

```sh
mvn -o -pl jdwp-mcp-server test \
  -Dtest=InterpreterHarnessLiveTest,InterpreterExceptionLogpointLiveTest \
  -Djdwp.live=true \
  -Djdwp.live.target.java.home=/usr/lib/jvm/java-17-openjdk-amd64,/usr/lib/jvm/java-21-openjdk-amd64
```

`jdwp.live.target.java.home` accepts a single JDK home or a comma-separated matrix. It defaults to the JVM running the tests. The fixture listens on an ephemeral loopback port and starts suspended. The corpus loader and ECJ parser shape checks also run in the regular unit suite.

The live tests write `target/interp-baseline.md` at the repository root and one `target/interp-oracle-jdk<major>.jsonl` per target major. The baseline times the first `n + 1` evaluation on each connection and the immediate cache hit. It measures the unconfigured evaluator before listener prewarm and uses the fixture classpath. The packet counter uses JDI's public `Connection` SPI and counts matched command replies, including error replies. The exception-logpoint smoke test uses the regular server listener and tool to verify how it binds the actual thrown object.

## Corpus and comparison

Each JSON Lines row has string fields `id`, `tool`, `param` and `expression`. The synthetic rows also name a `stop` from `main`, `package`, `inner`, `exception` and `field`. `tool` and `param` record where an expression belongs. The replay calls the production evaluator at the fixture event with the corresponding bindings. The optional `expected_class` checks the compiled outcome's value type, exception type or `compile error: JdiEvaluationException`. `notes` is optional.

To include an owner-reviewed session corpus, pass its absolute path with `-Djdwp.live.corpus=/path/to/corpus.jsonl`. Additional metadata fields are accepted. IDs must be unique across both inputs. A row with a missing or unknown stop gets a skip reason in the report. A row mapped to a known stop executes Java in the fixture, so assign stops after reviewing the expressions and their intended frame.

The report stores production value rendering with process-specific object IDs and the fixture's character-array identity hash normalized. Primitive and boxed values use the same type label for comparison. Target exceptions use their Java class. Compiler diagnostics use the exception class so generated filenames and diagnostic wording do not affect comparisons. The report also stores the fixture call count and order after each evaluation.

A later engine implements `InterpreterHarnessLiveTest.Engine` and passes it to `replay` on a fresh fixture connection, with the corpus in its original order. `EvaluationContext` supplies a fresh frame, marks, event bindings and the same result formatter. Compare the resulting `Observation` by ID with the row's `golden` observation when present, or with `compiled` otherwise. The javac-compiled fixture methods supply outcomes for expressions that depend on private access, qualified `this`, `super` or generic types lost by the compiled wrapper. A fresh replay keeps mutations and class initialization local to each engine run.
