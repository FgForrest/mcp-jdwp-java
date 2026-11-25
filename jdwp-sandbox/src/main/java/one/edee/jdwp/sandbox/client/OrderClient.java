package one.edee.jdwp.sandbox.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Client for the order listing endpoint. */
public final class OrderClient {

	private final HttpRouter router;
	private final String baseUrl;

	/** Loads the client configuration and connects to the supplied router. */
	public OrderClient(final HttpRouter router) {
		this.router = router;
		this.baseUrl = loadBaseUrl();
	}

	/** Requests the order listing and returns the HTTP status. */
	public int listOrders() {
		final String url = baseUrl + "/orders";
		return router.get(url);
	}

	private String loadBaseUrl() {
		final Properties properties = new Properties();
		try (final InputStream source = OrderClient.class.getResourceAsStream("client.properties")) {
			if (source == null) {
				throw new IllegalStateException("Order client configuration is missing");
			}
			properties.load(source);
		} catch (final IOException e) {
			throw new UncheckedIOException("Could not read the order client configuration", e);
		}
		return properties.getProperty("base.url");
	}
}
