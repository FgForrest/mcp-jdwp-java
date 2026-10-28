package one.edee.mcp.jdwp.fastlogpoint;

/**
 * A fast logpoint could not be armed or disarmed. The message is written for the agent and ends up
 * in the tool response or a {@code FAST_LOGPOINT_*} event.
 */
public class FastLogpointException extends Exception {

    public FastLogpointException(String message) {
        super(message);
    }

    public FastLogpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
