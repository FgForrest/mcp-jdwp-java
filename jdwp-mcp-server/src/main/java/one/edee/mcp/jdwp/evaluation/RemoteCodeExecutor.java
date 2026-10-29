package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.*;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiClassDefinitionException;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Injects compiled bytecode into a target JVM and invokes a static method on the resulting class.
 * <p>
 * Three-phase execution per call to {@link #execute}:
 * 1. {@link #loadClass} — calls `ClassLoader.defineClass(name, bytes, 0, len)` in the target VM
 * via JDI to define the class. Idempotent: a `vm.classesByName(name)` check at the top of
 * `loadClass` returns any existing definition unchanged, which is what makes the
 * {@link JdiExpressionEvaluator}'s compilation cache safe to reuse across calls with the same
 * class name.
 * 2. {@link #forceClassInitialization} — calls `Class.forName(name, true, classLoader)` so the
 * class is fully prepared and initialized before we look up its methods. Without this,
 * `methodsByName()` may return empty for a defined-but-not-prepared class.
 * 3. Looks up the static method by name and invokes it with `INVOKE_SINGLE_THREADED`.
 */
public final class RemoteCodeExecutor {
    private static final Logger log = LoggerFactory.getLogger(RemoteCodeExecutor.class);

    /**
     * JDI method name for `ClassLoader.defineClass`.
     */
    private static final String CLASSLOADER_DEFINE_CLASS_METHOD = "defineClass";
    /**
     * JNI signature for the four-argument `defineClass(String, byte[], int, int)` overload.
     */
    private static final String CLASSLOADER_DEFINE_CLASS_SIGNATURE = "(Ljava/lang/String;[BII)Ljava/lang/Class;";
    /**
     * How often {@link #createPinned} creates an object a GC collected before it could be pinned.
     */
    private static final int PIN_ATTEMPTS = 3;

    private RemoteCodeExecutor() {
    }

    /**
     * Injects and executes a static method from the given bytecode in the target VM.
     *
     * @param vm          The target VirtualMachine.
     * @param thread      The thread in which to execute the code. Must be suspended.
     * @param classLoader The ClassLoaderReference to use for defining the new class.
     * @param className   The fully qualified name of the class to load.
     * @param bytecode    The compiled bytecode of the class.
     * @param methodName  The name of the static method to invoke.
     * @param methodArgs  The arguments to pass to the method.
     * @return The Value returned by the remote method invocation.
     * @throws JdiEvaluationException if any step of the remote execution fails.
     */
    @Nullable
    public static Value execute(VirtualMachine vm, ThreadReference thread, ClassLoaderReference classLoader,
                                String className, byte[] bytecode, String methodName, List<Value> methodArgs)
        throws JdiEvaluationException {

        final long startTime = System.currentTimeMillis();

        try {
            log.debug("[Executor] Starting remote execution of {}.{}() with {} args",
                className, methodName, methodArgs.size());

            // 1. Load the class into the target VM
            final ClassType loadedClass = loadClass(vm, thread, classLoader, className, bytecode);
            log.debug("[Executor] Class {} loaded successfully", className);

            // 2. Force class preparation/initialization via Class.forName()
            // This ensures the class is in "prepared" state before calling methodsByName()
            forceClassInitialization(vm, thread, classLoader, className);
            log.debug("[Executor] Class {} initialized successfully", className);

            // 3. Find the static method to execute (now safe after initialization)
            final Method methodToInvoke = loadedClass.methodsByName(methodName).stream()
                .filter(Method::isStatic)
                .findFirst()
                .orElseThrow(() -> new JdiEvaluationException(
                    "Could not find static method '" + methodName + "' in loaded class."
                ));

            log.debug("[Executor] Found static method {}", methodName);

            // 4. Invoke the method
            final Value result = loadedClass.invokeMethod(thread, methodToInvoke, methodArgs, ClassType.INVOKE_SINGLE_THREADED);

            final long elapsed = System.currentTimeMillis() - startTime;
            final String resultType = result != null ? result.type().name() : "null";
            log.info("[Executor] Remote method invoked successfully in {}ms, returned: {}", elapsed, resultType);

            return result;

        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            final long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Executor] Remote execution failed after {}ms: {}", elapsed, e.getClass().getSimpleName(), e);
            throw new JdiEvaluationException("Failed to execute remote code: " + e.getMessage(), e);
        } catch (InvocationException e) {
            final long elapsed = System.currentTimeMillis() - startTime;
            final ObjectReference exception = e.exception();
            final String exceptionType = exception != null ? exception.type().name() : "unknown";
            log.error("[Executor] Target VM threw exception after {}ms: {}", elapsed, exceptionType, e);
            throw new JdiEvaluationException("Target VM threw exception: " + exceptionType, e);
        } catch (JdiEvaluationException e) {
            // Already classified by a lower phase (notably loadClass's JdiClassDefinitionException).
            // Propagate unchanged so the evaluator can act on the precise subtype — re-wrapping here
            // would erase the define-vs-invoke distinction the package-fallback retry depends on.
            throw e;
        } catch (Exception e) {
            final long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Executor] Unexpected exception after {}ms: {}", elapsed, e.getClass().getName(), e);
            throw new JdiEvaluationException("Unexpected exception during remote execution: " + e.getMessage(), e);
        }
    }

    /**
     * Defines {@code bytecode} as {@code className} through {@code classLoader} and initializes it,
     * without invoking anything on it. Used for fast-logpoint probes, which must be resolvable and
     * initialized <em>before</em> the instrumented class starts calling them — a resolution or
     * {@code <clinit>} failure at that point would surface at the application's call site.
     * <p>
     * Also verifies that {@code Class.forName(className, true, classLoader)} — the same
     * {@code loadClass} path the JVM takes when the instrumented class resolves the probe — yields
     * the class just defined, not some other class or a {@code ClassNotFoundException}.
     *
     * @param thread a thread suspended by an event, used for the {@code invokeMethod} calls
     * @return the defined, initialized class
     * @throws JdiClassDefinitionException when {@code defineClass} rejects the class (e.g. a sealed
     *                                     or signed package)
     * @throws JdiEvaluationException      when initialization fails or the loader resolves the name to
     *                                     a different class
     */
    public static ClassType defineAndInitialize(VirtualMachine vm, ThreadReference thread,
                                                ClassLoaderReference classLoader, String className,
                                                byte[] bytecode) throws JdiEvaluationException {
        final ClassType defined = loadClass(vm, thread, classLoader, className, bytecode);
        final Value resolved = forceClassInitialization(vm, thread, classLoader, className);
        if (!(resolved instanceof ClassObjectReference classObject) || !defined.equals(classObject.reflectedType())) {
            throw new JdiEvaluationException("Class loader " + classLoader.referenceType().name()
                + " does not resolve '" + className + "' to the class just defined into it");
        }
        return defined;
    }

    /**
     * Defines `bytecode` as `className` in the target VM via the supplied classloader. Idempotent:
     * if the class is already loaded (cached compilation reuse), the existing definition is
     * returned unchanged — calling `defineClass` twice would throw `LinkageError`.
     */
    private static ClassType loadClass(VirtualMachine vm, ThreadReference thread, ClassLoaderReference classLoader,
                                       String className, byte[] bytecode) throws JdiEvaluationException {
        // Cached compilations reuse the same class name across calls — if the class
        // is already loaded in the target VM, skip defineClass to avoid LinkageError.
        final List<ReferenceType> existing = vm.classesByName(className);
        if (!existing.isEmpty() && existing.get(0) instanceof ClassType existingClass) {
            log.debug("[Executor] Class {} already loaded — reusing", className);
            return existingClass;
        }

        try {
            log.debug("[Executor] Loading class {} ({} bytes) using classloader {}",
                className, bytecode.length, classLoader.referenceType().name());

            // Find the ClassLoader.defineClass(String, byte[], int, int) method
            final ReferenceType classLoaderType = classLoader.referenceType();
            final List<Method> defineClassMethods = classLoaderType.methodsByName(
                CLASSLOADER_DEFINE_CLASS_METHOD, CLASSLOADER_DEFINE_CLASS_SIGNATURE
            );
            if (defineClassMethods.isEmpty()) {
                log.error("[Executor] Could not find defineClass method on {}", classLoaderType.name());
                throw new JdiEvaluationException("Could not find 'defineClass' method on the provided ClassLoader.");
            }
            final Method defineClassMethod = defineClassMethods.get(0);

            // Create a remote byte array in the target VM to hold our bytecode. Both mirrors come
            // back pinned (createPinned) and stay pinned for the duration of the call.
            final ArrayReference remoteByteArray = createRemoteByteArray(vm, bytecode);
            log.debug("[Executor] Created remote byte array of {} bytes", bytecode.length);
            final Value loadedClassObject;
            try {
                final StringReference remoteClassName = createPinned(() -> vm.mirrorOf(className));
                try {
                    // Prepare arguments for defineClass: (className, remoteByteArray, 0, bytecode.length)
                    final List<Value> args = List.of(
                        remoteClassName,
                        remoteByteArray,
                        vm.mirrorOf(0),
                        vm.mirrorOf(bytecode.length)
                    );

                    // Invoke ClassLoader.defineClass(...)
                    log.debug("[Executor] Invoking defineClass on classloader");
                    loadedClassObject = classLoader.invokeMethod(
                        thread, defineClassMethod, args, ClassType.INVOKE_SINGLE_THREADED
                    );
                } finally {
                    enableCollectionQuietly(remoteClassName);
                }
            } finally {
                enableCollectionQuietly(remoteByteArray);
            }
            if (!(loadedClassObject instanceof ClassObjectReference classObjectReference)) {
                log.error("[Executor] defineClass returned unexpected type: {}",
                    loadedClassObject != null ? loadedClassObject.getClass().getName() : "null");
                throw new JdiEvaluationException("defineClass did not return a Class object.");
            }

            final ClassType result = (ClassType) classObjectReference.reflectedType();
            log.debug("[Executor] Class loaded and reflected successfully: {}", result.name());
            return result;

        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            log.error("[Executor] Failed to load class {}: {}", className, e.getClass().getSimpleName(), e);
            throw new JdiClassDefinitionException("Failed to load class '" + className + "' into target VM: " + e.getMessage(), e);
        } catch (InvocationException e) {
            final ObjectReference exception = e.exception();
            final String exceptionType = exception != null ? exception.type().name() : "unknown";
            log.error("[Executor] defineClass threw exception {}: {}", exceptionType, e.getMessage(), e);
            throw new JdiClassDefinitionException("Failed to load class '" + className + "': " + exceptionType, e);
        }
    }

    /**
     * Creates an object in the target and pins it against garbage collection. An object created
     * through JDI is referenced by nothing in the target until it is pinned, so a GC in between
     * collects it (and the call it was meant for fails with {@code ObjectCollectedException}) — the
     * object is created again then. Unpin it with {@link #enableCollectionQuietly}.
     */
    private static <T extends ObjectReference> T createPinned(Supplier<T> creation) {
        for (int attempt = 1; ; attempt++) {
            final T object = creation.get();
            try {
                object.disableCollection();
                return object;
            } catch (UnsupportedOperationException e) {
                // The VM cannot pin: the object is used unpinned.
                return object;
            } catch (ObjectCollectedException e) {
                if (attempt == PIN_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private static void enableCollectionQuietly(ObjectReference object) {
        try {
            object.enableCollection();
        } catch (RuntimeException ignored) {
            // Collected or VM gone — nothing left to release.
        }
    }

    /**
     * Mirrors a JVM-local `byte[]` into the target VM by allocating a fresh array of the same length
     * and populating it element-by-element via {@link VirtualMachine#mirrorOf(byte)}. Cost is
     * O(bytecode.length) JDWP round-trips for the per-byte mirror calls — expensive for large
     * bytecode but unavoidable without cooperating native code in the target VM, since
     * {@link ArrayReference#setValues} requires already-mirrored values. The array comes back
     * pinned ({@link #createPinned}); the caller unpins it.
     */
    private static ArrayReference createRemoteByteArray(VirtualMachine vm, byte[] bytes) throws JdiEvaluationException {
        log.debug("[Executor] Creating remote byte array of {} bytes", bytes.length);

        // Find the byte[] array type
        final ArrayType byteArrayType = (ArrayType) vm.classesByName("byte[]").get(0);
        // Create a new instance of byte[] in the target VM
        final ArrayReference arrayRef = createPinned(() -> byteArrayType.newInstance(bytes.length));
        boolean populated = false;
        try {
            // To set the values, we need to create a List<Value> of ByteValue mirrors. The per-byte
            // mirrorOf call is the dominant cost here — batching happens at the setValues call below
            // but the values themselves still have to round-trip individually.
            final List<Value> mirrorBytes = new ArrayList<>(bytes.length);
            for (byte b : bytes) {
                mirrorBytes.add(vm.mirrorOf(b));
            }

            // Set the array elements in the target VM
            arrayRef.setValues(0, mirrorBytes, 0, bytes.length);
            log.debug("[Executor] Remote byte array created and populated");
            populated = true;
            return arrayRef;

        } catch (ClassNotLoadedException | InvalidTypeException e) {
            log.error("[Executor] Failed to create remote byte array: {}", e.getClass().getSimpleName(), e);
            throw new JdiEvaluationException("Failed to create remote byte array: " + e.getMessage(), e);
        } finally {
            if (!populated) {
                enableCollectionQuietly(arrayRef);
            }
        }
    }

    /**
     * Forces class initialization by invoking `Class.forName(className, true, classLoader)` in the
     * target VM. Necessary because `methodsByName()` returns empty (or throws) on a class that has
     * been defined but not yet prepared/initialized. An exception thrown by the class's `<clinit>`
     * bubbles up as an `InvocationException` and is wrapped into {@link JdiEvaluationException}
     * with the original exception type in the message.
     *
     * @return the {@code Class} object {@code forName} returned
     */
    @Nullable
    private static Value forceClassInitialization(
        VirtualMachine vm,
        ThreadReference thread,
        ClassLoaderReference classLoader,
        String className
    ) throws JdiEvaluationException {
        try {
            log.debug("[Executor] Forcing initialization of class {}", className);

            // Get java.lang.Class in the target VM
            final ClassType classClass = (ClassType) vm.classesByName("java.lang.Class").get(0);

            // Find Class.forName(String, boolean, ClassLoader) method
            final Method forNameMethod = classClass.methodsByName(
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"
            ).get(0);

            // Prepare arguments: (className, true, classLoader)
            final StringReference classNameRef = createPinned(() -> vm.mirrorOf(className));
            final BooleanValue initializeRef = vm.mirrorOf(true);

            final List<Value> args = List.of(classNameRef, initializeRef, classLoader);

            // Invoke Class.forName() to force initialization
            final Value classObject;
            try {
                classObject = classClass.invokeMethod(thread, forNameMethod, args, ClassType.INVOKE_SINGLE_THREADED);
            } finally {
                enableCollectionQuietly(classNameRef);
            }

            log.debug("[Executor] Class {} initialization completed", className);
            return classObject;

        } catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException e) {
            log.error("[Executor] Failed to initialize class {}: {}", className, e.getClass().getSimpleName(), e);
            throw new JdiEvaluationException("Failed to initialize class: " + e.getMessage(), e);
        } catch (InvocationException e) {
            // This can happen if the static initializer (<clinit>) throws an exception
            final ObjectReference exception = e.exception();
            final String exceptionType = exception != null ? exception.type().name() : "unknown";
            log.error("[Executor] Static initializer of {} threw exception {}", className, exceptionType, e);
            throw new JdiEvaluationException("Static initializer threw exception: " + exceptionType, e);
        }
    }
}
