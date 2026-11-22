package one.edee.mcp.jdwp.evaluation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PathSearchingVirtualMachine;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.EvaluationRefusedException;
import one.edee.mcp.jdwp.JDIConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises remote classpath discovery using only JDI mirrors. */
class ClasspathDiscovererTest {
    private final PathSearchingVirtualMachine vm = mock(PathSearchingVirtualMachine.class);
    private final ThreadReference thread = mock(ThreadReference.class);
    private final ClassType threadType = type("java.lang.Thread");
    private final ClassType classLoaderType = type("java.lang.ClassLoader");
    private final ClassType urlLoaderType = type("java.net.URLClassLoader");
    private final ClassType builtinLoaderType = type("jdk.internal.loader.BuiltinClassLoader");
    private final ClassType systemType = type("java.lang.System");
    private final ClassType urlType = type("java.net.URL");
    private final Field parent = field(classLoaderType, "parent");
    private final Field context = field(threadType, "contextClassLoader");
    private final Field path = field(urlType, "path");

    /** Supplies the JDK version and home from debugger reads. */
    @BeforeEach
    void setUp() {
        when(thread.virtualMachine()).thenReturn(vm);
        when(vm.version()).thenReturn(Runtime.version().toString());
        when(vm.classesByName("java.lang.System")).thenReturn(List.of(systemType));
        when(vm.classesByName("java.lang.Thread")).thenReturn(List.of(threadType));
        when(thread.referenceType()).thenReturn(threadType);
        when(vm.classesByName("java.lang.ClassLoader")).thenReturn(List.of(classLoaderType));
        when(vm.classesByName("java.net.URLClassLoader")).thenReturn(List.of(urlLoaderType));
        when(vm.classesByName("java.net.URL")).thenReturn(List.of(urlType));
        final ClassType properties = type("jdk.internal.util.StaticProperty");
        final Field home = field(properties, "JAVA_HOME");
        final StringReference javaHome = string(System.getProperty("java.home"));
        when(properties.getValue(home)).thenReturn(javaHome);
        when(vm.classesByName("jdk.internal.util.StaticProperty")).thenReturn(List.of(properties));
    }

    /** Keeps the initial entries first, then follows the child-to-parent loader order. */
    @Test
    void readsUrlLoaderChainAndDecodesLikeUrlGetPath() throws Exception {
        when(vm.classPath()).thenReturn(List.of(" /first ", "", "/second", "/first"));
        final URL file = URI.create("file://server/has%20spaces.jar?query").toURL();
        final URL jar = URI.create("jar:file:/archive%20file.jar!/nested+name.jar").toURL();
        final URL windows = URI.create("file:/C:/Program%20Files/application.jar").toURL();
        final ObjectReference fileMirror = url(file.getPath());
        final ObjectReference jarMirror = url(jar.getPath());
        final ObjectReference windowsMirror = url(windows.getPath());
        final ObjectReference malformed = url("/raw space/bad%2");
        final ClassType childType = type("example.LaunchedLoader");
        final Field shadowedParent = field(childType, "parent");
        final ClassLoaderReference child = loader(childType, urlLoaderType,
            fileMirror, jarMirror, malformed);
        final ClassLoaderReference ancestor = loader(urlLoaderType, urlLoaderType, jarMirror, windowsMirror);
        when(thread.getValue(context)).thenReturn(child);
        when(child.getValue(parent)).thenReturn(ancestor);
        when(child.getValue(shadowedParent)).thenReturn(child);

        final ClasspathDiscoverer.DiscoveryResult found = new ClasspathDiscoverer(vm, new EvaluationGuard()).discoverClasspath(thread, null, null);

        assertThat(found.targetMajorVersion()).isEqualTo(Runtime.version().feature());
        assertThat(found.applicationClasspath()).containsExactly("/first", "/second", "/has spaces.jar",
            "file:/archive file.jar!/nested name.jar", "/raw space/bad%2", "/C:/Program Files/application.jar");
        verify(child, never()).getValue(shadowedParent);
        verify(systemType, never()).invokeMethod(any(), any(), any(), anyInt());
        for (ObjectReference mirror : List.of(thread, child, ancestor, fileMirror, jarMirror, windowsMirror, malformed)) {
            verify(mirror, never()).invokeMethod(any(), any(), any(), anyInt());
        }
    }

