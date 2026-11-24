package one.edee.jdwp.sandbox.settings;

import java.time.Duration;

import static java.lang.System.Logger.Level.DEBUG;

/** Configures connections to the product catalog. */
final class CatalogClient {

	void start(final HttpSettings settings) {
		final Duration timeout = Duration.ofMillis(settings.getTimeout());
		final System.Logger log = System.getLogger("app.catalog");
		log.log(DEBUG, "Catalog connection timeout: {0}", timeout);
	}
}
