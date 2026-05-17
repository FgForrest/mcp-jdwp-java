package one.edee.mcp.jdwp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the contract around a failed first connect:
 *
 * <ul>
 *   <li>The diagnostic snapshot DOES record the attempted host/port, so diagnostics can render
 *       "tried foo:5005 and got Connection refused" instead of "&lt;unknown&gt;".</li>
 *   <li>The internal reconnect target is NOT seeded until a connect actually succeeds, so a
 *       subsequent {@code getVM()} surfaces a "use jdwp_connect first" hint instead of silently
 *       retrying against the never-valid target.</li>
 * </ul>
 */
class JDIConnectionServiceFailedConnectTest {

	private JDIConnectionService service;

	@BeforeEach
	void setUp() {
		service = JDIConnectionServiceTestSupport.newServiceWithRealListener();
	}

	@Test
	@DisplayName("failed first connect records the attempted target for diagnostics")
	void shouldRecordAttemptedTargetForDiagnosticsAfterFailedConnect() {
		// A closed loopback port produces a deterministic "Connection refused" with no DNS / network.
		assertThatThrownBy(() -> service.connect("127.0.0.1", 1))
			.isInstanceOf(Exception.class);

		final JDIConnectionService.ConnectionStatus status = service.getConnectionStatus();

		assertThat(status.connected()).isFalse();
		assertThat(status.lastHost()).isEqualTo("127.0.0.1");
		assertThat(status.lastPort()).isEqualTo(1);
		assertThat(status.lastConnectError()).isNotNull();
	}

	@Test
	@DisplayName("tool call after a failed first connect surfaces 'Use jdwp_connect first'")
	void shouldRefuseSilentReconnectAfterFailedFirstConnect() {
		assertThatThrownBy(() -> service.connect("127.0.0.1", 1))
			.isInstanceOf(Exception.class);

		assertThatThrownBy(() -> service.getVM())
			.hasMessageContaining("jdwp_connect");
	}
}
