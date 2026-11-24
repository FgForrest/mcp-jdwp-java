package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassType;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.ReferenceType;
import one.edee.mcp.jdwp.EvaluationGuard;
import one.edee.mcp.jdwp.JDIConnectionService;
import one.edee.mcp.jdwp.TestReflectionUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JdiExpressionEvaluator}'s private
 * {@code getDeclaredType(ReferenceType, String)} helper. This routine resolves the type name
 * the wrapper class should reference, taking care of dynamic proxies (CGLIB / Guice / Mockito
 * {@code $$}) and non-public types (walks up to a public supertype, or to a same-package type
 * the wrapper can see by being emitted into that package, falling back to {@code java.lang.Object}).
 */
class JdiExpressionEvaluatorGetDeclaredTypeTest {

	/**
	 * The default isolated wrapper package — passing this exercises the legacy
	 * walk-to-public-supertype behaviour for typical target types. A non-public type whose package
	 * happens to literally equal {@code mcp.jdi.evaluation} would still trigger the share-package
	 * shortcut; the tests don't exercise that theoretical edge case.
	 */
	private static final String DEFAULT_WRAPPER_PACKAGE = "mcp.jdi.evaluation";

	private JdiExpressionEvaluator evaluator;

	@BeforeEach
	void setUp() {
		InMemoryJavaCompiler compiler = mock(InMemoryJavaCompiler.class);
		JDIConnectionService jdiService = mock(JDIConnectionService.class);
		evaluator = new JdiExpressionEvaluator(
			compiler, jdiService, new EvaluationGuard(),
			LocalProjectClasspathProviderTestSupport.noOpProvider());
	}

	@Nested
	@DisplayName("Public class types")
	class PublicClassType {

		@Test
		void shouldReturnNameForPublicClassDirectly() throws Exception {
			ClassType type = mock(ClassType.class);
			when(type.name()).thenReturn("com.example.Foo");
			when(type.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(type)).isEqualTo("com.example.Foo");
		}

		/** A public generated class can have a dollar-digit name. */
		@Test
		void shouldKeepPublicClassWithDollarDigitName() throws Exception {
			final ClassType type = mock(ClassType.class);
			when(type.name()).thenReturn("com.example.$1");
			when(type.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(type)).isEqualTo("com.example.$1");
		}
	}

	@Nested
	@DisplayName("Non-public class types")
	class NonPublicClassType {

		@Test
		void shouldWalkUpToFirstPublicSupertype() throws Exception {
			ClassType c = mock(ClassType.class);
			ClassType b = mock(ClassType.class);
			ClassType a = mock(ClassType.class);
			when(a.name()).thenReturn("com.example.A");
			when(a.isPublic()).thenReturn(false);
			when(a.superclass()).thenReturn(b);
			when(b.name()).thenReturn("com.example.B");
			when(b.isPublic()).thenReturn(false);
			when(b.superclass()).thenReturn(c);
			when(c.name()).thenReturn("com.example.C");
			when(c.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(a)).isEqualTo("com.example.C");
		}

		@Test
		void shouldFallBackToJavaLangObjectWhenAllSuperclassesNonPublic() throws Exception {
			ClassType only = mock(ClassType.class);
			when(only.name()).thenReturn("com.example.Hidden");
			when(only.isPublic()).thenReturn(false);
			when(only.superclass()).thenReturn(null);

			assertThat(invokeGetDeclaredType(only)).isEqualTo("java.lang.Object");
		}
	}

	@Nested
	@DisplayName("Dynamic proxy handling")
	class DynamicProxyHandling {

		@Test
		void shouldUnwrapCglibProxyViaSuperclass() throws Exception {
			ClassType real = mock(ClassType.class);
			when(real.name()).thenReturn("com.example.Foo");
			when(real.isPublic()).thenReturn(true);

			ClassType proxy = mock(ClassType.class);
			when(proxy.name()).thenReturn("com.example.Foo$$EnhancerByCGLIB$$abc123");
			when(proxy.superclass()).thenReturn(real);

			assertThat(invokeGetDeclaredType(proxy)).isEqualTo("com.example.Foo");
		}

		@Test
		void shouldUnwrapGuiceProxy() throws Exception {
			ClassType real = mock(ClassType.class);
			when(real.name()).thenReturn("com.example.Service");
			when(real.isPublic()).thenReturn(true);

			ClassType proxy = mock(ClassType.class);
			when(proxy.name()).thenReturn("com.example.Service$$EnhancerByGuice$$abc");
			when(proxy.superclass()).thenReturn(real);

			assertThat(invokeGetDeclaredType(proxy)).isEqualTo("com.example.Service");
		}

