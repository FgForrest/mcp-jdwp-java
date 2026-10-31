package one.edee.jdwp.sandbox.bank;

/**
 * Transfers funds between accounts and keeps an audit trail of the balances. Every account
 * operation is synchronized, so the books always balance.
 */
public class TransferService {

	private final AuditService auditService;

	public TransferService(AuditService auditService) {
		this.auditService = auditService;
	}

	/**
	 * Transfers {@code amount} from one account to another and snapshots both balances for the
	 * audit trail.
	 */
	public void transfer(BankAccount from, BankAccount to, int amount) {
		from.withdraw(amount);

		// Snapshot balances for the audit trail
		auditService.snapshotBalances(from, to);

		to.deposit(amount);
	}
}
