package one.edee.jdwp.sandbox.likes;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** Stores published posts in memory. */
public final class InMemoryPostRepository {

	private final Map<Long, Post> posts = new ConcurrentHashMap<>();

	/** Opens the store with a newly published post. */
	public InMemoryPostRepository(final long postId) {
		posts.put(postId, new Post());
	}

	Post load(final long postId) throws InterruptedException {
		final Post stored = find(postId);
		final Post post = new Post(stored);
		// Simulate a database round trip.
		Thread.sleep(750);
		return post;
	}

	void save(final long postId, final Post post) {
		posts.put(postId, post);
	}

	/** Returns the currently stored like count. */
	public int likesFor(final long postId) {
		return find(postId).likes();
	}

	private Post find(final long postId) {
		final Post post = posts.get(postId);
		if (post == null) {
			throw new NoSuchElementException("Post " + postId);
		}
		return post;
	}
}
