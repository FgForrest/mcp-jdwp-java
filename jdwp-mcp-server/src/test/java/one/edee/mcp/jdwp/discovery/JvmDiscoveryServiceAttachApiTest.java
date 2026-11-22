package one.edee.mcp.jdwp.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the attach-API discovery strategy using the package-private constructor seam to
 * inject a deterministic JVM list — avoids static-mocking the JDK's {@code VirtualMachine}
 * class, which is fragile across JDK versions.
 */
class JvmDiscoveryServiceAttachApiTest {

	@Test
	@DisplayName("each attached JVM yields a descriptor with a PID and a safe launch summary")
	void shouldEmitOneDescriptorPerAttachedJvm() {
		final JvmDiscoveryService service = new JvmDiscoveryService(
			() -> List.of(
				new JvmDiscoveryService.AttachedJvm(101L, "com.example.app.Main"),
				new JvmDiscoveryService.AttachedJvm(202L, "org.example.Other arg1 arg2")
			),
			999L
		);

		final List<JvmDescriptor> result = service.discover();

		assertThat(result).hasSize(2);
		assertThat(result.get(0).pid()).isEqualTo(101L);
		assertThat(result.get(0).mainClass()).isEqualTo("com.example.app.Main");
		assertThat(result.get(0).source()).isEqualTo(JvmDescriptor.Source.ATTACH_API);
		assertThat(result.get(0).isThisProcess()).isFalse();
		assertThat(result.get(0).jdwp()).isNull();
		assertThat(result.get(1).pid()).isEqualTo(202L);
		assertThat(result.get(1).mainClass()).isNull();
	}

	@Test
	@DisplayName("empty display name falls back to null main class")
	void shouldFallBackToNullMainClassWhenDisplayNameIsBlank() {
		final JvmDiscoveryService service = new JvmDiscoveryService(
			() -> List.of(new JvmDiscoveryService.AttachedJvm(123L, "")),
			999L
		);

		final List<JvmDescriptor> result = service.discover();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).mainClass()).isNull();
	}

	@Test
	@DisplayName("self PID is flagged via isThisProcess")
	void shouldFlagOwnProcess() {
		final long selfPid = 42L;
		final JvmDiscoveryService service = new JvmDiscoveryService(
			() -> List.of(
				new JvmDiscoveryService.AttachedJvm(selfPid, "JdwpMcpServerApplication"),
				new JvmDiscoveryService.AttachedJvm(43L, "com.example.Other")
			),
			selfPid
		);

		final List<JvmDescriptor> result = service.discover();

		assertThat(result.get(0).isThisProcess()).isTrue();
		assertThat(result.get(1).isThisProcess()).isFalse();
	}

	@Test
	@DisplayName("supplier throwing returns empty list instead of propagating")
	void shouldReturnEmptyListWhenSupplierThrows() {
		final JvmDiscoveryService service = new JvmDiscoveryService(
			() -> { throw new RuntimeException("simulated attach failure"); },
			999L
		);

		final List<JvmDescriptor> result = service.discover();

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("real VirtualMachine.list() at least sees this JVM and never throws")
	void realAttachApiCallShouldSeeSelf() {
		final JvmDiscoveryService service = new JvmDiscoveryService();

		final List<JvmDescriptor> result = service.discover();

		// We cannot assume the exact contents (other JVMs may be running on dev boxes), but the
		// current JVM must always be visible to itself, and the THIS-PROCESS marker must be set
		// on exactly one entry.
		assertThat(result).isNotNull();
		final long ourPid = ProcessHandle.current().pid();
		assertThat(result).anySatisfy(d -> {
			assertThat(d.pid()).isEqualTo(ourPid);
			assertThat(d.isThisProcess()).isTrue();
		});
	}
}
