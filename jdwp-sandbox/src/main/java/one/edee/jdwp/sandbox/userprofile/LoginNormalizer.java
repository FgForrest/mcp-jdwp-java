package one.edee.jdwp.sandbox.userprofile;

import java.util.Locale;

/**
 * Builds welcome messages and performs "small" formatting touches on user profiles before they hit
 * the UI. Appears to be a read-only formatter from the call site.
 */
public class LoginNormalizer {

	/**
	 * Produces the message shown when the user logs in. Looks like a pure read operation.
	 */
	public String welcomeMessage(UserProfile profile) {
		String canonical = normalizeForDisplay(profile);
		return "Welcome back, " + canonical + "!";
	}

	/**
	 * Normalises the display name to "a canonical form" for consistent rendering across the app.
	 * The name suggests a local computation; in practice it writes the lowercased form back to the
	 * profile so downstream consumers see the same value.
	 */
	private String normalizeForDisplay(UserProfile profile) {
		String canonical = profile.getDisplayName().toLowerCase(Locale.ROOT);
		profile.setDisplayName(canonical);
		return canonical;
	}
}
