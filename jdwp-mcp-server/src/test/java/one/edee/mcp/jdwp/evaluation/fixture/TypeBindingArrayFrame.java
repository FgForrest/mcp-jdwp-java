package one.edee.mcp.jdwp.evaluation.fixture;

import one.edee.mcp.jdwp.evaluation.fixture.shadow.Integer;

/** References the imported type only through a multidimensional array class constant. */
final class TypeBindingArrayFrame {
    /** Provides a breakpoint in the frame with array-only evidence of the imported type. */
    static void inspect() {
        TypeBindingFixture.sink = Integer[][].class;
    }
}
