package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.evaluation.fixture.ConcurrentMapTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the collection view through live JDI field reads. */
@EnabledIfSystemProperty(named = "jdwp.live", matches = "true")
@Timeout(90)
class ConcurrentMapLiveTest {
    /** Reads normal, treeified, empty, truncated and actively resizing maps. */
    @Test
    void readsConcurrentMaps() throws Exception {
        for (final Path javaHome : InterpreterTargetVm.javaHomes()) {
            try (final InterpreterTargetVm target = InterpreterTargetVm.startClass(javaHome, ConcurrentMapTarget.class);
                 final CountingJdwpConnection wire = CountingJdwpConnection.attach(target.port())) {
                final VirtualMachine vm = Bootstrap.virtualMachineManager().createVirtualMachine(wire);
                try {
                    inspectAtBreakpoint(vm);
                    System.out.printf("JDK %s: inspected five ConcurrentHashMap layouts%n", vm.version());
                } finally {
                    vm.dispose();
                }
            }
        }
    }

    /** Stops after the target prepares the five maps and proves the special bins are present. */
    private static void inspectAtBreakpoint(VirtualMachine vm) throws Exception {
        final ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
        prepare.addClassFilter(ConcurrentMapTarget.class.getName());
        prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        prepare.enable();
        Objects.requireNonNull(vm.eventQueue().remove(15_000)).resume();
        final EventSet prepared = Objects.requireNonNull(vm.eventQueue().remove(15_000));
        final ReferenceType type = ((ClassPrepareEvent) prepared.iterator().next()).referenceType();
        vm.eventRequestManager().deleteEventRequest(prepare);
        final BreakpointRequest stop = vm.eventRequestManager().createBreakpointRequest(
            type.methodsByName("inspect").get(0).location());
        stop.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        stop.enable();
        prepared.resume();
        final EventSet stopped = Objects.requireNonNull(vm.eventQueue().remove(15_000));
        assertThat(stopped.iterator().next().request()).isEqualTo(stop);
        final JDIConnectionService connection = InterpreterHarnessLiveTest.connectionFor(vm, new EvaluationGuard());
        final ObjectReference tree = map(type, "TREE");
        final ObjectReference resizing = map(type, "RESIZING");
        assertThat(binType(tree, 0)).endsWith("$TreeBin");
        assertThat(binType(resizing, 63)).endsWith("$ForwardingNode");
        assertThat(view(connection, map(type, "SMALL"))).contains("Size: 2", "\"a\" = 1", "\"b\" = 2");
        final String treeView = view(connection, tree);
        assertThat(treeView).contains("Size: 12", " = 0\n", " = 11\n");
        assertThat(treeView.lines().filter(line -> line.matches("  Object#.+ = \\d+"))).hasSize(12);
        assertThat(view(connection, map(type, "EMPTY"))).contains("Size: 0", "Entries:\n");
        final String largeView = view(connection, map(type, "LARGE"));
        assertThat(largeView).contains("Size: 57", "... (7 more entries)");
        assertThat(largeView.lines().filter(line -> line.matches("  \\d+ = \\d+"))).hasSize(50);
        final String resized = view(connection, resizing);
        assertThat(resized).contains("Size: 48", "  0 = 0\n", "  63 = 63\n").doesNotContain("Error");
        final List<String> entries = resized.lines().filter(line -> line.matches("  \\d+ = \\d+")).toList();
        assertThat(entries).hasSize(48).doesNotHaveDuplicates();
    }

    /** Returns a prepared fixture map. */
    private static ObjectReference map(ReferenceType type, String field) {
        return (ObjectReference) type.getValue(type.fieldByName(field));
    }

    /** Reads the concrete class occupying a bin to verify the fixture layout. */
    private static String binType(ObjectReference map, int index) {
        final ArrayReference table = (ArrayReference) map.getValue(map.referenceType().fieldByName("table"));
        return ((ObjectReference) table.getValue(index)).referenceType().name();
    }

    /** Caches the mirror and uses the service entry point backing jdwp_get_fields. */
    private static String view(JDIConnectionService connection, ObjectReference map) throws Exception {
        connection.formatFieldValue(map);
        return connection.getObjectFields(map.uniqueID());
    }
}
