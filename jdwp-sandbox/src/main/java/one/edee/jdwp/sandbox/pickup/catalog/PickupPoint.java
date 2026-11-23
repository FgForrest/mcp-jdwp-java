package one.edee.jdwp.sandbox.pickup.catalog;

/** An entry in the pickup network's directory. */
final class PickupPoint {

	private final String name;

	/** Creates an entry from the name supplied by the pickup network. */
	PickupPoint(String name) {
		this.name = name;
	}

	/** Checks a requested pickup point against this entry. */
	boolean matches(String pickupPoint) {
		return name.equals(pickupPoint);
	}
}
