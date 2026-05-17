package one.edee.jdwp.sandbox.userprofile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test flight #6 — "The Field That Lies". The welcome message renders correctly, yet the user's
 * stored display name silently changes casing. There is no call to {@link UserProfile#setDisplayName}
 * anywhere in {@link LoginNormalizer}; ripgrep for {@code setDisplayName} returns nothing useful.
 * Solve with a field-modification watchpoint on {@code UserProfile.displayName} — it catches even
 * reflective writes, which is what a line breakpoint on the setter would silently miss.
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
		// expects to read "Alice" back. In the broken state the value is silently lower-cased
		// via a reflective Field.set, so no line BP on the setter ever fires.
		assertEquals("Alice", profile.getDisplayName(),
			"displayName must preserve the original casing — the formatter is read-only by contract");
	}
}