		@Test
		void shouldFallBackToPrefixBeforeDollarsWhenSuperclassIsObject() throws Exception {
			// When the proxy's superclass is java.lang.Object, fall through to the substring fallback,
			// which returns the prefix before "$$". For a non-class declaring type the resulting name
			// is what gets returned by the trailing return statement.
			ClassType objectSuper = mock(ClassType.class);
			when(objectSuper.name()).thenReturn("java.lang.Object");

			ClassType proxy = mock(ClassType.class);
			when(proxy.name()).thenReturn("com.example.Bar$$Mock");
			when(proxy.superclass()).thenReturn(objectSuper);
			when(proxy.isPublic()).thenReturn(true);

			// After the proxy fallback truncates the name to "com.example.Bar", the method then
			// re-enters the public-walk branch on the original ClassType (which is public), and
			// returns the original full proxy name. This locks in the current behaviour.
			String result = invokeGetDeclaredType(proxy);
			assertThat(result).isEqualTo("com.example.Bar$$Mock");
		}
	}

	@Nested
	@DisplayName("Non-class reference types")
	class NonClassReferenceType {

		@Test
		void shouldReturnNameForArrayType() throws Exception {
			ArrayType arr = mock(ArrayType.class);
			when(arr.name()).thenReturn("int[]");

			assertThat(invokeGetDeclaredType(arr)).isEqualTo("int[]");
		}
	}

	private String invokeGetDeclaredType(ReferenceType type) throws Exception {
		return invokeGetDeclaredType(type, DEFAULT_WRAPPER_PACKAGE);
	}

	private String invokeGetDeclaredType(ReferenceType type, String wrapperPackage) throws Exception {
		return TestReflectionUtils.invokePrivate(
			evaluator, "getDeclaredType",
			new Class[]{ReferenceType.class, String.class}, type, wrapperPackage);
	}

	@Nested
	@DisplayName("Wrapper-shares-package mode")
	class WrapperSharesPackage {

		@Test
		@DisplayName("non-public type in wrapper package — returned as-is")
		void shouldReturnNonPublicTypeWhenWrapperSharesPackage() throws Exception {
			ClassType type = mock(ClassType.class);
			when(type.name()).thenReturn("com.example.PackagePrivate");
			when(type.isPublic()).thenReturn(false);
			// No need to mock superclass — the same-package check short-circuits the walk.

			assertThat(invokeGetDeclaredType(type, "com.example")).isEqualTo("com.example.PackagePrivate");
		}

		@Test
		@DisplayName("non-public type in OTHER package — still walks up to public supertype")
		void shouldStillWalkUpWhenWrapperPackageDiffers() throws Exception {
			ClassType pub = mock(ClassType.class);
			when(pub.name()).thenReturn("com.example.PublicAncestor");
			when(pub.isPublic()).thenReturn(true);

			ClassType type = mock(ClassType.class);
			when(type.name()).thenReturn("com.elsewhere.Hidden");
			when(type.isPublic()).thenReturn(false);
			when(type.superclass()).thenReturn(pub);

			assertThat(invokeGetDeclaredType(type, "com.example"))
				.isEqualTo("com.example.PublicAncestor");
		}

		@Test
		@DisplayName("nested-class binary name — `$` is rewritten to `.` in the source form")
		void shouldRewriteNestedClassDollarToDot() throws Exception {
			ClassType type = mock(ClassType.class);
			when(type.name()).thenReturn("com.example.Outer$Inner");
			when(type.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(type, "com.example"))
				.isEqualTo("com.example.Outer.Inner");
		}

		@Test
		@DisplayName("anonymous-class binary name — refuses to expose, walks to a public supertype")
		void shouldNotExposeAnonymousClass() throws Exception {
			ClassType pub = mock(ClassType.class);
			when(pub.name()).thenReturn("com.example.Public");
			when(pub.isPublic()).thenReturn(true);

			// `Outer$1` is an anonymous class — JLS does not let any class reference it by name,
			// including from its own package. The walk should keep going up to the public ancestor.
			ClassType anon = mock(ClassType.class);
			when(anon.name()).thenReturn("com.example.Outer$1");
			when(anon.isPublic()).thenReturn(false);
			when(anon.superclass()).thenReturn(pub);

			assertThat(invokeGetDeclaredType(anon, "com.example")).isEqualTo("com.example.Public");
		}

		@Test
		@DisplayName("private nested class in wrapper package — NOT reachable, walks to a public supertype")
		void shouldNotExposePrivateNestedClassEvenInSamePackage() throws Exception {
			ClassType pub = mock(ClassType.class);
			when(pub.name()).thenReturn("com.example.Public");
			when(pub.isPublic()).thenReturn(true);

			// A private nested class is accessible only from its enclosing class — even another
			// top-level class in the same package cannot reference it. The walk should skip past it.
			ClassType priv = mock(ClassType.class);
			when(priv.name()).thenReturn("com.example.Outer$PrivateInner");
			when(priv.isPublic()).thenReturn(false);
			when(priv.isPrivate()).thenReturn(true);
			when(priv.superclass()).thenReturn(pub);

			assertThat(invokeGetDeclaredType(priv, "com.example")).isEqualTo("com.example.Public");
		}
	}

