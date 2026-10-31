package one.edee.jdwp.sandbox.events;

/**
 * An order event: the order, the product and the ordered quantity.
 */
public class OrderEvent {

	private final String orderId;
	private final String productId;
	private final int quantity;

	/**
	 * Creates an order event.
	 */
	public OrderEvent(String orderId, String productId, int rawQuantity) {
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = (byte) rawQuantity;
	}

	public String getOrderId() {
		return orderId;
	}

	public String getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}
}
