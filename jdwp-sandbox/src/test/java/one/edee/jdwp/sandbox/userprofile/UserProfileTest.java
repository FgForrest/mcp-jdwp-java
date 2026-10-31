package one.edee.jdwp.sandbox.userprofile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test flight #5 — "The Field That Lies". The welcome message renders correctly, yet the user's
 * stored display name comes back with different casing than the caller set — and nothing visibly
 * calls {@link UserProfile#setDisplayName}.
 */
class UserProfileTest {

	@Test
	void shouldPreserveDisplayNameCasingAcrossWelcomeMessage() {
		UserProfile profile = new UserProfile(
			"alice@example.com",
			"Alice",
			List.of("user"));
		LoginNormalizer normalizer = new LoginNormalizer();

		String message = normalizer.welcomeMessage(profile);

		// The message itself uses the canonical lower-case form — this works.
		assertEquals("Welcome back, alice!", message);

		// The profile's displayName must NOT have changed — the caller passed "Alice" in and
		// expects to read "Alice" back.
		assertEquals("Alice", profile.getDisplayName(),
			"displayName must preserve the original casing — the formatter is read-only by contract");
	}
}
