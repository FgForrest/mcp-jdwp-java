package one.edee.jdwp.sandbox.pickup;

import one.edee.jdwp.sandbox.pickup.api.PickupDesk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flight #11: The Missing Pickup — a listed pickup point is reported as unavailable.
 */
class PickupDeskTest {

	/** A prepaid order can be collected at its selected pickup point. */
	@Test
	void shouldAcceptPickupAtSelectedPoint() {
		final PickupDesk desk = new PickupDesk();
		final String pickupPoint = "Marché Central";

		assertTrue(desk.acceptsPickup(pickupPoint), "Pickup unavailable for the selected location");
	}
}