    /** Reads built-in URLClassPath entries when launch classpath entries are unavailable. */
    @Test
    void readsBuiltinLoaderAndAcceptsAnEmptyPlatformLoader() throws Exception {
        final ClassType appType = type("jdk.internal.loader.ClassLoaders$AppClassLoader");
        final ClassLoaderReference application = loader(appType, builtinLoaderType, url("/app.jar"));
        final ClassType platformType = type("jdk.internal.loader.ClassLoaders$PlatformClassLoader");
        final ClassLoaderReference platform = mirror(ClassLoaderReference.class, platformType);
        when(platformType.superclass()).thenReturn(builtinLoaderType);
        when(thread.getValue(context)).thenReturn(application);
        when(application.getValue(parent)).thenReturn(platform);

        assertThat(new ClasspathDiscoverer(vm, new EvaluationGuard()).discoverClasspath(thread, null, null).applicationClasspath())
            .containsExactly("/app.jar");
        verify(application, never()).invokeMethod(any(), any(), any(), anyInt());
        verify(platform, never()).invokeMethod(any(), any(), any(), anyInt());
    }

    /** Keeps the launch spelling when the app loader also holds a file URL for that directory. */
    @Test
    void keepsTheLaunchSpellingOfBuiltinEntries() throws Exception {
        when(vm.classPath()).thenReturn(List.of("target/classes"));
        final ClassLoaderReference application = loader(builtinLoaderType, builtinLoaderType,
            url("/app/target/classes/"));
        when(thread.getValue(context)).thenReturn(application);

        assertThat(new ClasspathDiscoverer(vm, new EvaluationGuard()).discoverClasspath(thread, null, null).applicationClasspath())
            .containsExactly("target/classes");
        verify(application, never()).invokeMethod(any(), any(), any(), anyInt());
    }

    /** Reads the parent and never invokes a matching helper on a non-URL loader. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skipsAccessorOnUnknownLoader(boolean hasUnexpectedUcp) throws Exception {
        final ClassType customType = type("example.CustomLoader");
        final ClassLoaderReference custom = mirror(ClassLoaderReference.class, customType);
        final ClassLoaderReference ancestor = loader(urlLoaderType, urlLoaderType, url("/parent.jar"));
        if (hasUnexpectedUcp) {
            final Field unknownUcp = field(customType, "ucp");
            final ObjectReference otherPath = mirror(ObjectReference.class, type("example.OtherPath"));
            when(custom.getValue(unknownUcp)).thenReturn(otherPath);
        }
        final Method getUrls = accessor(customType);
        when(customType.methodsByName("getURLs", "()[Ljava/net/URL;")).thenReturn(List.of(getUrls));
        final ObjectReference entry = url("/another%20jar.jar");
        final ArrayReference urls = mock(ArrayReference.class);
        when(urls.getValues()).thenReturn(List.of(entry));
        when(custom.invokeMethod(thread, getUrls, List.of(), ObjectReference.INVOKE_SINGLE_THREADED)).thenReturn(urls);
        when(thread.getValue(context)).thenReturn(custom);
        when(custom.getValue(parent)).thenReturn(ancestor);

        assertThat(new ClasspathDiscoverer(vm, new EvaluationGuard()).discoverClasspath(thread, null, null).applicationClasspath())
            .containsExactly("/parent.jar");
        verify(custom, never()).invokeMethod(any(), any(), any(), anyInt());
        verify(ancestor, never()).invokeMethod(any(), any(), any(), anyInt());
        verify(entry, never()).invokeMethod(any(), any(), any(), anyInt());
    }

    /** A rejected URLClassLoader accessor does not freeze a partial connection classpath. */
    @Test
    void retriesFailedPublicFallback() throws Exception {
        when(vm.classPath()).thenReturn(List.of("/launch.jar"));
        final ClassLoaderReference loader = mirror(ClassLoaderReference.class, urlLoaderType);
        final Method getUrls = accessor(urlLoaderType);
        when(thread.getValue(context)).thenReturn(loader);
        final ArrayReference recovered = urlArray("/container%20only.jar");
        when(loader.invokeMethod(thread, getUrls, List.of(), ObjectReference.INVOKE_SINGLE_THREADED))
            .thenThrow(new IncompatibleThreadStateException()).thenReturn(recovered);
        final JDIConnectionService connection = InterpreterHarnessLiveTest.connectionFor(vm, new EvaluationGuard());

        assertThat(connection.discoverClasspath(thread)).isEqualTo("/launch.jar");
        assertThat(connection.getClasspathDiscoveryProblem())
            .contains("java.net.URLClassLoader", "getURLs() invocation failed", "IncompatibleThreadStateException");
        assertThat(connection.discoverClasspath(thread))
            .isEqualTo(String.join(File.pathSeparator, "/launch.jar", "/container only.jar"));
        assertThat(connection.getClasspathDiscoveryProblem()).isNull();
    }

