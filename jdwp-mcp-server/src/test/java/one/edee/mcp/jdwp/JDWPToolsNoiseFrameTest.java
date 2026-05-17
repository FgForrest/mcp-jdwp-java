package one.edee.mcp.jdwp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JDWPTools#isNoiseFrame(String)}, the predicate used by both
 * {@code jdwp_get_stack} and {@code jdwp_get_breakpoint_context} to collapse JUnit/maven/reflection
 * machinery out of stack traces. Pure string-prefix matching, no JDI involvement.
 */
@DisplayName("JDWPTools isNoiseFrame predicate")
class JDWPToolsNoiseFrameTest {

	@Test
	@DisplayName("flags org.junit.* frames")
	void shouldFlagJunitFrames() {
		assertThat(JDWPTools.isNoiseFrame("org.junit.platform.engine.support.hierarchical.NodeTestTask")).isTrue();
		assertThat(JDWPTools.isNoiseFrame("org.junit.jupiter.engine.execution.MethodInvocation")).isTrue();
	}

	@Test
	@DisplayName("flags maven surefire frames")
	void shouldFlagSurefireFrames() {
		assertThat(JDWPTools.isNoiseFrame("org.apache.maven.surefire.booter.ForkedBooter")).isTrue();
	}

	@Test
	@DisplayName("flags JDK reflection frames")
	void shouldFlagJdkReflectFrames() {
		assertThat(JDWPTools.isNoiseFrame("jdk.internal.reflect.DirectMethodHandleAccessor")).isTrue();
		assertThat(JDWPTools.isNoiseFrame("java.lang.reflect.Method")).isTrue();
		assertThat(JDWPTools.isNoiseFrame("sun.reflect.NativeMethodAccessorImpl")).isTrue();
	}

	@Test
	@DisplayName("flags java.lang.invoke and jdk.internal.invoke frames")
	void shouldFlagInvokeFrames() {
		assertThat(JDWPTools.isNoiseFrame("java.lang.invoke.LambdaForm$DMH")).isTrue();
		assertThat(JDWPTools.isNoiseFrame("jdk.internal.invoke.MhUtil")).isTrue();
	}

	@Test
	@DisplayName("does NOT flag user code")
	void shouldNotFlagUserCode() {
		assertThat(JDWPTools.isNoiseFrame("io.mcp.jdwp.sandbox.order.OrderProcessor")).isFalse();
		assertThat(JDWPTools.isNoiseFrame("com.example.MyService")).isFalse();
	}

	@Test
	@DisplayName("does NOT flag java.util collections")
	void shouldNotFlagJavaUtil() {
		// java.util.ArrayList legitimately appears in user-relevant stack traces (e.g. forEach lambda)
		assertThat(JDWPTools.isNoiseFrame("java.util.ArrayList")).isFalse();
		assertThat(JDWPTools.isNoiseFrame("java.util.HashMap")).isFalse();
	}

	@Test
	@DisplayName("does NOT match a class whose name starts with the prefix substring but no dot")
	void shouldNotMatchPartialPrefix() {
		// "org.junitfoo.Bar" must not match because the prefix is "org.junit." with the trailing dot
		assertThat(JDWPTools.isNoiseFrame("org.junitfoo.Bar")).isFalse();
	}

	@Test
	@DisplayName("flags nested classes within noise packages")
	void shouldFlagNestedNoiseClasses() {
		assertThat(JDWPTools.isNoiseFrame("org.junit.jupiter.engine.execution.InvocationInterceptorChain$InterceptedInvocation")).isTrue();
	}
}
