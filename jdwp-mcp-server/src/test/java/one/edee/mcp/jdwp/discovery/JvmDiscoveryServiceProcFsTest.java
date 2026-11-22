package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code /proc/<pid>/cmdline} discovery strategy against a temp directory that
 * mimics a slice of {@code /proc}. Each fake PID directory holds a {@code cmdline} file with
 * NUL-separated args, just like the real kernel layout.
 */
class JvmDiscoveryServiceProcFsTest {

	@Test
	@DisplayName("Java process with -agentlib:jdwp= surfaces a JDWP endpoint")
	void shouldExtractJdwpEndpointFromCmdline(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 1001, "/usr/lib/jvm/jdk-21/bin/java",
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
			"-cp", "/app/classes", "com.example.app.Main", "--port", "8080");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		final JvmDescriptor d = result.get(0);
		assertThat(d.pid()).isEqualTo(1001L);
		assertThat(d.mainClass()).isEqualTo("com.example.app.Main");
		assertThat(d.jdwp()).isNotNull();
		assert d.jdwp() != null;
		assertThat(d.jdwp().port()).isEqualTo(5005);
		assertThat(d.jdwp().suspendOnStart()).isTrue();
		assertThat(d.source()).isEqualTo(JvmDescriptor.Source.PROC_FS);
	}

	@Test
	@DisplayName("Java process without JDWP arg is included with jdwp=null")
	void shouldIncludeJavaProcessWithoutJdwp(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 2002, "/usr/bin/java", "-jar", "/app/server.jar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).jdwp()).isNull();
		assertThat(result.get(0).mainClass()).isEqualTo("/app/server.jar");
	}

	@Test
	@DisplayName("non-Java processes are ignored")
	void shouldSkipNonJavaProcesses(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 3003, "/bin/bash", "-c", "echo hi");
		writeCmdline(tempProc, 3004, "/usr/sbin/nginx");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("non-numeric subdirectories are ignored")
	void shouldIgnoreNonPidDirectories(@TempDir Path tempProc) throws IOException {
		Files.createDirectories(tempProc.resolve("self"));
		Files.createDirectories(tempProc.resolve("cpuinfo"));
		writeCmdline(tempProc, 4004, "/usr/bin/java", "-jar", "/app/x.jar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).pid()).isEqualTo(4004L);
	}

	@Test
	@DisplayName("cmdline credentials are masked")
	void shouldMaskCredentialsInCmdline(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 5005, "/usr/bin/java", "-Dpassword=hunter2", "-Dapi_key=abc", "-jar", "/app/x.jar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		final String masked = result.get(0).maskedCmdline();
		assertThat(masked).isNotNull();
		assert masked != null;
		assertThat(masked).contains("password=***");
		assertThat(masked).contains("api_key=***");
		assertThat(masked).doesNotContain("hunter2");
		assertThat(masked).doesNotContain("abc");
	}

	@Test
	@DisplayName("credential value containing whitespace is fully masked — per-arg, not after String.join")
	void shouldMaskWhitespaceContainingCredentialBeforeJoining(@TempDir Path tempProc) throws IOException {
		// /proc/<pid>/cmdline preserves args verbatim — internal whitespace in a value is part
		// of one argv token. Masking must therefore run per-arg, before the args are joined
		// for display; otherwise the regex's \S+ stops at the embedded space and exposes the tail.
		writeCmdline(tempProc, 5010, "/usr/bin/java", "-Dpassword=foo bar", "-jar", "/x.jar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		final String masked = result.get(0).maskedCmdline();
		assertThat(masked).isNotNull();
		assert masked != null;
		assertThat(masked).contains("password=***");
		assertThat(masked).doesNotContain("foo");
		assertThat(masked).doesNotContain("bar");
	}

	@Test
	@DisplayName("missing /proc directory returns empty list, no exception")
	void shouldHandleMissingProcDirectory(@TempDir Path tempProc) {
		final Path missing = tempProc.resolve("does-not-exist");
		final List<JvmDescriptor> result = newServiceWithProcRoot(missing).discover();
		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("attach-API and /proc results merge by PID (Source.BOTH)")
	void shouldMergeAttachAndProcFsByPid(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 6006, "/usr/bin/java",
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005",
			"-cp", "/app", "com.example.Main");

		final JvmDiscoveryService service = new JvmDiscoveryService(
			() -> List.of(new JvmDiscoveryService.AttachedJvm(6006L, "com.example.Main")),
			999L,
			tempProc,
			true
		);

		final List<JvmDescriptor> result = service.discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).source()).isEqualTo(JvmDescriptor.Source.BOTH);
		assertThat(result.get(0).jdwp()).isNotNull();
	}

	@Test
	@DisplayName("two-token JVM options are skipped before the module selector")
	void shouldSkipTwoTokenJvmOptionsBeforeModuleSelector(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 7001, "/usr/bin/java",
			"-cp", "/cp/app.jar:/cp/lib.jar",
			"--module-path", "/modules",
			"-p", "/more-modules",
			"--add-modules", "java.base,java.sql",
			"--patch-module", "java.base=/patches/base.jar",
			"-m", "com.example/com.example.Bootstrap", "--password", "app-argument");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).mainClass()).isEqualTo("com.example/com.example.Bootstrap");
	}

	@Test
	@DisplayName("module selectors end JVM options in both separated and equals forms")
	void shouldStopAtLongModuleSelector(@TempDir Path tempProc) throws IOException {
		final String applicationArg = "-Xrunjdwp:transport=dt_socket,server=y,address=5005";
		writeCmdline(tempProc, 7011, "java", "--module", "example/example.Main", applicationArg);
		writeCmdline(tempProc, 7012, "java", "--module=example/example.Main", applicationArg);
		writeCmdline(tempProc, 7013, "java", "example.Main", applicationArg);

		assertThat(newServiceWithProcRoot(tempProc).discover())
			.allSatisfy(jvm -> assertThat(jvm.jdwp()).isNull())
			.extracting(JvmDescriptor::mainClass)
			.containsExactlyInAnyOrder("example/example.Main", "example/example.Main", "example.Main");
	}

	@Test
	@DisplayName("-jar after leading JVM flags returns the JAR path")
	void shouldReturnJarPathFromDashJar(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 7002, "/usr/bin/java",
			"-Xmx512m", "-Dfoo=bar",
			"-cp", "/cp",
			"-jar", "/app/server.jar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).mainClass()).isEqualTo("/app/server.jar");
	}

	@Test
	@DisplayName("argv with only JVM options (no main class) returns null mainClass")
	void shouldReturnNullMainClassWhenArgvHasOnlyJvmOptions(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 7003, "/usr/bin/java", "-Xmx512m", "-Dfoo=bar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).mainClass()).isNull();
	}

	@Test
	@DisplayName("mvn-like launcher with -agentlib:jdwp= is recognised as a Java process")
	void shouldRecogniseMvnLauncherViaAgentArg(@TempDir Path tempProc) throws IOException {
		writeCmdline(tempProc, 7004, "/usr/bin/mvn",
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
			"test");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		// The executable basename isn't 'java', so it's only the agent flag that classifies this
		// as a Java process. The endpoint must still be extracted.
		assertThat(result).hasSize(1);
		assertThat(result.get(0).jdwp()).isNotNull();
		assert result.get(0).jdwp() != null;
		assertThat(result.get(0).jdwp().port()).isEqualTo(5005);
	}

	@Test
	@DisplayName("missing or zero-byte cmdline is silently skipped")
	void shouldSkipMissingAndEmptyCmdlines(@TempDir Path tempProc) throws IOException {
		// PID dir with no cmdline file at all (race with process exit, hidepid).
		Files.createDirectories(tempProc.resolve("7100"));
		// PID dir with a zero-byte cmdline (kernel returns 0 bytes for kernel threads).
		final Path zeroByteDir = tempProc.resolve("7101");
		Files.createDirectories(zeroByteDir);
		Files.write(zeroByteDir.resolve("cmdline"), new byte[0]);
		// A real Java process so the test proves discovery completes and emits exactly that one.
		writeCmdline(tempProc, 7102, "/usr/bin/java", "-jar", "/x.jar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).pid()).isEqualTo(7102L);
	}

	@Test
	@DisplayName("non-Java sibling plus a NUL-only cmdline still yields exactly one Java entry")
	void shouldYieldExactlyOneJavaEntryAmongNoise(@TempDir Path tempProc) throws IOException {
		// Non-Java process — must be filtered out by the executable-name check.
		writeCmdline(tempProc, 7200, "/bin/sleep", "999");
		// NUL-only cmdline — splitNulSeparated returns empty, readProcEntry returns null.
		final Path nulOnly = tempProc.resolve("7201");
		Files.createDirectories(nulOnly);
		Files.write(nulOnly.resolve("cmdline"), new byte[]{0, 0, 0});
		// The one genuine Java process.
		writeCmdline(tempProc, 7202, "/usr/bin/java", "-jar", "/x.jar");

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).pid()).isEqualTo(7202L);
	}

	@Test
	@DisplayName("resolveJavaHome reads /proc/<pid>/exe symlink when present")
	void shouldResolveJavaHomeViaExeSymlink(@TempDir Path tempProc) throws IOException {
		// resolveJavaHome calls Files.readSymbolicLink which only works on real symlink-capable
		// filesystems. Skip on platforms where symlinks aren't supported in the temp area.
		Assumptions.assumeTrue(
			System.getProperty("os.name", "").toLowerCase().contains("linux"),
			"symlink-backed JAVA_HOME resolution only relevant on Linux"
		);

		final Path pidDir = tempProc.resolve("7300");
		Files.createDirectories(pidDir);
		Files.write(pidDir.resolve("cmdline"), "/opt/jdk-21/bin/java\0-jar\0/x.jar\0".getBytes());

		// Simulate /proc/<pid>/exe → /opt/jdk-21/bin/java; we don't need the link target to exist.
		final Path target = tempProc.resolve("opt").resolve("jdk-21").resolve("bin").resolve("java");
		try {
			Files.createDirectories(target.getParent());
			Files.createSymbolicLink(pidDir.resolve("exe"), target);
		} catch (UnsupportedOperationException | IOException e) {
			Assumptions.abort("Symbolic links are not supported in this temp directory: " + e.getMessage());
		}

		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).hasSize(1);
		// JAVA_HOME is the grandparent of the executable (.../bin/java → .../).
		assertThat(result.get(0).javaHome()).endsWith("/opt/jdk-21");
	}

	@Test
	@DisplayName("empty procRoot (no PID subdirs) returns an empty list, no exception")
	void shouldReturnEmptyForEmptyProcRoot(@TempDir Path tempProc) {
		final List<JvmDescriptor> result = newServiceWithProcRoot(tempProc).discover();

		assertThat(result).isEmpty();
	}

	private static JvmDiscoveryService newServiceWithProcRoot(Path procRoot) {
		return new JvmDiscoveryService(List::of, 999L, procRoot, true);
	}

	private static void writeCmdline(Path procRoot, long pid, String... argv) throws IOException {
		final Path pidDir = procRoot.resolve(String.valueOf(pid));
		Files.createDirectories(pidDir);
		// /proc/<pid>/cmdline is NUL-separated, NUL-terminated.
		final StringBuilder sb = new StringBuilder();
		for (String a : argv) {
			sb.append(a).append('\0');
		}
		Files.write(pidDir.resolve("cmdline"), sb.toString().getBytes());
	}
}
