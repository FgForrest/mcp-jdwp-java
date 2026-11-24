package one.edee.jdwp.sandbox.audit;

/** A registered user and their current contact information. */
public final class User {

	private final long id;
	private String email;

	/** Creates a user with the supplied email address. */
	public User(final long id, final String email) {
		this.id = id;
		this.email = email;
	}

	/** Returns the user's account ID. */
	public long getId() {
		return id;
	}

	/** Returns the user's current email address. */
	public String getEmail() {
		return email;
	}

	/** Updates the user's contact email address. */
	public void setEmail(final String email) {
		this.email = email;
	}
}
