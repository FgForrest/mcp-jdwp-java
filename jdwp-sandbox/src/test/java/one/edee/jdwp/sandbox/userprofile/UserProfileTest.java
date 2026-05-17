package one.edee.jdwp.sandbox.userprofile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test flight #6 — "The Field That Lies". The welcome message looks fine, but the user's display
 * name silently loses its original casing because a private helper inside {@link LoginNormalizer}
 * writes the lower-cased form back to the profile. Solve with a field-modification watchpoint on
 * {@code UserProfile.displayName} — it pinpoints the call site without any prior source-reading.
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
		// expects to read "Alice" back. In the broken state the value is silently lower-cased.
		assertEquals("Alice", profile.getDisplayName(),
			"displayName must preserve the original casing — the formatter is read-only by contract");
	}
}