    /** A missing field and a failed read both keep the parent walk retryable. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesMissingOrUnreadableParent(boolean missingField) throws Exception {
        when(vm.classPath()).thenReturn(List.of("/launch.jar"));
        final ClassType childType = type("example.ChildLoader");
        final ClassLoaderReference child = loader(childType, urlLoaderType, url("/child.jar"));
        final ClassLoaderReference ancestor = loader(urlLoaderType, urlLoaderType, url("/parent-only.jar"));
        when(thread.getValue(context)).thenReturn(child);
        if (missingField) {
            when(classLoaderType.fields()).thenReturn(List.of(), List.of(parent));
            when(child.getValue(parent)).thenReturn(ancestor);
        } else {
            when(child.getValue(parent)).thenThrow(new ObjectCollectedException()).thenReturn(ancestor);
        }
        final Logger logger = (Logger) LoggerFactory.getLogger(ClasspathDiscoverer.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            final JDIConnectionService connection = InterpreterHarnessLiveTest.connectionFor(vm, new EvaluationGuard());
            assertThat(connection.discoverClasspath(thread))
                .isEqualTo(String.join(File.pathSeparator, "/launch.jar", "/child.jar"));
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel().isGreaterOrEqual(Level.WARN)).isTrue();
                assertThat(event.getFormattedMessage()).contains("parent", "example.ChildLoader");
            });
            assertThat(connection.discoverClasspath(thread))
                .isEqualTo(String.join(File.pathSeparator, "/launch.jar", "/child.jar", "/parent-only.jar"));
            assertThat(connection.getClasspathDiscoveryProblem()).isNull();
            final Field ucp = urlLoaderType.fieldByName("ucp");
            verify(child).getValue(ucp);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** Ignores private and static lookalikes even on a URLClassLoader subclass. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skipsNonPublicOrStaticUrlAccessor(boolean isStatic) throws Exception {
        final ClassType type = type("example.UrlLoader");
        final ClassLoaderReference loader = loader(type, urlLoaderType, url("/from-fields.jar"));
        final Method lookalike = accessor(type);
        when(lookalike.isStatic()).thenReturn(isStatic);
        when(lookalike.isPublic()).thenReturn(isStatic);
        when(thread.getValue(context)).thenReturn(loader);

        final ClasspathDiscoverer.DiscoveryResult result = new ClasspathDiscoverer(vm, new EvaluationGuard())
            .discoverClasspath(thread, null, null);
        assertThat(result.applicationClasspath()).containsExactly("/from-fields.jar");
        assertThat(result.problem()).isNull();
        verify(loader, never()).invokeMethod(any(), any(), any(), anyInt());
    }

    /** Uses the inherited context field provisionally when the override cannot take a lease. */
    @Test
    void readsContextFieldWhenGetterInvocationIsRefused() throws Exception {
        final ClassType childThread = type("example.ChildThread");
        final ClassType parentThread = type("example.ParentThread");
        when(thread.referenceType()).thenReturn(childThread);
        when(childThread.superclass()).thenReturn(parentThread);
        when(parentThread.superclass()).thenReturn(threadType);
        final Method getter = mock(Method.class);
        when(getter.isPublic()).thenReturn(true);
        when(getter.name()).thenReturn("getContextClassLoader");
        when(getter.signature()).thenReturn("()Ljava/lang/ClassLoader;");
        when(parentThread.methods()).thenReturn(List.of(getter));
        final ClassLoaderReference loader = loader(urlLoaderType, urlLoaderType, url("/from-field.jar"));
        when(thread.getValue(context)).thenReturn(loader);
        final EvaluationGuard guard = mock(EvaluationGuard.class);
        when(guard.lease(thread)).thenThrow(new EvaluationRefusedException("thread has no invocation event"));

        final ClasspathDiscoverer.DiscoveryResult result = new ClasspathDiscoverer(vm, guard)
            .discoverClasspath(thread, null, null);
        assertThat(result.applicationClasspath()).containsExactly("/from-field.jar");
        assertThat(result.problem()).contains("example.ChildThread", "getContextClassLoader", "no invocation event");
        verify(thread, never()).invokeMethod(any(), any(), any(), anyInt());
    }

