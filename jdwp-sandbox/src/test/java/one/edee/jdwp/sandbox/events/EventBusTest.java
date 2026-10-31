package one.edee.jdwp.sandbox.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

	private Inventory inventory;
	private EventBus eventBus;

	@BeforeEach
	void setUp() {
		inventory = new Inventory();
		eventBus = new EventBus();
		eventBus.register(new InventoryHandler(inventory));
	}

	@Test
	void shouldDispatchAndUpdateInventory() {
		inventory.restock("WIDGET", 100);
		OrderEvent event = new OrderEvent("ORD-1", "WIDGET", 200);

		eventBus.dispatch(event);

		// Fails: the stock is still 100, and no exception reached this thread.
		assertThat(inventory.getStock("WIDGET"))
			.describedAs("Stock should have been reduced by the reservation")
			.isLessThan(100);

		// The bus recorded no error either.
		assertThat(eventBus.getErrorSummary())
			.describedAs("No error was recorded — yet the reservation clearly did not happen")
			.isEmpty();
	}
}
