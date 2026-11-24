package one.edee.jdwp.sandbox.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

	@Test
	void shouldPreserveUserEmailWhenRecordingAnEvent() {
		final String email = "jordan@example.com";
		final User user = new User(42L, email);
		final AuditLog auditLog = new AuditLog();

		auditLog.record(user);

		assertThat(user.getEmail())
			.describedAs("User email after recording the audit event")
			.isEqualTo(email);
	}
}
