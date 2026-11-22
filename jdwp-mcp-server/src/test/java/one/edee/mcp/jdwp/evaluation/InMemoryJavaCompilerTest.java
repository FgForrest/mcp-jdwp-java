package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.TestReflectionUtils;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

		/** Records ECJ's problem codes and exact source spans for the forms used in arming expressions. */
		@ParameterizedTest
		@CsvSource(delimiter = '|', textBlock = """
			unknownLocal | 33554515 | unknownLocal
			Missing.VALUE | 33554515 | Missing
			com.acme.Missing.VALUE | 33554515 | com.acme
			new Missing() | 16777218 | Missing
			missing.call() | 570425394 | missing
			""")
		void ecjReportsMissingNameRanges(String expression, String code, String problemText, @TempDir Path project)
			throws Exception {
			final String source = "public class Probe { Object read() { return " + expression + "; } }";
			final Path file = Files.writeString(project.resolve("Probe.java"), source);
			final EclipseCompiler ecj = new EclipseCompiler();
			final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
			try (StandardJavaFileManager files = ecj.getStandardFileManager(diagnostics, null, null)) {
				assertThat(ecj.getTask(null, files, diagnostics, List.of("--release", "17"), null,
					files.getJavaFileObjects(file.toFile())).call()).isFalse();
			}
			assertThat(diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR))
				.singleElement().satisfies(problem -> {
					final int start = (int) problem.getStartPosition();
					final int end = (int) problem.getEndPosition();
					assertThat(List.of(problem.getCode(), source.substring(start, end + 1)))
						.containsExactly(code, problemText);
					assertThat(start).isEqualTo(source.indexOf(expression) + expression.indexOf(problemText));
				});
		}

		/** Checks that a simple unresolved value stays permanent and other missing-type errors still qualify. */
		@ParameterizedTest
		@CsvSource(delimiter = '|', textBlock = """
			return unknownLocal; | false
			return unknownLocal /* comment. */; | false
			return Missing.VALUE; | true
			return Missing /* comment */ .VALUE; | true
			return Missing\\u002eVALUE; | true
			return com.acme.Missing.VALUE; | true
			return new Missing(); | true
			return missing.call(); | true
			Object value = unknownLocal; return new Missing(); | true
			""")
		void classifiesMissingTypesSeparatelyFromSimpleUnresolvedValues(String body, boolean missingType) {
			compiler.configure(System.getProperty("java.home"), "", 17);
			final String source = "public class Probe { Object read() { " + body + " } }";
			final Class<? extends JdiEvaluationException> expected = missingType
				? InMemoryJavaCompiler.MissingClasspathTypes.class : JdiEvaluationException.class;
			assertThatThrownBy(() -> compiler.compile("Probe", source)).isExactlyInstanceOf(expected);
		}

		/**
		 * U+1C90 became an identifier letter after Java 8. The classifier scans at the compile's source level, so a
		 * misspelled local that contains it stays a permanent error instead of a missing classpath type.
		 */
		@Test
		void classifiesAnIdentifierOfTheCompileSourceLevelAsAnUnresolvedValue() {
			compiler.configure(System.getProperty("java.home"), "", Runtime.version().feature());
			final String source = "public class Probe { Object read() { return missᲐngLocal; } }";
			assertThatThrownBy(() -> compiler.compile("Probe", source)).isExactlyInstanceOf(JdiEvaluationException.class);
		}

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

		/** Classifies ECJ's indirect missing-type diagnostic so fast probes can retry. */
		@Test
		void shouldRecognizeMissingIndirectType(@TempDir Path project) throws Exception {
			final Path remote = Files.createDirectory(project.resolve("remote"));
			final Path localPackage = Files.createDirectories(project.resolve("local/fixtures"));
			final Path base = Files.writeString(project.resolve("Base.java"),
				"package fixtures; public class Base { public int value() { return 42; } }");
			final Path derived = Files.writeString(project.resolve("Derived.java"),
				"package fixtures; public class Derived extends Base {}");
			assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17",
				"-d", remote.toString(), base.toString(), derived.toString())).isZero();
			Files.move(remote.resolve("fixtures/Base.class"), localPackage.resolve("Base.class"));
			final String source = "public class Probe { int read() { return new fixtures.Derived().value(); } }";
			final String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, remote.toString(), 17);

			assertThatThrownBy(() -> compiler.compile("Probe", source))
				.isInstanceOf(InMemoryJavaCompiler.MissingClasspathTypes.class)
				.hasMessageContaining("indirectly referenced");
			compiler.configure(jdkHome, remote + File.pathSeparator + localPackage.getParent(), 17);
			assertThat(compiler.compile("Probe", source)).containsKey("Probe");
		}
	}
}
