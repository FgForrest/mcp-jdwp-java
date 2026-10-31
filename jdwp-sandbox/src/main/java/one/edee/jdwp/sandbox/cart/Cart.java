package one.edee.jdwp.sandbox.cart;

import java.util.ArrayList;
import java.util.List;

/**
 * A shopping cart: its items and their total.
 */
public class Cart {

	private final String id;
	private final List<String> items;
	private double total;

	public Cart(String id, List<String> items) {
		this.id = id;
		this.items = new ArrayList<>(items);
		this.total = 0.0;
	}

	/**
	 * Copies {@code other}.
	 */
	public Cart(Cart other) {
		this.id = other.id;
		this.items = new ArrayList<>(other.items);
		this.total = other.total;
	}

	public String getId() {
		return id;
	}

	public List<String> getItems() {
		return items;
	}

	public double getTotal() {
		return total;
	}

	public void setTotal(double total) {
		this.total = total;
	}
}
