package one.edee.jdwp.sandbox.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

	private SessionStore store;

	@BeforeEach
	void setUp() {
		store = new SessionStore();
	}

	@Test
	void shouldRetrieveSessionAfterRoleUpgrade() {
		UserSession session = new UserSession("user-42", "BASIC");
		SessionData data = new SessionData(System.currentTimeMillis(), System.currentTimeMillis());
		store.store(session, data);

		store.upgradeUserRole(session, "PREMIUM");

		SessionData retrieved = store.retrieve(session);
		assertThat(retrieved)
			.describedAs("Session data should be retrievable after role upgrade")
			.isNotNull();
	}
}
