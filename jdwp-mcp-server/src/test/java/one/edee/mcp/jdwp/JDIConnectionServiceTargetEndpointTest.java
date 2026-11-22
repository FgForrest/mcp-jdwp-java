package one.edee.mcp.jdwp;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JDIConnectionServiceTargetEndpointTest {

	@Test
	@DisplayName("named IPv6 zones retain their case in endpoint identity")
	void namedIpv6ZonesAreDistinct() {
		final var upper = new JDIConnectionService.TargetEndpoint("[FE80::1%LinkA]", 5005, false);
		final var lower = new JDIConnectionService.TargetEndpoint("[FE80::1%linka]", 5005, false);

		assertThat(upper).isNotEqualTo(lower);
		assertThat(upper.toString()).isEqualTo("[fe80::1%LinkA]:5005");
		assertThat(upper).isEqualTo(new JDIConnectionService.TargetEndpoint("fe80::1%LinkA", 5005, false));
	}

	@Test
	@DisplayName("SocketAttach gets the selected numeric address and endpoint classification uses that same address")
	void socketAttachUsesTheAddressThatDeterminesEndpointIdentity() throws Exception {
		final AttachingConnector connector = mock(AttachingConnector.class);
		final Connector.Argument hostname = mock(Connector.Argument.class);
		final Connector.Argument port = mock(Connector.Argument.class);
		final Map<String, Connector.Argument> args = Map.of("hostname", hostname, "port", port);
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(connector.defaultArguments()).thenReturn(args);
		when(connector.attach(args)).thenReturn(vm);
		final InetAddress remote = InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1});
		final InetAddress loopback = InetAddress.getLoopbackAddress();

		try (var resolver = mockStatic(InetAddress.class)) {
			resolver.when(() -> InetAddress.getByName("localhost")).thenReturn(remote, loopback);
			final var attached = JDIConnectionService.attachSocket(connector, "localhost", 5005);

			assertThat(attached.vm()).isSameAs(vm);
			assertThat(attached.endpoint()).isEqualTo(new JDIConnectionService.TargetEndpoint("localhost", 5005, false));
			assertThat(attached.endpoint()).isNotEqualTo(new JDIConnectionService.TargetEndpoint("127.0.0.1", 5005, true));
			verify(hostname).setValue("192.0.2.1");
			verify(port).setValue("5005");
			resolver.verify(() -> InetAddress.getByName("localhost"));
		}
	}
}
