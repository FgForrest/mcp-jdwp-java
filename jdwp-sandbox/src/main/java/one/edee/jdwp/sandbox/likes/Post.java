package one.edee.jdwp.sandbox.likes;

import java.util.EnumMap;
import java.util.Map;

/** A published post and its reaction totals. */
final class Post {

	private enum Reaction {
		LIKE, LOVE, CELEBRATE
	}

	private final Map<Reaction, Integer> reactions;

	Post() {
		this.reactions = new EnumMap<>(Reaction.class);
	}

	Post(final Post source) {
		this.reactions = new EnumMap<>(source.reactions);
	}

	int likes() {
		return reactions.getOrDefault(Reaction.LIKE, 0);
	}

	void addLike() {
		reactions.merge(Reaction.LIKE, 1, Integer::sum);
	}
}
