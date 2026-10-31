package one.edee.jdwp.sandbox.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationProviderTest {

	@Test
	void shouldRetainInitializedTimeout() throws Exception {
		ConfigurationProvider provider = new ConfigurationProvider();

		// Run the maintenance sweep (background thread). It should leave a live config untouched.
		provider.runMaintenanceSweep();

		// Fails: the timeout is 0, not 5000.
		assertThat(provider.getConfig().getTimeout())
			.describedAs("Timeout set during construction should survive the maintenance sweep")
			.isEqualTo(5000);
	}
}
