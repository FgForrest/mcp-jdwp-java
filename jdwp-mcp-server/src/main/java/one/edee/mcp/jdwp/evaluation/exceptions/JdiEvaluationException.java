package one.edee.mcp.jdwp.evaluation.exceptions;

import one.edee.mcp.jdwp.evaluation.InMemoryJavaCompiler;
import one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator;
import one.edee.mcp.jdwp.evaluation.RemoteCodeExecutor;

import org.jspecify.annotations.Nullable;

import java.io.Serial;

/**
 * Single checked exception propagated out of the expression-evaluation pipeline (the
 * {@link InMemoryJavaCompiler}, {@link RemoteCodeExecutor},
 * and {@link JdiExpressionEvaluator}). Caught at the top level by
 * `JDWPTools.jdwp_evaluate_expression` and `jdwp_assert_expression`, where the message is
 * formatted into the human-readable string returned to the MCP client.
 */
public class JdiEvaluationException extends Exception {

    @Serial
    private static final long serialVersionUID = -8640643362312809268L;

    /** Creates an evaluation failure with a message for the tool or event report. */
    public JdiEvaluationException(String message) {
        super(message);
    }

    /** Creates an evaluation failure with a message and an optional underlying cause. */
    public JdiEvaluationException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
