package one.edee.jdwp.sandbox.bank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferServiceTest {

	private AuditService auditService;
	private TransferService transferService;

	@BeforeEach
	void setUp() {
		auditService = new AuditService();
		transferService = new TransferService(auditService);
	}

	@Test
	void shouldMaintainTotalBalance() {
		BankAccount a = new BankAccount(1000);
		BankAccount b = new BankAccount(1000);

		transferService.transfer(a, b, 500);

		int discrepancy = auditService.reconcile(a, b);
		assertThat(discrepancy)
			.describedAs("Total balance should be consistent before and after transfer")
			.isZero();
	}
}
