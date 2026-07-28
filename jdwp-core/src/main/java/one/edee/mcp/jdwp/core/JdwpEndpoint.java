package one.edee.mcp.jdwp.core;

import java.util.Objects;

/**
 * Network address of a JVM exposing the JDWP socket transport.
 */
public record JdwpEndpoint(String host, int port) {
    public JdwpEndpoint {
        host = Objects.requireNonNull(host, "host").trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }
}
