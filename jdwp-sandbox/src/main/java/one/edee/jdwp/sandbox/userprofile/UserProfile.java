package one.edee.jdwp.sandbox.userprofile;

import java.util.List;

/**
 * Mutable user profile. Display name and roles preserve whatever the caller passed; the only
 * publicly visible setter is {@link #setDisplayName(String)}.
 */
public class UserProfile {

	private final String email;
	private String displayName;
	private final List<String> roles;

	public UserProfile(String email, String displayName, List<String> roles) {
		this.email = email;
		this.displayName = displayName;
		this.roles = roles;
	}

	public String getEmail() {
		return email;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public List<String> getRoles() {
		return roles;
	}
}
