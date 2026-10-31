package one.edee.jdwp.sandbox.cart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutTest {

	@Test
	void shouldPriceAndDiscountTheCart() {
		Cart cart = new Cart("CART-1", List.of("Widget", "Gadget"));
		Checkout checkout = new Checkout();

		checkout.process(cart);

		// 2 items * 25.0 = 50.0, minus 10% = 45.0
		// Fails: the total is still 0.0.
		assertThat(cart.getTotal())
			.describedAs("Cart should be priced and discounted to 45.0")
			.isEqualTo(45.0);
	}
}
