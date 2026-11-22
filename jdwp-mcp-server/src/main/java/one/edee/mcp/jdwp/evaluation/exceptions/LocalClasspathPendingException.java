package one.edee.mcp.jdwp.evaluation.exceptions;

import org.jspecify.annotations.Nullable;

import java.io.Serial;

/** An unresolved type may become available when local classpath discovery is merged. */
public final class LocalClasspathPendingException extends JdiEvaluationException {
    /** Serialization version for this discovery exception. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Signals a pending local classpath and preserves the compiler failure if one occurred. */
    public LocalClasspathPendingException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
