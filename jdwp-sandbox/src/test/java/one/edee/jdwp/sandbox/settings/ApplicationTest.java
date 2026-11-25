package one.edee.jdwp.sandbox.settings;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTest {

	@Test
	void shouldUseConfiguredHttpTimeout() {
		final Application application = new Application();
		final HttpSettings settings = application.start();

		assertThat(settings.getTimeout())
			.describedAs("HTTP timeout after application startup in milliseconds")
			.isEqualTo(5000);
	}
}