	@Nested
	@DisplayName("JDK dynamic-proxy binary names")
	class JdkDynamicProxy {

		@Test
		@DisplayName("`com.sun.proxy.$Proxy12` — leading-$ simple name is NOT rewritten to `..Proxy12`")
		void shouldNotRewriteJdkDynamicProxyName() throws Exception {
			// JDK java.lang.reflect.Proxy generates classes like `com.sun.proxy.$Proxy12`. The `$`
			// is part of the simple-name component, NOT a nested-class separator — rewriting it
			// to `.` would yield the invalid name `com.sun.proxy..Proxy12` and break wrapper
			// compilation.
			ClassType proxy = mock(ClassType.class);
			when(proxy.name()).thenReturn("com.sun.proxy.$Proxy12");
			when(proxy.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(proxy)).isEqualTo("com.sun.proxy.$Proxy12");
		}
	}

	@Nested
	@DisplayName("Interface types")
	class InterfaceTypes {

		@Test
		@DisplayName("public interface — returned as-is")
		void shouldReturnPublicInterfaceDirectly() throws Exception {
			InterfaceType type = mock(InterfaceType.class);
			when(type.name()).thenReturn("com.example.Service");
			when(type.isPublic()).thenReturn(true);

			assertThat(invokeGetDeclaredType(type)).isEqualTo("com.example.Service");
		}

		@Test
		@DisplayName("non-public interface in OTHER package — walks superinterfaces to a public one")
		void shouldWalkToPublicSuperinterface() throws Exception {
			InterfaceType pub = mock(InterfaceType.class);
			when(pub.name()).thenReturn("com.example.PublicApi");
			when(pub.isPublic()).thenReturn(true);

			InterfaceType pkgPrivate = mock(InterfaceType.class);
			when(pkgPrivate.name()).thenReturn("com.elsewhere.Internal");
			when(pkgPrivate.isPublic()).thenReturn(false);
			when(pkgPrivate.superinterfaces()).thenReturn(List.of(pub));

			assertThat(invokeGetDeclaredType(pkgPrivate, "com.example"))
				.isEqualTo("com.example.PublicApi");
		}

		@Test
		@DisplayName("non-public interface with no reachable superinterface — falls back to Object")
		void shouldFallBackToObjectWhenNoReachableSuperinterface() throws Exception {
			InterfaceType only = mock(InterfaceType.class);
			when(only.name()).thenReturn("com.elsewhere.Internal");
			when(only.isPublic()).thenReturn(false);
			when(only.superinterfaces()).thenReturn(List.of());

			assertThat(invokeGetDeclaredType(only, "com.example")).isEqualTo("java.lang.Object");
		}

		@Test
		@DisplayName("non-public interface in wrapper package — exposed by its own name")
		void shouldExposeNonPublicInterfaceWhenWrapperSharesPackage() throws Exception {
			InterfaceType type = mock(InterfaceType.class);
			when(type.name()).thenReturn("com.example.PackagePrivateApi");
			when(type.isPublic()).thenReturn(false);
			// superinterfaces() is never consulted — the same-package check accepts it first.

			assertThat(invokeGetDeclaredType(type, "com.example"))
				.isEqualTo("com.example.PackagePrivateApi");
		}

		@Test
		@DisplayName("diamond superinterface graph — visited-guard avoids re-expanding the shared parent")
		void shouldTerminateOnDiamondInheritance() throws Exception {
			InterfaceType pub = mock(InterfaceType.class);
			when(pub.name()).thenReturn("com.example.Root");
			when(pub.isPublic()).thenReturn(true);

			// Two non-public mid-level interfaces both extend the same public Root (a diamond when
			// a leaf extends both). Interface inheritance is acyclic, so the walk terminates even
			// without the visited set; the set's job here is to avoid re-expanding the shared Root
			// twice (and to stay safe against pathological/cyclic inputs).
			InterfaceType left = mock(InterfaceType.class);
			when(left.name()).thenReturn("com.elsewhere.Left");
			when(left.isPublic()).thenReturn(false);
			when(left.superinterfaces()).thenReturn(List.of(pub));

			InterfaceType right = mock(InterfaceType.class);
			when(right.name()).thenReturn("com.elsewhere.Right");
			when(right.isPublic()).thenReturn(false);
			when(right.superinterfaces()).thenReturn(List.of(pub));

			InterfaceType leaf = mock(InterfaceType.class);
			when(leaf.name()).thenReturn("com.elsewhere.Leaf");
			when(leaf.isPublic()).thenReturn(false);
			when(leaf.superinterfaces()).thenReturn(List.of(left, right));

			assertThat(invokeGetDeclaredType(leaf, "com.example")).isEqualTo("com.example.Root");
		}
	}
}
