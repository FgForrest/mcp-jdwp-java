package one.edee.jdwp.sandbox.deadlock;

/**
 * A bank account that transfers money to other accounts.
 */
public class Account {

	private final String id;
	private int balance;

	public Account(String id, int balance) {
		this.id = id;
		this.balance = balance;
	}

	public String getId() {
		return id;
	}

	public int getBalance() {
		return balance;
	}

	/**
	 * Transfers {@code amount} from this account to {@code to}.
	 */
	public void transfer(Account to, int amount) {
		synchronized (this) {
			pause();
			synchronized (to) {
				this.balance -= amount;
				to.balance += amount;
			}
		}
	}

	private static void pause() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