    /** Publishes both declared and visible mirrors for a public instance URL accessor. */
    private static Method accessor(ClassType type) {
        final Method method = mock(Method.class);
        when(method.name()).thenReturn("getURLs");
        when(method.signature()).thenReturn("()[Ljava/net/URL;");
        when(method.declaringType()).thenReturn(type);
        when(method.isPublic()).thenReturn(true);
        when(type.methods()).thenReturn(List.of(method));
        when(type.methodsByName("getURLs", "()[Ljava/net/URL;")).thenReturn(List.of(method));
        return method;
    }

    /** Builds the array returned by an invoked accessor. */
    private ArrayReference urlArray(String path) {
        final ObjectReference url = url(path);
        final ArrayReference array = mock(ArrayReference.class);
        when(array.getValues()).thenReturn(List.of(url));
        return array;
    }

    /** Builds a loader with URLClassPath.path backed by an ArrayList with spare capacity. */
    private static ClassLoaderReference loader(ClassType type, ClassType ucpOwner, ObjectReference... urls) {
        final ClassLoaderReference loader = mirror(ClassLoaderReference.class, type);
        final Field existing = ucpOwner.fieldByName("ucp");
        final Field ucp = existing == null ? field(ucpOwner, "ucp") : existing;
        if (type != ucpOwner) {
            when(type.superclass()).thenReturn(ucpOwner);
        }
        final ClassType ucpType = type("jdk.internal.loader.URLClassPath");
        final ObjectReference classpath = mirror(ObjectReference.class, ucpType);
        when(loader.getValue(ucp)).thenReturn(classpath);
        final Field path = field(ucpType, "path");
        final ClassType listType = type("java.util.ArrayList");
        final ObjectReference list = mirror(ObjectReference.class, listType);
        when(classpath.getValue(path)).thenReturn(list);
        final Field size = field(listType, "size");
        final Field elementData = field(listType, "elementData");
        final IntegerValue count = mock(IntegerValue.class);
        when(count.value()).thenReturn(urls.length);
        final ArrayReference elements = mock(ArrayReference.class);
        when(elements.getValues(0, urls.length)).thenReturn(List.<Value>of(urls));
        when(list.getValues(List.of(size, elementData))).thenReturn(Map.of(size, count, elementData, elements));
        return loader;
    }

    /** Builds a URL mirror with the supplied getPath value. */
    private ObjectReference url(String urlPath) {
        final ObjectReference url = mirror(ObjectReference.class, urlType);
        final StringReference value = string(urlPath);
        when(url.getValue(path)).thenReturn(value);
        return url;
    }

    /** Adds a named field to its declaring type. */
    private static Field field(ReferenceType owner, String name) {
        final Field field = mock(Field.class);
        final List<Field> fields = new ArrayList<>(owner.fields());
        fields.add(field);
        when(field.declaringType()).thenReturn(owner);
        when(field.name()).thenReturn(name);
        when(owner.fields()).thenReturn(fields);
        when(owner.fieldByName(name)).thenReturn(field);
        return field;
    }

    /** Builds a named class mirror. */
    private static ClassType type(String name) {
        final ClassType type = mock(ClassType.class);
        when(type.name()).thenReturn(name);
        return type;
    }

    /** Builds an object of the supplied mirror type. */
    private static <T extends ObjectReference> T mirror(Class<T> kind, ClassType type) {
        final T mirror = mock(kind);
        when(mirror.referenceType()).thenReturn(type);
        return mirror;
    }

    /** Builds a remote string with the supplied Java value. */
    private static StringReference string(String text) {
        final StringReference string = mock(StringReference.class);
        when(string.value()).thenReturn(text);
        return string;
    }
}
