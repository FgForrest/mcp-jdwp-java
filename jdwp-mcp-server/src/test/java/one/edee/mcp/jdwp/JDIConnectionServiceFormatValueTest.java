package one.edee.mcp.jdwp;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link JDIConnectionService#formatFieldValue(Value)}, the object cache surface
 * ({@link JDIConnectionService#cacheObject(ObjectReference)}, {@code getCachedObject},
 * {@code clearObjectCache}), and the private {@code isCollection} type-allow-list.
 *
 * <p>The service is constructed with mocked collaborators since {@code formatFieldValue} only
 * needs the cache map and the static {@code isBoxedPrimitiveType} helper to function.
 */
class JDIConnectionServiceFormatValueTest {

	private JDIConnectionService service;

	@BeforeEach
	void setUp() {
		service = JDIConnectionServiceTestSupport.newServiceWithMocks();
	}

	@Nested
	@DisplayName("formatFieldValue")
	class FormatFieldValue {

		@Test
		void shouldFormatNullAsString() {
			assertThat(service.formatFieldValue(null)).isEqualTo("null");
		}

		@Test
		void shouldFormatStringReferenceWithQuotes() {
			StringReference strRef = mock(StringReference.class);
			when(strRef.value()).thenReturn("hi");

			assertThat(service.formatFieldValue(strRef)).isEqualTo("\"hi\"");
		}

		@Test
		void shouldFormatPrimitiveValueViaToString() {
			PrimitiveValue pv = mock(PrimitiveValue.class);
			when(pv.toString()).thenReturn("42");

			assertThat(service.formatFieldValue(pv)).isEqualTo("42");
		}

		@Test
		void shouldFormatArrayReferenceAsArrayHashTokenAndCacheIt() {
			ArrayReference arr = mock(ArrayReference.class);
			Type arrType = mock(Type.class);
			when(arr.uniqueID()).thenReturn(7L);
			when(arr.type()).thenReturn(arrType);
			when(arrType.name()).thenReturn("int[]");
			when(arr.length()).thenReturn(3);

			String result = service.formatFieldValue(arr);

			assertThat(result).isEqualTo("Array#7 (int[][3])");
			assertThat(service.getCachedObject(7L)).isSameAs(arr);
		}

		@Test
		void shouldFormatBoxedIntegerAsUnboxedPrimitive() {
			ObjectReference obj = mock(ObjectReference.class);
			ReferenceType refType = mock(ReferenceType.class);
			Field valueField = mock(Field.class);
			IntegerValue inner = mock(IntegerValue.class);
			when(obj.referenceType()).thenReturn(refType);
			when(refType.name()).thenReturn("java.lang.Integer");
			when(refType.fieldByName("value")).thenReturn(valueField);
			when(obj.getValue(valueField)).thenReturn(inner);
			when(inner.toString()).thenReturn("42");

			assertThat(service.formatFieldValue(obj)).isEqualTo("42");
		}

		@Test
		void shouldFormatBoxedBooleanFalseAsLiteral() {
			ObjectReference obj = mock(ObjectReference.class);
			ReferenceType refType = mock(ReferenceType.class);
			Field valueField = mock(Field.class);
			PrimitiveValue inner = mock(PrimitiveValue.class);
			when(obj.referenceType()).thenReturn(refType);
			when(refType.name()).thenReturn("java.lang.Boolean");
			when(refType.fieldByName("value")).thenReturn(valueField);
			when(obj.getValue(valueField)).thenReturn(inner);
			when(inner.toString()).thenReturn("false");

			assertThat(service.formatFieldValue(obj)).isEqualTo("false");
		}

		@Test
		void shouldFormatUnboxableObjectReferenceWhenValueFieldMissing() {
			ObjectReference obj = mock(ObjectReference.class);
			ReferenceType refType = mock(ReferenceType.class);
			when(obj.referenceType()).thenReturn(refType);
			when(refType.name()).thenReturn("java.lang.Integer");
			when(refType.fieldByName("value")).thenReturn(null);
			when(obj.uniqueID()).thenReturn(13L);

			String result = service.formatFieldValue(obj);

			assertThat(result).isEqualTo("Object#13 (java.lang.Integer)");
			assertThat(service.getCachedObject(13L)).isSameAs(obj);
		}

		@Test
		void shouldFormatNonWrapperObjectReferenceAsObjectHashToken() {
			ObjectReference obj = mock(ObjectReference.class);
			ReferenceType refType = mock(ReferenceType.class);
			when(obj.referenceType()).thenReturn(refType);
			when(refType.name()).thenReturn("io.sandbox.Foo");
			when(obj.uniqueID()).thenReturn(99L);

			String result = service.formatFieldValue(obj);

			assertThat(result).isEqualTo("Object#99 (io.sandbox.Foo)");
			assertThat(service.getCachedObject(99L)).isSameAs(obj);
		}

		@Test
		void shouldFallBackToValueToStringForUnknownKinds() {
			Value unknown = mock(Value.class);
			when(unknown.toString()).thenReturn("<weird>");

			assertThat(service.formatFieldValue(unknown)).isEqualTo("<weird>");
		}
	}

	@Nested
	@DisplayName("Object cache")
	class ObjectCache {

		@Test
		void shouldCacheObjectByUniqueId() {
			ObjectReference obj = mock(ObjectReference.class);
			when(obj.uniqueID()).thenReturn(42L);

			service.cacheObject(obj);

			assertThat(service.getCachedObject(42L)).isSameAs(obj);
		}

		@Test
		void shouldIgnoreNullOnCacheObject() {
			service.cacheObject(null);
			assertThat(service.getCachedObject(0L)).isNull();
		}

		@Test
		void shouldReturnNullForUnknownCachedId() {
			assertThat(service.getCachedObject(123456L)).isNull();
		}

		@Test
		void shouldClearCache() {
			ObjectReference obj = mock(ObjectReference.class);
			when(obj.uniqueID()).thenReturn(1L);
			service.cacheObject(obj);

			service.clearObjectCache();

			assertThat(service.getCachedObject(1L)).isNull();
		}
	}

	@Nested
	@DisplayName("isCollection allow-list")
	class IsCollection {

		@Test
		void shouldRecogniseArrayList() throws Exception {
			assertThat(invokeIsCollection("java.util.ArrayList")).isTrue();
		}

		@Test
		void shouldRecogniseLinkedList() throws Exception {
			assertThat(invokeIsCollection("java.util.LinkedList")).isTrue();
		}

		@Test
		void shouldRecogniseHashMap() throws Exception {
			assertThat(invokeIsCollection("java.util.HashMap")).isTrue();
		}

		@Test
		void shouldRecogniseLinkedHashMap() throws Exception {
			assertThat(invokeIsCollection("java.util.LinkedHashMap")).isTrue();
		}

		@Test
		void shouldRecogniseHashSet() throws Exception {
			assertThat(invokeIsCollection("java.util.HashSet")).isTrue();
		}

		@Test
		void shouldRecogniseTreeSet() throws Exception {
			assertThat(invokeIsCollection("java.util.TreeSet")).isTrue();
		}

		@Test
		void shouldRecogniseTreeMap() throws Exception {
			assertThat(invokeIsCollection("java.util.TreeMap")).isTrue();
		}

		@Test
		void shouldNotRecogniseCopyOnWriteArrayList() throws Exception {
			assertThat(invokeIsCollection("java.util.concurrent.CopyOnWriteArrayList")).isFalse();
		}

		@Test
		void shouldNotRecogniseUserTypes() throws Exception {
			assertThat(invokeIsCollection("io.sandbox.MyCollection")).isFalse();
		}

		/**
		 * {@code LinkedHashSet} must be in the smart-view allow-list so its elements render via
		 * the same path as the other Set types — the dispatch routes it to
		 * {@link JDIConnectionService#getSetElements(ObjectReference, int)}.
		 */
		@Test
		void shouldRecogniseLinkedHashSet() throws Exception {
			assertThat(invokeIsCollection("java.util.LinkedHashSet")).isTrue();
		}

		/**
		 * The {@code getCollectionView} dispatch must not route by substring match. A future
		 * addition like {@code ConcurrentSkipListMap} contains both "List" and "Map" — the
		 * dispatch must reject types not on the allow-list rather than falling through to the
		 * wrong branch. {@code ConcurrentSkipListMap} should remain {@code false} for
		 * {@code isCollection} (and thus unreachable from the dispatch entirely).
		 */
		@Test
		void shouldNotRecogniseConcurrentSkipListMap() throws Exception {
			assertThat(invokeIsCollection("java.util.concurrent.ConcurrentSkipListMap")).isFalse();
		}

		private boolean invokeIsCollection(String typeName) throws Exception {
			return TestReflectionUtils.invokePrivate(
				service, "isCollection",
				new Class[]{String.class}, typeName);
		}
	}
}
