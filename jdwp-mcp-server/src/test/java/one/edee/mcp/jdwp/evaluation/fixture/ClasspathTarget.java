package one.edee.mcp.jdwp.evaluation.fixture;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises isolated classpaths and application methods that resemble loader accessors. */
public final class ClasspathTarget {
    private static final CountDownLatch REPOSITORIES_READY = new CountDownLatch(1);
    /** Read by JDI to detect an accidental invocation of the private helper. */
    public static volatile boolean privateAccessorEntered;

    /** Runs the selected loader scenario and keeps the VM available for inspection. */
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "standard", "lazy", "later", "override" -> exerciseIsolatedCode(args[0], Path.of(args[1]));
            case "private-accessor" -> {
                final CountDownLatch acquired = new CountDownLatch(1);
                final Thread repositoryWorker = new Thread(() -> {
                    acquired.countDown();
                    try {
                        TimeUnit.SECONDS.sleep(2);
                        REPOSITORIES_READY.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "repository-worker");
                repositoryWorker.setDaemon(true);
                repositoryWorker.start();
                acquired.await();
                Thread.currentThread().setContextClassLoader(new IsolatingLoader());
                conditionStop();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        System.in.read();
    }

    /** Loads a repository outside the launch classpath and varies how it is reached. */
    private static void exerciseIsolatedCode(String mode, Path directory) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()})) {
            final Class<?> isolated = Class.forName("classpath.fixture.Isolated", true, loader);
            if (!mode.equals("override")) {
                final Thread current = Thread.currentThread();
                if (!mode.equals("later")) {
                    current.setContextClassLoader(loader);
                }
                firstPlainStop();
                if (mode.equals("lazy")) {
                    current.setContextClassLoader(ClassLoader.getSystemClassLoader());
                }
                isolated.getMethod("stop").invoke(null);
            } else {
                final Thread thread = new Thread("overridden-context-loader") {
                    /** Supplies the effective loader independently of the inherited field. */
                    @Override
                    public ClassLoader getContextClassLoader() {
                        return loader;
                    }

                    /** Stops first in app code and then in the isolated repository. */
                    @Override
                    public void run() {
                        try {
                            firstPlainStop();
                            isolated.getMethod("stop").invoke(null);
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    }
                };
                thread.start();
                thread.join();
            }
        }
    }

    /** Breakpoint before entering code from the isolated repository. */
    private static void firstPlainStop() {
    }

    /** Stop-all breakpoint with a compiled condition. */
    private static void conditionStop() {
    }

    /** Models a loader with a private helper that depends on a worker. */
    private static final class IsolatingLoader extends ClassLoader {
        /** Blocks while the repository worker remains suspended. */
        @SuppressWarnings("unused")
        private URL[] getURLs() throws InterruptedException {
            privateAccessorEntered = true;
            REPOSITORIES_READY.await();
            return new URL[0];
        }
    }
}
