package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.TestReflectionUtils;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryJavaCompilerTest {

	private InMemoryJavaCompiler compiler;

	@BeforeEach
	void setUp() {
		compiler = new InMemoryJavaCompiler();
	}

	@Nested
	class PackageAndClassName {

		@Test
		void shouldSplitFullyQualifiedClassName() throws Exception {
			String[] result = TestReflectionUtils.invokePrivate(
				compiler, "getPackageAndClassName",
				new Class[]{String.class}, "com.example.Foo"
			);
			assertThat(result[0]).isEqualTo("com.example");
			assertThat(result[1]).isEqualTo("Foo");
		}

		@Test
		void shouldHandleDefaultPackage() throws Exception {
			String[] result = TestReflectionUtils.invokePrivate(
				compiler, "getPackageAndClassName",
				new Class[]{String.class}, "Foo"
			);
			assertThat(result[0]).isNull();
			assertThat(result[1]).isEqualTo("Foo");
		}

		@Test
		void shouldHandleDeeplyNestedPackage() throws Exception {
			String[] result = TestReflectionUtils.invokePrivate(
				compiler, "getPackageAndClassName",
				new Class[]{String.class}, "io.mcp.jdwp.evaluation.Foo"
			);
			assertThat(result[0]).isEqualTo("io.mcp.jdwp.evaluation");
			assertThat(result[1]).isEqualTo("Foo");
		}
	}

	@Nested
	class Compilation {

		@Test
		void shouldCompileSimpleClass() throws JdiEvaluationException {
			String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "", 17);

			String source = "public class SimpleTest { public static String run() { return \"hello\"; } }";
			Map<String, byte[]> result = compiler.compile("SimpleTest", source);

			assertThat(result).isNotEmpty();
			assertThat(result).containsKey("SimpleTest");
			// Verify it's valid bytecode — class files start with 0xCAFEBABE
			byte[] bytecode = result.get("SimpleTest");
			assertThat(bytecode.length).isGreaterThan(4);
			assertThat(bytecode[0]).isEqualTo((byte) 0xCA);
			assertThat(bytecode[1]).isEqualTo((byte) 0xFE);
			assertThat(bytecode[2]).isEqualTo((byte) 0xBA);
			assertThat(bytecode[3]).isEqualTo((byte) 0xBE);
		}

		@Test
		void shouldReportCompilationErrors() {
			String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "", 17);

			String badSource = "public class BadClass { public void x() { undeclaredMethod(); } }";

			assertThatThrownBy(() -> compiler.compile("BadClass", badSource))
				.isInstanceOf(JdiEvaluationException.class)
				.hasMessageContaining("Compilation failed")
				// The offending line, not the temporary file the source was written to.
				.hasMessageContaining("Line 1 (`public class BadClass")
				.hasMessageNotContaining("mcp-compiler-")
				.satisfies(e -> assertThat(e.getMessage().split("undeclaredMethod", -1)).as("listed once")
					.hasSizeLessThanOrEqualTo(3));
		}

		@Test
		void shouldThrowWhenNotConfigured() {
			assertThatThrownBy(() -> compiler.compile("Foo", "public class Foo {}"))
				.isInstanceOf(JdiEvaluationException.class)
				.hasMessageContaining("not configured");
		}
	}
}
