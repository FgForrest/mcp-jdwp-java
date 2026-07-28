package one.edee.mcp.jdwp.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.core.JdiSocketAttacher;
import one.edee.mcp.jdwp.core.JdwpEndpoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone CLI entry point. It never depends on Spring or MCP.
 */
public final class CollectorMain {
    private CollectorMain() {
    }

    public static void main(String[] args) {
        try {
            Arguments arguments = Arguments.parse(args);
            run(arguments);
        } catch (Exception exception) {
            System.err.println("JDWP collection failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(2);
        }
    }

    static void run(Arguments arguments) throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        DebugPlan plan = mapper.readValue(arguments.plan().toFile(), DebugPlan.class);
        plan.validate();
        if (arguments.host() != null) {
            plan.target.host = arguments.host();
        }
        if (arguments.port() != null) {
            plan.target.port = arguments.port();
        }
        plan.validate();

        Files.createDirectories(arguments.outputDirectory());
        Path trace = arguments.outputDirectory().resolve("raw-trace.jsonl");
        Path manifest = arguments.outputDirectory().resolve("collection-manifest.json");
        Instant startedAt = Instant.now();
        TracePlanExecutor.CollectionResult result;
        VirtualMachine vm = new JdiSocketAttacher().attach(
            new JdwpEndpoint(plan.target.host, plan.target.port)
        );
        try (JsonlTraceWriter writer = new JsonlTraceWriter(mapper, trace)) {
            result = new TracePlanExecutor(vm, plan, writer).execute();
        } finally {
            try {
                vm.dispose();
            } catch (RuntimeException ignored) {
                // The target usually exits before the collector; a dead VM needs no disposal.
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "1.0");
        data.put("sessionId", plan.sessionId);
        data.put("target", Map.of("host", plan.target.host, "port", plan.target.port));
        data.put("plan", arguments.plan().toAbsolutePath().normalize().toString());
        data.put("trace", trace.toAbsolutePath().normalize().toString());
        data.put("startedAt", startedAt.toString());
        data.put("finishedAt", Instant.now().toString());
        data.put("completionReason", result.completionReason());
        data.put("eventCount", result.eventCount());
        data.put("hitCounts", result.hitCounts());
        data.put("installedLocations", result.installedLocations());
        mapper.writeValue(manifest.toFile(), data);

        System.out.printf(
            "Collection finished (%s), %d events written to %s%n",
            result.completionReason(), result.eventCount(), trace.toAbsolutePath()
        );
    }

    record Arguments(Path plan, Path outputDirectory, String host, Integer port) {
        static Arguments parse(String[] args) {
            Path plan = null;
            Path output = Path.of("output", "jdwp-trace");
            String host = null;
            Integer port = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "collect" -> {
                        // Optional readability token.
                    }
                    case "--plan" -> plan = Path.of(requireValue(args, ++index, argument));
                    case "--output" -> output = Path.of(requireValue(args, ++index, argument));
                    case "--host" -> host = requireValue(args, ++index, argument);
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++index, argument));
                    case "--help", "-h" -> throw new IllegalArgumentException(usage());
                    default -> throw new IllegalArgumentException("Unknown argument: " + argument + System.lineSeparator() + usage());
                }
            }
            if (plan == null) {
                throw new IllegalArgumentException("--plan is required" + System.lineSeparator() + usage());
            }
            return new Arguments(plan, output, host, port);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        private static String usage() {
            return "Usage: java --add-modules jdk.jdi -jar jdwp-batch-collector.jar collect "
                + "--plan <debug-plan.json> [--output <directory>] [--host <host>] [--port <port>]";
        }
    }
}
