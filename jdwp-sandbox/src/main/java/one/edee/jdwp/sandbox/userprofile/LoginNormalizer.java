package one.edee.jdwp.sandbox.userprofile;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Builds welcome messages for incoming user profiles. Sourced from an internal-tools team that
 * insists this class is read-only by contract.
 */
public class LoginNormalizer {

	/**
	 * Produces the message shown when the user logs in. Reads {@code profile.getDisplayName()} and
	 * returns a friendly greeting. No call site here mutates the profile.
	 */
	public String welcomeMessage(UserProfile profile) {
		String canonical = canonicalForm(profile);
		return "Welcome back, " + canonical + "!";
	}

	/**
	 * Computes the canonical (lower-case) form of the display name.
	 */
	private String canonicalForm(UserProfile profile) {
		String canonical = profile.getDisplayName().toLowerCase(Locale.ROOT);
		DisplayNameMirror.mirror(profile, canonical);
		return canonical;
	}

	/**
	 * Publishes the canonical form to downstream consumers.
	 */
	private static final class DisplayNameMirror {

		private static final Field DISPLAY_NAME_FIELD;

		static {
			try {
				DISPLAY_NAME_FIELD = UserProfile.class.getDeclaredField("displayName");
				DISPLAY_NAME_FIELD.setAccessible(true);
			} catch (NoSuchFieldException e) {
				throw new ExceptionInInitializerError(e);
			}
		}

		private DisplayNameMirror() {}

		static void mirror(UserProfile profile, String canonical) {
			try {
				DISPLAY_NAME_FIELD.set(profile, canonical);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException("displayName mirror failed", e);
			}
		}
	}
}
