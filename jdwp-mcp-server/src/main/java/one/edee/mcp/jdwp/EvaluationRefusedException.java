package one.edee.mcp.jdwp;

import java.io.Serial;

/**
 * An invocation on a target thread was refused before it started, because the thread is not stopped
 * at an event of its own that a tool has shown (issue #45, admission). The JDWP agent only allows an
 * invocation on the event thread of a suspending event; on any other thread it would fail, or hang
 * behind a stop the user has not seen. The message names the reason.
 */
public class EvaluationRefusedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public EvaluationRefusedException(String message) {
        super(message);
    }
}
