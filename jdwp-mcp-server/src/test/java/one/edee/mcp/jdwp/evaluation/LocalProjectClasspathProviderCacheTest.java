package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.transport.ClientWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * Tests for the memoisation, reset semantics, per-source breakdown shape, immutability of the
 * cached view, and concurrent-access safety of {@link LocalProjectClasspathProvider}.
 */
@DisplayName("LocalProjectClasspathProvider — caching and breakdown")
class LocalProjectClasspathProviderCacheTest {

	/** Safety bound for positive test signals. Progress is asserted while the old scan is gated. */
	private static final long SIGNAL_TIMEOUT_SECONDS = 30;

	/** Keeps the old worker blocked while a replacement scan completes, then releases its late result. */
	@Test
	void replacementCompletesWhileCancelledWorkerIsStillUnwinding(@TempDir Path project) throws Exception {
		final Path next = Files.createDirectory(project.resolve("next"));
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		Files.writeString(next.resolve("pom.xml"), "<project/>");
		final ClientWorkspace workspace = new ClientWorkspace();
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch cancelled = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final CountDownLatch replacement = new CountDownLatch(1);
		final CountDownLatch oldScanReturned = new CountDownLatch(1);
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, workspace,
			name -> null, (command, directory, timeout) -> {
				if (directory.equals(next)) {
					replacement.countDown();
					return List.of("/new.jar");
				}
				started.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					cancelled.countDown();
					release.await();
				}
				oldScanReturned.countDown();
				return List.of("/old.jar");
			});

		try {
			provider.startForConnection(mock(VirtualMachine.class));
			assertThat(started.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", next.toString())));
			provider.startForConnection(mock(VirtualMachine.class));
			assertThat(cancelled.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(replacement.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			awaitCachedEntry(provider, "/new.jar");
			release.countDown();
			assertThat(oldScanReturned.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(provider.peekCachedBreakdown()).isNotNull()
				.extracting(LocalProjectClasspathProvider.Breakdown::all).isEqualTo(Set.of("/new.jar"));
		} finally {
			release.countDown();
			provider.reset();
		}
	}

	/** Checks that attach can complete while the old worker's interrupt handler blocks in I/O cleanup. */
	@Test
	void replacementDoesNotWaitForCancelledIoToClose(@TempDir Path project) throws Exception {
		final Path next = Files.createDirectory(project.resolve("next"));
		Files.writeString(project.resolve("pom.xml"), "<project/>");
		Files.writeString(next.resolve("pom.xml"), "<project/>");
		final ClientWorkspace workspace = new ClientWorkspace();
		final CountDownLatch workerStarted = new CountDownLatch(1);
		final Semaphore releaseWorker = new Semaphore(0);
		final CountDownLatch closeEntered = new CountDownLatch(1);
		final Semaphore releaseClose = new Semaphore(0);
		final CountDownLatch closeFinished = new CountDownLatch(1);

		/** Test channel whose close hook can block the thread requesting interruption. */
		final class StalledChannel extends AbstractInterruptibleChannel {
			/** Registers the interrupt hook and parks the discovery worker until released. */
			void blockInRead() throws java.io.IOException {
				begin();
				try {
					workerStarted.countDown();
					// An interruptible wait could unwind to end(false) and unregister the
					// channel hook before Thread.interrupt() gets to invoke it.
					releaseWorker.acquireUninterruptibly();
				} finally {
					end(false);
				}
			}

			/** Signals that the interrupt handler entered, then waits for the test to release it. */
			@Override
			protected void implCloseChannel() {
				closeEntered.countDown();
				releaseClose.acquireUninterruptibly();
				closeFinished.countDown();
			}
		}

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(project, workspace,
			name -> null, (command, directory, timeout) -> {
				if (directory.equals(project)) {
					// Model an I/O implementation whose interruption hook blocks its caller.
					new StalledChannel().blockInRead();
				}
				return List.of(directory.resolve("dependency.jar").toString());
			});
		provider.startForConnection(mock(VirtualMachine.class));
		try {
			assertThat(workerStarted.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				.as("the old worker registered its channel interrupt hook").isTrue();
			workspace.observe(Map.of("codex/sandbox-state", Map.of("sandboxCwd", next.toString())));
			final VirtualMachine replacement = mock(VirtualMachine.class);
			final CompletableFuture<Void> attachment =
				CompletableFuture.runAsync(() -> provider.startForConnection(replacement));
			assertThat(closeEntered.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
				.as("cancellation entered the old channel's close hook").isTrue();
			attachment.get(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			awaitCachedEntry(provider, next.resolve("dependency.jar").toString());
			assertThat(closeFinished.getCount()).as("attach and discovery completed while close was blocked").isEqualTo(1);
		} finally {
			releaseClose.release();
			releaseWorker.release();
			provider.reset();
		}
	}

	/** Observes the cached result of the scan started by attach. */
	private static void awaitCachedEntry(LocalProjectClasspathProvider provider, String entry) {
		await().atMost(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS).untilAsserted(() ->
			assertThat(provider.peekCachedBreakdown()).isNotNull()
				.extracting(LocalProjectClasspathProvider.Breakdown::all).isEqualTo(Set.of(entry)));
	}

	@Test
	@DisplayName("subsequent discover() and discoverWithBreakdown() calls reuse the cached result")
	void shouldReuseCachedResultOnSubsequentCalls(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final AtomicInteger mavenInvocations = new AtomicInteger();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> { mavenInvocations.incrementAndGet(); return List.of("/m2/foo.jar"); }
		);

		provider.discover();
		provider.discover();
		provider.discoverWithBreakdown();

		assertThat(mavenInvocations.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("reset() clears the cache so the next discover() recomputes from sources")
	void shouldClearCacheAndForceRecomputationOnReset(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final AtomicInteger mavenInvocations = new AtomicInteger();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> { mavenInvocations.incrementAndGet(); return List.of("/m2/foo.jar"); }
		);

		provider.discover();
		provider.reset();
		provider.discover();

		assertThat(mavenInvocations.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("breakdown reports per-source entry counts and a total matching the union")
	void shouldReportPerSourceCountsInBreakdown(@TempDir Path tmp) throws Exception {
		Files.createDirectories(tmp.resolve("target/classes"));
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");

		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp,
			envName -> "JDWP_EXTRA_CLASSPATH".equals(envName)
				? "/opt/extra.jar"
				: null,
			(cmd, cwd, t) -> List.of("/m2/a.jar", "/m2/b.jar")
		);

		final var breakdown = provider.discoverWithBreakdown();

		assertThat(breakdown.envOverride()).isEqualTo(1);
		assertThat(breakdown.filesystem()).isEqualTo(1);
		assertThat(breakdown.maven()).isEqualTo(2);
		assertThat(breakdown.all()).hasSize(4);
	}

	/**
	 * Concurrent callers reaching {@link LocalProjectClasspathProvider#discover()} from the eval
	 * path and the diagnose path must not race the underlying Maven invocation. Memoisation +
	 * synchronisation means Maven runs at most once per cache lifetime even under contention.
	 */
	@Test
	@DisplayName("concurrent discover() invocations trigger Maven at most once")
	void shouldInvokeMavenAtMostOnceUnderConcurrentDiscovery(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final AtomicInteger mavenInvocations = new AtomicInteger();
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> {
				mavenInvocations.incrementAndGet();
				try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
				return List.of("/m2/foo.jar");
			}
		);

		final int threadCount = 8;
		final var pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
		try {
			final var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
			for (int i = 0; i < threadCount; i++) {
				futures.add(pool.submit(provider::discover));
			}
			for (var f : futures) f.get();
		} finally {
			pool.shutdown();
		}

		assertThat(mavenInvocations.get()).isEqualTo(1);
	}

	/**
	 * The cached breakdown's {@code all()} set must be unmodifiable so consumers cannot poison the
	 * cache by mutating the returned view. The provider wraps a defensive LinkedHashSet copy in
	 * {@link java.util.Collections#unmodifiableSet} — pin the invariant.
	 */
	@Test
	@DisplayName("the cached breakdown.all() view is unmodifiable")
	void shouldExposeUnmodifiableEntrySetInBreakdown(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> List.of("/m2/foo.jar")
		);

		final var breakdown = provider.discoverWithBreakdown();

		assertThat(breakdown.all())
			.as("cached breakdown view must be unmodifiable")
			.isUnmodifiable();
	}

	/**
	 * The cached breakdown is a value object — the second call must return the SAME reference as
	 * the first so callers can identity-compare to detect a cache hit without measuring elapsed
	 * time. This pins the memoisation seam more strictly than the "invocation count == 1" test.
	 */
	@Test
	@DisplayName("the second discoverWithBreakdown() returns the same Breakdown reference as the first")
	void shouldReturnSameBreakdownReferenceOnSecondCall(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> List.of("/m2/foo.jar")
		);

		final var first = provider.discoverWithBreakdown();
		final var second = provider.discoverWithBreakdown();

		assertThat(second).isSameAs(first);
	}

	/**
	 * The non-blocking peek seam used by the diagnose renderer must return null before any
	 * discovery has run — otherwise the renderer cannot tell "cold cache, do not block" from
	 * "warm cache with zero entries".
	 */
	@Test
	@DisplayName("peekCachedBreakdown() returns null before the first discovery")
	void shouldReturnNullFromPeekBeforeDiscovery(@TempDir Path tmp) {
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null, (cmd, cwd, t) -> List.of()
		);

		assertThat(provider.peekCachedBreakdown()).isNull();
	}

	@Test
	@DisplayName("peekCachedBreakdown() returns the populated breakdown after discovery has run")
	void shouldReturnPopulatedBreakdownFromPeekAfterDiscovery(@TempDir Path tmp) throws Exception {
		Files.writeString(tmp.resolve("pom.xml"), "<project/>");
		final LocalProjectClasspathProvider provider = new LocalProjectClasspathProvider(
			tmp, envName -> null,
			(cmd, cwd, t) -> List.of("/m2/foo.jar")
		);

		provider.discover();

		assertThat(provider.peekCachedBreakdown())
			.isNotNull()
			.satisfies(b -> assertThat(b.maven()).isEqualTo(1));
	}
}
