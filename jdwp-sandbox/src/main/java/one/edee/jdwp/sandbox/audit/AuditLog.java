package one.edee.jdwp.sandbox.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records user activity for the account audit trail. */
public final class AuditLog {

	private final PiiMasker masker = new PiiMasker();
	private final List<String> entries = new ArrayList<>();

	/** Records an account access event in the audit trail. */
	public void record(final User user) {
		final Instant timestamp = Instant.now();
		final Map<String, Object> attributes = masker.fieldsFor(user);
		final long accountId = user.getId();
		final String entry = "%s account.access user=%d attributes=%s".formatted(timestamp, accountId, attributes);
		entries.add(entry);
	}

	/** Returns an unmodifiable copy of the recorded audit entries. */
	public List<String> entries() {
		return List.copyOf(entries);
	}
}
