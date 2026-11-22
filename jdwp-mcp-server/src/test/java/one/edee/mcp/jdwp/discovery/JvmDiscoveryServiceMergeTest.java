package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for the package-private helpers of {@link JvmDiscoveryService}:
 * {@code mergeByPid}, {@code maskCredentials}, and {@code splitNulSeparated}. These run as plain
 * static-method calls — no temp filesystem, no sockets.
 */
@DisplayName("JvmDiscoveryService — package-private helpers")
class JvmDiscoveryServiceMergeTest {

	@Nested
	@DisplayName("mergeByPid")
	class Merge {

		@Test
		@DisplayName("attach-API-only PID is emitted with Source.ATTACH_API")
		void shouldKeepAttachApiOnlyPid() {
			final JvmDescriptor attachOnly = descriptor(101L, JvmDescriptor.Source.ATTACH_API, null);

			final List<JvmDescriptor> result = JvmDiscoveryService.mergeByPid(
				List.of(attachOnly), List.of()
			);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).pid()).isEqualTo(101L);
			assertThat(result.get(0).source()).isEqualTo(JvmDescriptor.Source.ATTACH_API);
		}

		@Test
		@DisplayName("/proc-only PID is emitted with Source.PROC_FS")
		void shouldKeepProcFsOnlyPid() {
			final JvmDescriptor procOnly = descriptor(202L, JvmDescriptor.Source.PROC_FS, "com.proc.Main");

			final List<JvmDescriptor> result = JvmDiscoveryService.mergeByPid(
				List.of(), List.of(procOnly)
			);

			assertThat(result).hasSize(1);
			assertThat(result.get(0).pid()).isEqualTo(202L);
			assertThat(result.get(0).source()).isEqualTo(JvmDescriptor.Source.PROC_FS);
		}

		@Test
		@DisplayName("PID seen by both strategies merges into Source.BOTH, /proc fields win")
		void shouldMergeBothIntoSourceBoth() {
			final JvmDescriptor attach = descriptor(303L, JvmDescriptor.Source.ATTACH_API, "AttachMain");
			final JvmDescriptor proc = new JvmDescriptor(
				303L, "ProcMain", "/opt/jdk", "java -jar x.jar",
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.UNKNOWN),
				false, JvmDescriptor.Source.PROC_FS
			);

			final List<JvmDescriptor> result = JvmDiscoveryService.mergeByPid(List.of(attach), List.of(proc));

			assertThat(result).hasSize(1);
			final JvmDescriptor merged = result.get(0);
			assertThat(merged.source()).isEqualTo(JvmDescriptor.Source.BOTH);
			// /proc wins on conflict — it carries the endpoint and the masked cmdline.
			assertThat(merged.mainClass()).isEqualTo("ProcMain");
			assertThat(merged.javaHome()).isEqualTo("/opt/jdk");
			assertThat(merged.jdwp()).isNotNull();
		}

		@Test
		@DisplayName("attach-API mainClass is borrowed when /proc could not extract one")
		void shouldBorrowAttachApiMainClassWhenProcNull() {
			final JvmDescriptor attach = descriptor(404L, JvmDescriptor.Source.ATTACH_API, "com.example.AttachMain");
			final JvmDescriptor proc = new JvmDescriptor(
				404L, null, "/opt/jdk", "java",
				new JdwpEndpoint("*", 5005, "dt_socket", true, false, JdwpEndpoint.State.UNKNOWN),
				false, JvmDescriptor.Source.PROC_FS
			);

			final List<JvmDescriptor> result = JvmDiscoveryService.mergeByPid(List.of(attach), List.of(proc));

			assertThat(result).hasSize(1);
			// /proc had null mainClass — the merge must reach into the attach-API result to fill it.
			assertThat(result.get(0).mainClass()).isEqualTo("com.example.AttachMain");
			assertThat(result.get(0).source()).isEqualTo(JvmDescriptor.Source.BOTH);
		}

		@Test
		@DisplayName("output preserves the insertion order: attach-API first, then /proc-only")
		void shouldPreserveInsertionOrder() {
			final JvmDescriptor a1 = descriptor(1L, JvmDescriptor.Source.ATTACH_API, "A1");
			final JvmDescriptor a2 = descriptor(2L, JvmDescriptor.Source.ATTACH_API, "A2");
			final JvmDescriptor p3 = descriptor(3L, JvmDescriptor.Source.PROC_FS, "P3");
			final JvmDescriptor p2 = descriptor(2L, JvmDescriptor.Source.PROC_FS, "P2"); // merges with a2

			final List<JvmDescriptor> result = JvmDiscoveryService.mergeByPid(
				List.of(a1, a2), List.of(p3, p2)
			);

			assertThat(result).hasSize(3);
			// Insertion order: 1 (only attach), 2 (merged), 3 (added by proc).
			assertThat(result.get(0).pid()).isEqualTo(1L);
			assertThat(result.get(1).pid()).isEqualTo(2L);
			assertThat(result.get(2).pid()).isEqualTo(3L);
			assertThat(result.get(1).source()).isEqualTo(JvmDescriptor.Source.BOTH);
		}

		private JvmDescriptor descriptor(long pid, JvmDescriptor.Source source, String mainClass) {
			return new JvmDescriptor(pid, mainClass, null, null, null, false, source);
		}
	}

	@Nested
	@DisplayName("maskCredentials")
	class Mask {

		@Test
		@DisplayName("each credential key is masked case-insensitively")
		void shouldMaskAllSupportedKeysCaseInsensitively() {
			final String input = "java -Dpassword=p1 -DSECRET=s1 -DToken=t1 -Dapikey=k1 -Dapi_key=k2";

			final String masked = JvmDiscoveryService.maskCredentials(input);

			assertThat(masked).contains("password=***");
			assertThat(masked).contains("SECRET=***");
			assertThat(masked).contains("Token=***");
			assertThat(masked).contains("apikey=***");
			assertThat(masked).contains("api_key=***");
			assertThat(masked).doesNotContain("p1");
			assertThat(masked).doesNotContain("s1");
			assertThat(masked).doesNotContain("t1");
			assertThat(masked).doesNotContain("k1");
			assertThat(masked).doesNotContain("k2");
		}

		@Test
		@DisplayName("credentials embedded inside longer flags (e.g. -Dauthtoken=) are still masked")
		void shouldMaskWhenKeyIsEmbeddedInLongerFlag() {
			final String input = "java -Dauthtoken=abc123";

			final String masked = JvmDiscoveryService.maskCredentials(input);

			// The regex matches "token=" inside "authtoken=" — masks just that suffix.
			assertThat(masked).contains("token=***");
			assertThat(masked).doesNotContain("abc123");
		}

		@Test
		@DisplayName("hex/punctuation-heavy values are masked entirely up to whitespace")
		void shouldMaskHexAndPunctuationValues() {
			final String input = "java -Dsecret=0xDEADBEEF! -Dapikey=a:b/c?d=e";

			final String masked = JvmDiscoveryService.maskCredentials(input);

			assertThat(masked).contains("secret=***");
			assertThat(masked).contains("apikey=***");
			assertThat(masked).doesNotContain("DEADBEEF");
			assertThat(masked).doesNotContain("a:b/c");
		}

		@Test
		@DisplayName("non-credential 'version=1.2.3' is left untouched")
		void shouldNotMaskUnrelatedKeys() {
			final String input = "java -Dversion=1.2.3 -Dorg=example";

			final String masked = JvmDiscoveryService.maskCredentials(input);

			assertThat(masked).isEqualTo(input);
		}

		@Test
		@DisplayName("multiple credentials in one cmdline are all masked independently")
		void shouldMaskMultipleCredentialsIndependently() {
			final String input = "java -Dpassword=a -Dsecret=b -Dpassword=c -jar x.jar";

			final String masked = JvmDiscoveryService.maskCredentials(input);

			assertThat(masked).doesNotContain("=a");
			assertThat(masked).doesNotContain("=b");
			assertThat(masked).doesNotContain("=c");
			// Cmdline structure preserved.
			assertThat(masked).contains("-jar x.jar");
		}

	}

	@Nested
	@DisplayName("splitNulSeparated")
	class Split {

		@Test
		@DisplayName("NUL-terminated buffer (kernel canonical shape) round-trips")
		void shouldSplitNulTerminatedBuffer() {
			final byte[] buf = "java\0-jar\0/app.jar\0".getBytes();

			final List<String> argv = JvmDiscoveryService.splitNulSeparated(buf);

			assertThat(argv).containsExactly("java", "-jar", "/app.jar");
		}

		@Test
		@DisplayName("missing trailing NUL still emits the final arg")
		void shouldEmitFinalArgWithoutTrailingNul() {
			final byte[] buf = "java\0-jar\0/app.jar".getBytes();

			final List<String> argv = JvmDiscoveryService.splitNulSeparated(buf);

			assertThat(argv).containsExactly("java", "-jar", "/app.jar");
		}

		@Test
		@DisplayName("empty buffer returns an empty list")
		void shouldReturnEmptyListForEmptyBuffer() {
			final List<String> argv = JvmDiscoveryService.splitNulSeparated(new byte[0]);

			assertThat(argv).isEmpty();
		}

		@Test
		@DisplayName("each NUL terminates an argument even when it is empty")
		void shouldKeepEmptyArgumentsFromAllNulBuffer() {
			final List<String> argv = JvmDiscoveryService.splitNulSeparated(new byte[]{0, 0, 0});

			assertThat(argv).containsExactly("", "", "");
		}

		@Test
		@DisplayName("adjacent NULs preserve empty options and their values")
		void shouldKeepEmptyArgumentsBetweenAdjacentNuls() {
			final byte[] buf = "java\0-cp\0\0-jar\0/app.jar\0".getBytes();

			final List<String> argv = JvmDiscoveryService.splitNulSeparated(buf);

			assertThat(argv).containsExactly("java", "-cp", "", "-jar", "/app.jar");
		}
	}
}
