package one.edee.mcp.jdwp.core;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VirtualMachineManager;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Small, framework-independent adapter around JDI's SocketAttach connector.
 */
public final class JdiSocketAttacher {
    private static final String SOCKET_ATTACH = "com.sun.jdi.SocketAttach";

    public VirtualMachine attach(JdwpEndpoint endpoint)
        throws IOException, IllegalConnectorArgumentsException {
        Objects.requireNonNull(endpoint, "endpoint");
        return attach(Bootstrap.virtualMachineManager(), endpoint);
    }

    VirtualMachine attach(VirtualMachineManager manager, JdwpEndpoint endpoint)
        throws IOException, IllegalConnectorArgumentsException {
        AttachingConnector connector = manager.attachingConnectors().stream()
            .filter(candidate -> SOCKET_ATTACH.equals(candidate.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("JDI SocketAttach connector not found"));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        requiredArgument(arguments, "hostname").setValue(endpoint.host());
        requiredArgument(arguments, "port").setValue(Integer.toString(endpoint.port()));
        return connector.attach(arguments);
    }

    private static Connector.Argument requiredArgument(
        Map<String, Connector.Argument> arguments, String name
    ) {
        return Objects.requireNonNull(
            arguments.get(name), "SocketAttach connector missing '" + name + "' argument"
        );
    }
}
