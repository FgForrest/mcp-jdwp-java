package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalJdwpTargetTest {

	@TempDir
	Path emptyProc;

	@Test
	void reportsAllCandidatesWhenOnlyPortMatchingIsAvailable() throws Exception {
		final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);
		when(discovery.discover()).thenReturn(List.of(
			descriptor(1234, "example.First", "127.0.0.1", true),
			descriptor(5678, "example.Second", "*", true),
			descriptor(3210, "example.Client", "127.0.0.1", false),
			descriptor(8765, "example.OffHost", "192.0.2.1", true)));

		final LocalJdwpTarget target = LocalJdwpTarget.identify(discovery, emptyProc, "localhost", 5005);

		assertThat(target.describeAfterDisconnect(5005))
			.contains("ambiguous", "PID 1234", "example.First", "PID 5678", "example.Second")
			.doesNotContain("example.Client", "example.OffHost", "still alive");
	}

	@Test
	void skipsJdwpFlagsPassedToTheApplication() throws Exception {
		final String applicationArg = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005";
		writeCmdline(42424, "java", "-jar", "helper.jar", applicationArg);
		writeCmdline(42426, "java", "-cp", "", "helper.Main", applicationArg);
		// An argument file used as the classpath operand can supply the main class too.
		writeCmdline(42428, "java", "-cp", "@classpath-and-main.args", applicationArg);
		final JvmDiscoveryService discovery = new JvmDiscoveryService(List::of, -1, emptyProc, true);

		assertThat(LocalJdwpTarget.identify(discovery, emptyProc, "127.0.0.1", 5005).describe()).isEmpty();
	}

	@Test
	void showsTheModuleSelectorWithoutItsApplicationArguments() throws Exception {
		writeCmdline(42425, "java",
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005",
			"-m", "example.module/example.Main", "--password", "dummy-sensitive-argument");
		final JvmDiscoveryService discovery = new JvmDiscoveryService(List::of, -1, emptyProc, true);

		assertThat(LocalJdwpTarget.identify(discovery, emptyProc, "127.0.0.1", 5005).describe())
			.contains("probably PID 42425", "example.module/example.Main")
			.doesNotContain("--password", "dummy-sensitive-argument");
	}

	@Test
	void anUnverifiedLiveCandidatePromptsAPortOwnerCheck() throws Exception {
		final long pid = ProcessHandle.current().pid();
		final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);
		when(discovery.discover()).thenReturn(List.of(descriptor(pid, "example.Main", "*", true)));

		assertThat(LocalJdwpTarget.identify(discovery, emptyProc, "localhost", 5005).describeAfterDisconnect(5005))
			.contains("probably PID " + pid, "ss -ltnp", "lsof", "5005")
			.doesNotContain("still alive", "kill ", "cannot bind");
	}

	@Test
	void anOpaqueArgumentFileDoesNotExposeTheRestOfTheCommand() throws Exception {
		writeCmdline(42427, "java",
			"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005",
			"@launch.args", "--password", "dummy-sensitive-argument");
		final JvmDiscoveryService discovery = new JvmDiscoveryService(List::of, -1, emptyProc, true);

		assertThat(LocalJdwpTarget.identify(discovery, emptyProc, "127.0.0.1", 5005).describe())
			.contains("probably PID 42427")
			.doesNotContain("launch.args", "--password", "dummy-sensitive-argument");
	}

	private void writeCmdline(long pid, String... argv) throws Exception {
		final Path process = Files.createDirectory(emptyProc.resolve(Long.toString(pid)));
		Files.writeString(process.resolve("cmdline"), String.join("\0", argv) + '\0');
	}

	/** Supplies a listener lookup after detaching from a local endpoint without any PID evidence. */
	@Test
	void unidentifiedLocalTargetStillPromptsAPortOwnerCheck() throws Exception {
		final JvmDiscoveryService discovery = new JvmDiscoveryService(List::of, -1, emptyProc, true);
		final LocalJdwpTarget target = LocalJdwpTarget.identify(discovery, emptyProc, "127.0.0.1", 5005);

		assertThat(target.describeAfterDisconnect(5005)).contains("ss -ltnp", "lsof", "5005")
			.doesNotContain("PID ", "kill ");
	}

	/** Remote attachments carry no local listener lookup advice. */
	@Test
	void remoteHostSkipsLocalProcessMatching() throws Exception {
		final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);

		assertThat(LocalJdwpTarget.identify(discovery, emptyProc, "192.0.2.1", 5005)
			.describeAfterDisconnect(5005)).isEmpty();
		verifyNoInteractions(discovery);
	}

	private static JvmDescriptor descriptor(long pid, String main, String bind, boolean serverMode) {
		return new JvmDescriptor(pid, main, null, null,
			new JdwpEndpoint(bind, 5005, "dt_socket", serverMode, true, JdwpEndpoint.State.UNKNOWN),
			false, JvmDescriptor.Source.PROC_FS);
	}
}
