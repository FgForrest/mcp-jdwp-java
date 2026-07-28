package one.edee.mcp.jdwp.collector;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import one.edee.mcp.jdwp.core.FrameSnapshotter;
import one.edee.mcp.jdwp.core.JdiValueSnapshotter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Installs plan tracepoints and drains the target EventQueue until exit, timeout or event limit.
 */
final class TracePlanExecutor {
    private static final String TRACEPOINT_PROPERTY = "collector.tracepoint";
    private final VirtualMachine vm;
    private final DebugPlan plan;
    private final JsonlTraceWriter writer;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Integer> hitCounts = new HashMap<>();
    private final Map<String, Integer> installedCounts = new HashMap<>();

    TracePlanExecutor(VirtualMachine vm, DebugPlan plan, JsonlTraceWriter writer) {
        this.vm = vm;
        this.plan = plan;
        this.writer = writer;
    }

    CollectionResult execute() throws IOException {
        installTracepoints();
        writeLifecycle("collector_started", Map.of(
            "vmName", safeVmName(), "tracepointCount", plan.tracepoints.size()
        ));
        if (plan.resumeOnAttach) {
            vm.resume();
        }

        long lastEventAt = System.currentTimeMillis();
        String completion = "vm_disconnected";
        try {
            while (sequence.get() < plan.maxEvents) {
                long remaining = plan.idleTimeoutMillis - (System.currentTimeMillis() - lastEventAt);
                if (remaining <= 0) {
                    completion = "idle_timeout";
                    break;
                }
                EventSet eventSet = vm.eventQueue().remove(Math.min(remaining, 1_000));
                if (eventSet == null) {
                    continue;
                }
                lastEventAt = System.currentTimeMillis();
                boolean terminal = false;
                try {
                    for (Event event : eventSet) {
                        if (event instanceof BreakpointEvent breakpointEvent) {
                            captureBreakpoint(breakpointEvent);
                        } else if (event instanceof ClassPrepareEvent classPrepareEvent) {
                            installForPreparedClass(classPrepareEvent.referenceType());
                        } else if (event instanceof VMDeathEvent) {
                            completion = "vm_death";
                            terminal = true;
                        } else if (event instanceof VMDisconnectEvent) {
                            completion = "vm_disconnect";
                            terminal = true;
                        }
                    }
                } finally {
                    try {
                        eventSet.resume();
                    } catch (VMDisconnectedException ignored) {
                        terminal = true;
                    }
                }
                if (terminal) {
                    break;
                }
            }
            if (sequence.get() >= plan.maxEvents) {
                completion = "max_events";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            completion = "interrupted";
        } catch (VMDisconnectedException disconnected) {
            completion = "vm_disconnected";
        }
        writeLifecycle("collector_finished", Map.of("reason", completion));
        return new CollectionResult(completion, sequence.get(), Map.copyOf(hitCounts), Map.copyOf(installedCounts));
    }

    private void installTracepoints() {
        EventRequestManager manager = vm.eventRequestManager();
        for (DebugPlan.Tracepoint tracepoint : plan.tracepoints) {
            List<ReferenceType> loaded = vm.classesByName(tracepoint.className);
            if (loaded.isEmpty()) {
                ClassPrepareRequest prepare = manager.createClassPrepareRequest();
                prepare.addClassFilter(tracepoint.className);
                prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                prepare.enable();
            } else {
                loaded.forEach(type -> installAtLocations(tracepoint, type));
            }
        }
    }

    private void installForPreparedClass(ReferenceType type) {
        for (DebugPlan.Tracepoint tracepoint : plan.tracepoints) {
            if (tracepoint.className.equals(type.name())) {
                installAtLocations(tracepoint, type);
            }
        }
    }

    private void installAtLocations(DebugPlan.Tracepoint tracepoint, ReferenceType type) {
        try {
            List<Location> locations = type.locationsOfLine(tracepoint.line).stream()
                .filter(location -> tracepoint.methodName == null
                    || tracepoint.methodName.isBlank()
                    || tracepoint.methodName.equals(location.method().name()))
                .toList();
            for (Location location : locations) {
                BreakpointRequest request = vm.eventRequestManager().createBreakpointRequest(location);
                request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                request.putProperty(TRACEPOINT_PROPERTY, tracepoint.id);
                request.enable();
                installedCounts.merge(tracepoint.id, 1, Integer::sum);
            }
        } catch (AbsentInformationException noLines) {
            installedCounts.putIfAbsent(tracepoint.id, 0);
        }
    }

    private void captureBreakpoint(BreakpointEvent event) throws IOException {
        Object property = event.request().getProperty(TRACEPOINT_PROPERTY);
        if (!(property instanceof String tracepointId)) {
            return;
        }
        DebugPlan.Tracepoint tracepoint = plan.tracepoints.stream()
            .filter(candidate -> candidate.id.equals(tracepointId))
            .findFirst()
            .orElse(null);
        if (tracepoint == null) {
            return;
        }
        int hit = hitCounts.merge(tracepointId, 1, Integer::sum);
        if (hit > tracepoint.maxHits) {
            event.request().disable();
            return;
        }

        ThreadReference thread = event.thread();
        Map<String, Object> data = baseEvent("tracepoint_hit");
        data.put("tracepointId", tracepointId);
        data.put("hit", hit);
        data.put("thread", Map.of("id", thread.uniqueID(), "name", thread.name()));
        data.put("location", Map.of(
            "className", event.location().declaringType().name(),
            "methodName", event.location().method().name(),
            "line", event.location().lineNumber(),
            "codeIndex", event.location().codeIndex()
        ));
        if (tracepoint.capture.stack || tracepoint.capture.locals) {
            FrameSnapshotter frameSnapshotter = new FrameSnapshotter(
                new JdiValueSnapshotter(tracepoint.capture.limits())
            );
            data.put("frames", frameSnapshotter.capture(
                thread, tracepoint.capture.maxFrames, tracepoint.capture.locals
            ));
        }
        writer.write(data);
        if (hit >= tracepoint.maxHits) {
            event.request().disable();
        }
    }

    private void writeLifecycle(String type, Map<String, Object> details) throws IOException {
        Map<String, Object> event = baseEvent(type);
        event.putAll(details);
        writer.write(event);
    }

    private Map<String, Object> baseEvent(String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", "1.0");
        event.put("sessionId", plan.sessionId);
        event.put("sequence", sequence.incrementAndGet());
        event.put("timestamp", Instant.now().toString());
        event.put("eventType", type);
        return event;
    }

    private String safeVmName() {
        try {
            return vm.name();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    record CollectionResult(
        String completionReason,
        long eventCount,
        Map<String, Integer> hitCounts,
        Map<String, Integer> installedLocations
    ) {
    }
}
