package one.edee.jdwp.sandbox.cart;

/**
 * Runs a cart through the checkout pipeline.
 */
public class Checkout {

	/**
	 * Validates, prices and discounts the cart.
	 */
	public Cart process(Cart cart) {
		cart = validate(cart);
		cart = price(cart);
		cart = applyDiscount(cart, 10.0);
		return cart;
	}

	/**
	 * Rejects an empty cart.
	 */
	private Cart validate(Cart cart) {
		if (cart.getItems().isEmpty()) {
			throw new IllegalStateException("Cannot check out an empty cart");
		}
		return new Cart(cart);
	}

	private Cart price(Cart cart) {
		cart.setTotal(cart.getItems().size() * 25.0);
		return cart;
	}

	private Cart applyDiscount(Cart cart, double percent) {
		cart.setTotal(cart.getTotal() * (1 - percent / 100));
		return cart;
	}
}
