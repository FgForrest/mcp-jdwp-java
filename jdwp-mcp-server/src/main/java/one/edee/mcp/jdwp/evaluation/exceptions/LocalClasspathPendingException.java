package one.edee.mcp.jdwp.evaluation.exceptions;

import org.jspecify.annotations.Nullable;

import java.io.Serial;

/** Compilation can be retried when discovery is ready or when an earlier classpath was superseded. */
public final class LocalClasspathPendingException extends JdiEvaluationException {
    /** Serialization version for this discovery exception. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Signals a retryable classpath condition with an optional compiler failure. */
    public LocalClasspathPendingException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
