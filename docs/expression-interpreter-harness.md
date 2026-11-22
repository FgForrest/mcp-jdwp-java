# Expression interpreter harness

The harness compares the compiled and interpreted evaluator on a synthetic Java fixture under `jdwp-mcp-server/src/test`. The fixture keeps its application state independent of the sandbox test flights.

## Run the harness

```sh
mvn -o -pl jdwp-mcp-server test \
  -Dtest=InterpreterHarnessLiveTest,InterpreterExceptionLogpointLiveTest \
  -Djdwp.live=true \
  -Djdwp.live.target.java.home=/usr/lib/jvm/java-17-openjdk-amd64,/usr/lib/jvm/java-21-openjdk-amd64
```

`jdwp.live.target.java.home` accepts a single JDK home or a comma-separated matrix. It defaults to the JVM running the tests. The fixture listens on an ephemeral loopback port and starts suspended. The corpus loader and ECJ parser shape checks also run in the regular unit suite.

The live tests write `target/interp-baseline.md` at the repository root and one `target/interp-oracle-jdk<major>.jsonl` per target major. The baseline first runs an untimed warm-up on one connection per selected target to warm host ECJ against the selected JDKs. It then takes five fresh connections per target, interleaved by trial. Each connection contributes its first `n + 1` evaluation and five immediate repeats per engine. The compiled repeats are cache hits. The report names the host JVM and shows median and min–max wall time, sample counts, and JDWP packet counts. It measures the unconfigured evaluator before listener prewarm and uses the fixture classpath.

The packet counter uses JDI's public `Connection` SPI and counts matched command replies, including error replies. The timed window includes production value formatting. Attach, stop setup and the extra semantic reads used by differential replay happen outside that window. The exception-logpoint smoke test uses the regular server listener and tool to verify how it binds the actual thrown object.

## Corpus and comparison

Each JSON Lines row has string fields `id`, `tool`, `param` and `expression`. The synthetic rows also name a `stop` from `main`, `package`, `inner`, `exception` and `field`. `tool` and `param` record where an expression belongs. The replay calls the production evaluator at the fixture event with the corresponding bindings. The checked-in `expected_plan` is NAVIGATE, COMPILE or ERROR. The optional `expected_class` checks the compiled outcome's value type, exception type or `compile error: JdiEvaluationException`. `notes` is optional.

To include an owner-reviewed session corpus, pass its absolute path with `-Djdwp.live.corpus=/path/to/corpus.jsonl`. Additional metadata fields are accepted. IDs must be unique across both inputs. A row with a missing or unknown stop gets a skip reason in the report. A row mapped to a known stop executes Java in the fixture, so assign stops after reviewing the expressions and their intended frame.

The report stores production value rendering with process-specific object IDs and the fixture's character-array identity hash normalized. Value facts add the reflected type of class objects, string contents, scalar values, and array lengths and elements. Array reads sample the first 16 elements at each of two levels. Primitive and boxed values use the same type label and scalar representation. Array and object results can carry identities such as `items` or `$target.store.map` when they match references captured at that stop. Target exceptions use their Java class. Compiler diagnostics use the exception class so generated filenames and diagnostic wording do not affect comparisons.

The candidate engine uses strict interpreter mode for NAVIGATE rows and auto mode for compiled rows. Each ERROR row uses an isolated compiler mock in auto mode and checks the planner diagnostic and that neither compiler setup nor compilation ran. It checks the plan for every row and compares each result with the javac fixture's golden observation when present for navigation, or the compiled observation otherwise. Compiled rows run in corpus order to preserve fixture state, including class initialization. Each observation includes call count, call order and the initialization state of `Cold`. The replay uses a fresh target for each engine.

To report the external corpus without a target JVM, run `mvn -o -pl jdwp-mcp-server test -Dtest=InterpreterSessionCorpusTest -Djdwp.live.corpus=/path/to/corpus.jsonl`. It reports aggregate syntax classification for observed and top-up rows in `target/interp-session-distribution.txt`. Navigation counts are upper bounds until JDI types resolve.

Replay deletes the request for the current fixture stop before evaluating its rows and arms the next request after those evaluations finish. The corpus exercises this by calling the future `Inner.inspect()` stop from a `main` row, then reaching the scheduled inner stop during normal execution.
