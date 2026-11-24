package one.edee.jdwp.sandbox.likes;

/** Records likes on published posts. */
public final class LikeService {

	private final InMemoryPostRepository repository;

	/** Connects the service to the post store. */
	public LikeService(final InMemoryPostRepository repository) {
		this.repository = repository;
	}

	/** Adds a like to the selected post. */
	public void like(final long postId) throws InterruptedException {
		final Post post = repository.load(postId);
		post.addLike();
		repository.save(postId, post);
	}
}
