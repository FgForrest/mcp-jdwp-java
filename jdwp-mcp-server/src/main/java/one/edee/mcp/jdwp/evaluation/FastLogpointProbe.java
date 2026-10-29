package one.edee.mcp.jdwp.evaluation;

import java.util.List;

/**
 * A compiled fast-logpoint probe class, ready to be defined in the target VM.
 *
 * @param className      binary name of the probe class
 * @param bytecode       the class file
 * @param parameterNames names of the {@code hit} method's parameters in declaration order —
 *                       {@code _this} for the receiver, otherwise the name of a local variable
 *                       visible at the logpoint's location
 */
public record FastLogpointProbe(String className, byte[] bytecode, List<String> parameterNames) {
}
