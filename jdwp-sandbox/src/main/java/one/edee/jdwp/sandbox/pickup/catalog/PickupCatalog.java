package one.edee.jdwp.sandbox.pickup.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The operations directory of pickup points accepting prepaid orders. */
public final class PickupCatalog {

	private final PickupPoint[] points;

	/** Loads the directory supplied by the pickup network. */
	public PickupCatalog() {
		final InputStream source = PickupCatalog.class.getResourceAsStream("pickup-points.txt");
		if (source == null) {
			throw new IllegalStateException("Pickup directory is missing");
		}
		try (final BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
			points = reader.lines().map(PickupPoint::new).toArray(PickupPoint[]::new);
		} catch (final IOException e) {
			throw new UncheckedIOException("Could not read the pickup directory", e);
		}
	}

	/** Looks up the selected pickup point in the operations directory. */
	public boolean contains(String pickupPoint) {
		for (final PickupPoint point : points) {
			if (point.matches(pickupPoint)) {
				return true;
			}
		}
		return false;
	}
}
