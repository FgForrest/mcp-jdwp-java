package one.edee.jdwp.sandbox.pickup.api;

import one.edee.jdwp.sandbox.pickup.catalog.PickupCatalog;

/** Checks where prepaid orders can be collected. */
public final class PickupDesk {

	private final PickupCatalog catalog = new PickupCatalog();

	/** Returns whether the selected pickup point accepts collections. */
	public boolean acceptsPickup(String pickupPoint) {
		return catalog.contains(pickupPoint);
	}
}
