package one.edee.jdwp.sandbox.settings;

import static java.lang.System.Logger.Level.WARNING;

/** Checks the timing configuration for service health probes. */
final class HealthMonitor {

	private static final int POLL_INTERVAL_MILLIS = 30_000;

	void start(final HttpSettings settings) {
		final int timeout = settings.getTimeout();
		if (timeout > POLL_INTERVAL_MILLIS) {
			final System.Logger log = System.getLogger("app.health");
			log.log(WARNING, "Health request timeout exceeds the polling interval");
		}
	}
}
