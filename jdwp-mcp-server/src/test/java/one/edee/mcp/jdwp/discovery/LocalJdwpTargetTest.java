package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
	void remoteHostSkipsLocalProcessMatching() throws Exception {
		final JvmDiscoveryService discovery = mock(JvmDiscoveryService.class);

		assertThat(LocalJdwpTarget.identify(discovery, emptyProc, "192.0.2.1", 5005).describe()).isEmpty();
		verifyNoInteractions(discovery);
	}

	private static JvmDescriptor descriptor(long pid, String main, String bind, boolean serverMode) {
		return new JvmDescriptor(pid, main, null, null,
			new JdwpEndpoint(bind, 5005, "dt_socket", serverMode, true, JdwpEndpoint.State.UNKNOWN),
			false, JvmDescriptor.Source.PROC_FS);
	}
}
