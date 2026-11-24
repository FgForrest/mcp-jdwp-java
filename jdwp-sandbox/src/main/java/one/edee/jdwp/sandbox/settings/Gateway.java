package one.edee.jdwp.sandbox.settings;

import static java.lang.System.Logger.Level.DEBUG;

/** Configures the application's HTTP entry point. */
final class Gateway {

	void start(final HttpSettings settings) {
		final System.Logger log = System.getLogger("app.gateway");
		log.log(DEBUG, "Gateway request timeout: {0} ms", settings.getTimeout());
	}
}
