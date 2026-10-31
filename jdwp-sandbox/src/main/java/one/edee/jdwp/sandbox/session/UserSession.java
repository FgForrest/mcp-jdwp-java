package one.edee.jdwp.sandbox.session;

import java.util.Objects;

/**
 * A user session: a user id and a role.
 */
public class UserSession {

	private final String userId;
	private String role;

	public UserSession(String userId, String role) {
		this.userId = userId;
		this.role = role;
	}

	public String getUserId() {
		return userId;
	}

	public String getRole() {
		return role;
	}

	/**
	 * Upgrades the user's role.
	 */
	public void upgradeRole(String newRole) {
		this.role = newRole;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		UserSession that = (UserSession) o;
		return Objects.equals(userId, that.userId) && Objects.equals(role, that.role);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, role);
	}
}
