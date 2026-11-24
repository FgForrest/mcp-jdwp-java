package one.edee.jdwp.sandbox.settings;

import static java.lang.System.Logger.Level.DEBUG;

/** Configures outbound webhook delivery. */
final class WebhookClient {

	private static final int DELIVERY_ATTEMPTS = 3;

	void start(final HttpSettings settings) {
		final long deliveryBudgetMillis = (long) settings.getTimeout() * DELIVERY_ATTEMPTS;
		final System.Logger log = System.getLogger("app.webhook");
		log.log(DEBUG, "Webhook delivery budget across all attempts: {0} ms", deliveryBudgetMillis);
	}
}
