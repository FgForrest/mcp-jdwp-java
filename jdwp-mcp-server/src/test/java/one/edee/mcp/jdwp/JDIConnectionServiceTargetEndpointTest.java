package one.edee.mcp.jdwp;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JDIConnectionServiceTargetEndpointTest {

	/** IPv6 normalization preserves the case of interface names. */
	@Test
	@DisplayName("named IPv6 zones retain their case in endpoint identity")
	void namedIpv6ZonesAreDistinct() {
		final JDIConnectionService.TargetEndpoint upper = new JDIConnectionService.TargetEndpoint("[FE80::1%LinkA]", 5005, false);
		final JDIConnectionService.TargetEndpoint lower = new JDIConnectionService.TargetEndpoint("[FE80::1%linka]", 5005, false);

		assertThat(upper).isNotEqualTo(lower);
		assertThat(upper.toString()).isEqualTo("[fe80::1%LinkA]:5005");
		assertThat(upper).isEqualTo(new JDIConnectionService.TargetEndpoint("fe80::1%LinkA", 5005, false));
	}

	/** The same selected address drives the socket and the loopback classification. */
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

		try (MockedStatic<InetAddress> resolver = mockStatic(InetAddress.class)) {
			resolver.when(() -> InetAddress.getByName("localhost")).thenReturn(remote, loopback);
			final JDIConnectionService.SocketAttachment attached =
				JDIConnectionService.attachSocket(connector, "localhost", 5005);

			assertThat(attached.vm()).isSameAs(vm);
			assertThat(attached.endpoint()).isEqualTo(new JDIConnectionService.TargetEndpoint("localhost", 5005, false));
			assertThat(attached.endpoint()).isNotEqualTo(new JDIConnectionService.TargetEndpoint("127.0.0.1", 5005, true));
			verify(hostname).setValue("192.0.2.1");
			verify(port).setValue("5005");
			resolver.verify(() -> InetAddress.getByName("localhost"));
		}
	}

	/** Brackets are transport syntax, while the scope remains part of a scoped IPv6 literal. */
	@ParameterizedTest
	@ValueSource(strings = {"[127.0.0.1]", "[fe80::1%3]"})
	@DisplayName("the connector sees unbracketed IPv4 and scoped IPv6 addresses")
	void bracketedHostReachesConnector(String host) throws Exception {
		final AttachingConnector connector = mock(AttachingConnector.class);
		final Connector.Argument hostname = mock(Connector.Argument.class);
		final Map<String, Connector.Argument> args = Map.of("hostname", hostname, "port", mock(Connector.Argument.class));
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(connector.defaultArguments()).thenReturn(args);
		when(connector.attach(args)).thenReturn(vm);

		final String literal = host.substring(1, host.length() - 1);
		final InetAddress address = InetAddress.getByName(literal);
		final JDIConnectionService.SocketAttachment attached = JDIConnectionService.attachSocket(connector, host, 5005);

		assertThat(attached.vm()).isSameAs(vm);
		assertThat(attached.endpoint())
			.isEqualTo(new JDIConnectionService.TargetEndpoint(literal, 5005, address.isLoopbackAddress()));
		verify(hostname).setValue(address.getHostAddress());
	}

	/** A wildcard is replaced before the socket and the approval key use its effective address. */
	@ParameterizedTest
	@CsvSource({"0.0.0.0, false", "::, false", "0.0.0.0, true"})
	@DisplayName("wildcard destinations are replaced by the address the socket dials")
	void wildcardDestinationUsesEffectiveAddress(String host, boolean localHostnameIsWildcard) throws Exception {
		final AttachingConnector connector = mock(AttachingConnector.class);
		final VirtualMachine vm = mock(VirtualMachine.class);
		final Map<String, Connector.Argument> args = Bootstrap.virtualMachineManager().attachingConnectors().stream()
			.filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
			.findFirst().orElseThrow().defaultArguments();
		when(connector.defaultArguments()).thenReturn(args);
		final InetAddress loopback = InetAddress.getByName("127.0.0.1");
		final InetAddress local = localHostnameIsWildcard ? InetAddress.getByName(host) : loopback;
		try (ServerSocket listener = new ServerSocket(0, 1, loopback);
		     MockedStatic<InetAddress> resolver = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
			resolver.when(InetAddress::getLocalHost).thenReturn(local);
			when(connector.attach(args)).thenAnswer(invocation -> {
				try (Socket socket = new Socket()) {
					socket.connect(new InetSocketAddress(args.get("hostname").value(), listener.getLocalPort()), 2000);
					assertThat(socket.getInetAddress()).isEqualTo(loopback);
				}
				return vm;
			});

			final JDIConnectionService.SocketAttachment attached =
				JDIConnectionService.attachSocket(connector, host, listener.getLocalPort());

			assertThat(attached.endpoint())
				.isEqualTo(new JDIConnectionService.TargetEndpoint("localhost", listener.getLocalPort(), true));
			assertThat(args.get("hostname").value()).isEqualTo("127.0.0.1");
		}
	}

	/** The key changes when a wildcard resolves to a different non-loopback local address. */
	@Test
	@DisplayName("a wildcard's non-loopback destination contributes its numeric address to the key")
	void wildcardKeyUsesNonLoopbackDestination() throws Exception {
		final AttachingConnector connector = mock(AttachingConnector.class);
		final Connector.Argument hostname = mock(Connector.Argument.class);
		final Map<String, Connector.Argument> args = Map.of("hostname", hostname, "port", mock(Connector.Argument.class));
		when(connector.defaultArguments()).thenReturn(args);
		final VirtualMachine vm = mock(VirtualMachine.class);
		when(connector.attach(args)).thenReturn(vm);
		final InetAddress first = InetAddress.getByName("192.0.2.1");
		final InetAddress second = InetAddress.getByName("192.0.2.2");
		try (MockedStatic<InetAddress> resolver = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
			resolver.when(InetAddress::getLocalHost).thenReturn(first, second);
			assertThat(JDIConnectionService.attachSocket(connector, "0.0.0.0", 5005).endpoint())
				.isEqualTo(new JDIConnectionService.TargetEndpoint("192.0.2.1", 5005, false));
			assertThat(JDIConnectionService.attachSocket(connector, "0.0.0.0", 5005).endpoint())
				.isEqualTo(new JDIConnectionService.TargetEndpoint("192.0.2.2", 5005, false));
			verify(hostname).setValue("192.0.2.1");
			verify(hostname).setValue("192.0.2.2");
		}
	}
}
