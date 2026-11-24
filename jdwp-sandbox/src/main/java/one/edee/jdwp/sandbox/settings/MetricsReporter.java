package one.edee.jdwp.sandbox.settings;

import java.util.Properties;

import static java.lang.System.Logger.Level.DEBUG;

/** Configures the export of service metrics to a collector. */
final class MetricsReporter {

	void start(final HttpSettings settings) {
		final Properties properties = PropertyFiles.load("metrics.properties");
		settings.configure(properties);
		final String collector = properties.getProperty("metrics.collector");
		final System.Logger log = System.getLogger("app.metrics");
		log.log(DEBUG, "Metrics collector {0} uses a timeout of {1} ms", collector, settings.getTimeout());
	}
}
