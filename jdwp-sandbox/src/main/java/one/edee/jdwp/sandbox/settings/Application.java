package one.edee.jdwp.sandbox.settings;

import java.util.Properties;

/** Starts the components that use the application's HTTP configuration. */
public final class Application {

	/** Starts the application and returns its HTTP settings. */
	public HttpSettings start() {
		final Properties properties = PropertyFiles.load("application.properties");
		final HttpSettings settings = new HttpSettings();
		settings.configure(properties);

		new Gateway().start(settings);
		new CatalogClient().start(settings);
		new MetricsReporter().start(settings);
		new HealthMonitor().start(settings);
		new WebhookClient().start(settings);

		return settings;
	}
}
