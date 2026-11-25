package one.edee.jdwp.sandbox.client;

import java.net.URI;
import java.util.Map;
import java.util.function.IntSupplier;

/** Dispatches HTTP requests to in-process route handlers. */
public final class HttpRouter {

	private final Map<String, IntSupplier> routes = Map.of("/orders", () -> 200);

	/** Returns the status from the matching GET handler. */
	public int get(final String url) {
		final String path = URI.create(url).getPath();
		final IntSupplier handler = routes.get(path);
		return handler == null ? 404 : handler.getAsInt();
	}
}
