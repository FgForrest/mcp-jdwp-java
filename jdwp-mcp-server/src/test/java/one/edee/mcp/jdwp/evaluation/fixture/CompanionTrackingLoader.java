package one.edee.mcp.jdwp.evaluation.fixture;

import java.net.URL;
import java.net.URLClassLoader;

/** Owns the target fixtures and records loader visits for the compile-only superclass. */
public final class CompanionTrackingLoader extends URLClassLoader {
    /** Binary name made visible only to the compiler. */
    public static final String ABSENT = "optionalfixture.Absent";
    /** Process-local trace shared by the loader and the fixtures it defines. */
    public static final String TRACE = "jdwp.fixture.companion.trace";
    private static final String FIXTURES = CompanionTrackingLoader.class.getPackageName() + ".";

    /** Uses the fixture classpath while letting the parent load ordinary application classes. */
    public CompanionTrackingLoader(ClassLoader parent) {
        super(new URL[]{CompanionTrackingLoader.class.getProtectionDomain().getCodeSource().getLocation()}, parent);
    }

    /** Appends a trace event visible from both defining loaders. */
    public static void record(String event) {
        final String trace = System.getProperty(TRACE, "");
        System.setProperty(TRACE, trace.isEmpty() ? event : trace + " -> " + event);
    }

    /** Owns fixture definitions and reports any attempt to load the absent superclass. */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (ABSENT.equals(name)) {
            record("loadClass(Absent)");
        }
        if (!name.startsWith(FIXTURES)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = findClass(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}
