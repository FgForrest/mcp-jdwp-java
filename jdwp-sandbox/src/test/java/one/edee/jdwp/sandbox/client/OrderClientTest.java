package one.edee.jdwp.sandbox.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderClientTest {

	@Test
	void shouldListOrdersSuccessfully() {
		final OrderClient client = new OrderClient(new HttpRouter());
		final int status = client.listOrders();

		assertThat(status)
			.describedAs("HTTP status for the order listing")
			.isEqualTo(200);
	}
}
