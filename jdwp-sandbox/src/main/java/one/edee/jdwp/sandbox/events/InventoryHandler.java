package one.edee.jdwp.sandbox.events;

/**
 * Handles order events by reserving inventory.
 */
public class InventoryHandler implements EventHandler {

	private final Inventory inventory;

	public InventoryHandler(Inventory inventory) {
		this.inventory = inventory;
	}

	@Override
	public void handle(OrderEvent event) {
		inventory.reserve(event.getProductId(), event.getQuantity());
	}
}
