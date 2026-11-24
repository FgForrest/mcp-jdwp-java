package one.edee.jdwp.sandbox.likes;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

class LikeServiceTest {

	@Test
	void shouldCountBothLikes() throws Exception {
		final long postId = 42L;
		final InMemoryPostRepository repository = new InMemoryPostRepository(postId);
		final LikeService service = new LikeService(repository);
		final CountDownLatch ready = new CountDownLatch(2);
		final CountDownLatch start = new CountDownLatch(1);
		final Callable<Void> like = () -> {
			ready.countDown();
			start.await();
			service.like(postId);
			return null;
		};

		final FutureTask<Void> first = new FutureTask<>(like);
		final FutureTask<Void> second = new FutureTask<>(like);
		final Thread maya = new Thread(first, "Maya");
		final Thread noah = new Thread(second, "Noah");
		maya.start();
		noah.start();
		ready.await();
		start.countDown();
		first.get();
		second.get();

		assertThat(repository.likesFor(postId))
			.describedAs("Total likes after two users like the post")
			.isEqualTo(2);
	}
}
