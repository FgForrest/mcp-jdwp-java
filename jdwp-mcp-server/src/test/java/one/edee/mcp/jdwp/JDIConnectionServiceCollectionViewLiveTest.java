package one.edee.mcp.jdwp;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the smart-collection rendering produced by {@link JDIConnectionService}'s private
 * {@code getCollectionView} helper for the JDK collection types in its allow-list. Uses Mockito
 * to simulate the JDI {@code ObjectReference} field graph for each backing layout: HashMap's
 * {@code table[]} bucket array, TreeMap's red-black {@code root}, LinkedList's {@code first}/{@code next}
 * linked nodes, HashSet (delegating to a backing HashMap), and TreeSet's {@code m} backing field.
 *
 * <p>Each test invokes the private {@code getCollectionView(ObjectReference, long, String)}
 * directly via reflection so the public {@code getObjectFields} entry point's
 * {@code ensureConnected()} guard is bypassed.
 */
@DisplayName("Smart-collection rendering")
class JDIConnectionServiceCollectionViewLiveTest {

	private JDIConnectionService service;

	@BeforeEach
	void setUp() {
		service = JDIConnectionServiceTestSupport.newServiceWithMocks();
	}

	/**
	 * {@code getMapEntries} must walk a {@link java.util.HashMap}'s {@code table[]} bucket array
	 * (and follow each {@code Node.next} chain) to render entries. Verifies that the entries
	 * appear under the {@code Entries:} header for the mocked HashMap.
	 */
	@Test
	void shouldRenderEntriesForHashMap() throws Exception {
		ObjectReference map = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field sizeField = mock(Field.class);
		IntegerValue sizeValue = mock(IntegerValue.class);
		when(map.referenceType()).thenReturn(refType);
		when(refType.fieldByName("size")).thenReturn(sizeField);
		when(map.getValue(sizeField)).thenReturn(sizeValue);
		when(sizeValue.value()).thenReturn(2);
		when(refType.fieldByName("head")).thenReturn(null); // HashMap has no "head" field
		when(refType.allFields()).thenReturn(List.of()); // skip internal fields dump

		// HashMap.table[] — two non-null buckets, each holding one Node with key/value/next.
		ObjectReference nodeA = mockHashMapNode("a", 1, null);
		ObjectReference nodeB = mockHashMapNode("b", 2, null);
		ArrayReference table = mock(ArrayReference.class);
		when(table.length()).thenReturn(4);
		when(table.getValue(0)).thenReturn(nodeA);
		when(table.getValue(1)).thenReturn(null);
		when(table.getValue(2)).thenReturn(nodeB);
		when(table.getValue(3)).thenReturn(null);
		Field tableField = mock(Field.class);
		when(refType.fieldByName("table")).thenReturn(tableField);
		when(map.getValue(tableField)).thenReturn(table);

		String result = invokeGetCollectionView(map, 1L, "java.util.HashMap");

		assertThat(result).contains("Size: 2");
		assertThat(result).contains("Entries:");
		assertThat(result).contains("\"a\" = 1");
		assertThat(result).contains("\"b\" = 2");
	}

	/**
	 * {@code TreeMap} stores its sorted entries in a red-black tree rooted at {@code root};
	 * entries link via {@code left}/{@code right}. The walk must traverse the tree in-order so the
	 * user sees the entries.
	 */
	@Test
	void shouldRenderEntriesForTreeMap() throws Exception {
		ObjectReference map = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field sizeField = mock(Field.class);
		IntegerValue sizeValue = mock(IntegerValue.class);
		when(map.referenceType()).thenReturn(refType);
		when(refType.fieldByName("size")).thenReturn(sizeField);
		when(map.getValue(sizeField)).thenReturn(sizeValue);
		when(sizeValue.value()).thenReturn(2);
		when(refType.fieldByName("head")).thenReturn(null);
		when(refType.fieldByName("table")).thenReturn(null);
		when(refType.allFields()).thenReturn(List.of());

		// TreeMap.root → Entry("b", 2) with left=Entry("a", 1), right=null.
		ObjectReference left = mockTreeMapEntry("a", 1, null, null);
		ObjectReference root = mockTreeMapEntry("b", 2, left, null);
		Field rootField = mock(Field.class);
		when(refType.fieldByName("root")).thenReturn(rootField);
		when(map.getValue(rootField)).thenReturn(root);

		String result = invokeGetCollectionView(map, 2L, "java.util.TreeMap");

		assertThat(result).contains("Size: 2");
		assertThat(result).contains("Entries:");
		assertThat(result).contains("\"a\" = 1");
		assertThat(result).contains("\"b\" = 2");
	}

	/**
	 * {@code getListElements} must walk LinkedList's {@code first} → {@code next} chain reading
	 * the {@code item} field of each node. Verifies the elements appear in order.
	 */
	@Test
	void shouldRenderElementsForLinkedList() throws Exception {
		ObjectReference list = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field sizeField = mock(Field.class);
		IntegerValue sizeValue = mock(IntegerValue.class);
		when(list.referenceType()).thenReturn(refType);
		when(refType.fieldByName("size")).thenReturn(sizeField);
		when(list.getValue(sizeField)).thenReturn(sizeValue);
		when(sizeValue.value()).thenReturn(3);
		when(refType.fieldByName("elementData")).thenReturn(null); // LinkedList has no elementData
		when(refType.allFields()).thenReturn(List.of());

		// LinkedList.first → Node("a") → Node("b") → Node("c") → null
		ObjectReference n3 = mockLinkedListNode("c", null);
		ObjectReference n2 = mockLinkedListNode("b", n3);
		ObjectReference n1 = mockLinkedListNode("a", n2);
		Field firstField = mock(Field.class);
		when(refType.fieldByName("first")).thenReturn(firstField);
		when(list.getValue(firstField)).thenReturn(n1);

		String result = invokeGetCollectionView(list, 3L, "java.util.LinkedList");

		assertThat(result).contains("Size: 3");
		assertThat(result).contains("Elements:");
		assertThat(result).contains("[0] = \"a\"");
		assertThat(result).contains("[1] = \"b\"");
		assertThat(result).contains("[2] = \"c\"");
	}

	/**
	 * {@code getSetElements} must fall back to {@code fieldByName("m")} when the {@code map}
	 * field is missing — TreeSet's backing field is named {@code m}. Asserts the resulting set
	 * elements (extracted from the backing TreeMap's keys) appear.
	 */
	@Test
	void shouldRenderElementsForTreeSet() throws Exception {
		ObjectReference set = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field sizeField = mock(Field.class);
		IntegerValue sizeValue = mock(IntegerValue.class);
		when(set.referenceType()).thenReturn(refType);
		when(refType.fieldByName("size")).thenReturn(sizeField);
		when(set.getValue(sizeField)).thenReturn(sizeValue);
		when(sizeValue.value()).thenReturn(2);
		when(refType.fieldByName("map")).thenReturn(null); // TreeSet uses "m", not "map"
		when(refType.allFields()).thenReturn(List.of());

		// TreeSet.m → TreeMap whose root has two entries.
		ObjectReference backingMap = mock(ObjectReference.class);
		ReferenceType backingType = mock(ReferenceType.class);
		when(backingMap.referenceType()).thenReturn(backingType);
		when(backingType.fieldByName("head")).thenReturn(null);
		when(backingType.fieldByName("table")).thenReturn(null);
		ObjectReference left = mockTreeMapEntry("apple", "P", null, null);
		ObjectReference root = mockTreeMapEntry("pear", "P", left, null);
		Field rootField = mock(Field.class);
		when(backingType.fieldByName("root")).thenReturn(rootField);
		when(backingMap.getValue(rootField)).thenReturn(root);

		Field mField = mock(Field.class);
		when(refType.fieldByName("m")).thenReturn(mField);
		when(set.getValue(mField)).thenReturn(backingMap);

		String result = invokeGetCollectionView(set, 4L, "java.util.TreeSet");

		assertThat(result).contains("Size: 2");
		assertThat(result).contains("Elements:");
		assertThat(result).contains("\"apple\"");
		assertThat(result).contains("\"pear\"");
	}

	/**
	 * The recursive {@code getMapEntries} call on the backing HashMap walks the {@code table[]}
	 * bucket chain and returns the keys, so HashSet elements render via the same path. Verifies
	 * the elements appear under the {@code Elements:} header.
	 */
	@Test
	void shouldRenderElementsForHashSet() throws Exception {
		ObjectReference set = mock(ObjectReference.class);
		ReferenceType refType = mock(ReferenceType.class);
		Field sizeField = mock(Field.class);
		IntegerValue sizeValue = mock(IntegerValue.class);
		when(set.referenceType()).thenReturn(refType);
		when(refType.fieldByName("size")).thenReturn(sizeField);
		when(set.getValue(sizeField)).thenReturn(sizeValue);
		when(sizeValue.value()).thenReturn(2);
		when(refType.allFields()).thenReturn(List.of());

		// HashSet.map → HashMap with table[] containing two Node buckets.
		ObjectReference backingMap = mock(ObjectReference.class);
		ReferenceType backingType = mock(ReferenceType.class);
		when(backingMap.referenceType()).thenReturn(backingType);
		when(backingType.fieldByName("head")).thenReturn(null);
		when(backingType.fieldByName("root")).thenReturn(null);
		ObjectReference nodeA = mockHashMapNode("x", "P", null);
		ObjectReference nodeB = mockHashMapNode("y", "P", null);
		ArrayReference table = mock(ArrayReference.class);
		when(table.length()).thenReturn(2);
		when(table.getValue(0)).thenReturn(nodeA);
		when(table.getValue(1)).thenReturn(nodeB);
		Field tableField = mock(Field.class);
		when(backingType.fieldByName("table")).thenReturn(tableField);
		when(backingMap.getValue(tableField)).thenReturn(table);

		Field mapField = mock(Field.class);
		when(refType.fieldByName("map")).thenReturn(mapField);
		when(set.getValue(mapField)).thenReturn(backingMap);

		String result = invokeGetCollectionView(set, 5L, "java.util.HashSet");

		assertThat(result).contains("Size: 2");
		assertThat(result).contains("Elements:");
		assertThat(result).contains("\"x\"");
		assertThat(result).contains("\"y\"");
	}

	// ── helpers ──

	/**
	 * Builds a mocked {@code HashMap.Node} (or {@code HashMap.TreeNode}) with the given key,
	 * value, and bucket-chain successor. The {@code value} parameter accepts a String (mirrored
	 * via a mocked {@link StringReference}) or an Integer (mirrored via {@link IntegerValue}).
	 */
	private ObjectReference mockHashMapNode(String key, Object value, ObjectReference next) {
		StringReference keyMirror = mockString(key);
		Value valueMirror = mockValue(value);
		ObjectReference node = mock(ObjectReference.class);
		ReferenceType type = mock(ReferenceType.class);
		Field keyField = mock(Field.class);
		Field valueField = mock(Field.class);
		Field nextField = mock(Field.class);
		when(node.referenceType()).thenReturn(type);
		when(type.fieldByName("key")).thenReturn(keyField);
		when(type.fieldByName("value")).thenReturn(valueField);
		when(type.fieldByName("next")).thenReturn(nextField);
		when(type.fieldByName("after")).thenReturn(null);
		when(node.getValue(keyField)).thenReturn(keyMirror);
		when(node.getValue(valueField)).thenReturn(valueMirror);
		when(node.getValue(nextField)).thenReturn(next);
		return node;
	}

	/**
	 * Builds a mocked {@code TreeMap.Entry} with the given key, value, and {@code left}/{@code right}
	 * children for in-order traversal.
	 */
	private ObjectReference mockTreeMapEntry(String key, Object value,
											 ObjectReference left, ObjectReference right) {
		StringReference keyMirror = mockString(key);
		Value valueMirror = mockValue(value);
		ObjectReference entry = mock(ObjectReference.class);
		ReferenceType type = mock(ReferenceType.class);
		Field keyField = mock(Field.class);
		Field valueField = mock(Field.class);
		Field leftField = mock(Field.class);
		Field rightField = mock(Field.class);
		when(entry.referenceType()).thenReturn(type);
		when(type.fieldByName("key")).thenReturn(keyField);
		when(type.fieldByName("value")).thenReturn(valueField);
		when(type.fieldByName("left")).thenReturn(leftField);
		when(type.fieldByName("right")).thenReturn(rightField);
		when(entry.getValue(keyField)).thenReturn(keyMirror);
		when(entry.getValue(valueField)).thenReturn(valueMirror);
		when(entry.getValue(leftField)).thenReturn(left);
		when(entry.getValue(rightField)).thenReturn(right);
		return entry;
	}

	/**
	 * Builds a mocked {@code LinkedList.Node} with the given {@code item} value and {@code next}
	 * pointer. The {@code prev} field is not exercised by the walker so it is left unset.
	 */
	private ObjectReference mockLinkedListNode(String item, ObjectReference next) {
		StringReference itemMirror = mockString(item);
		ObjectReference node = mock(ObjectReference.class);
		ReferenceType type = mock(ReferenceType.class);
		Field itemField = mock(Field.class);
		Field nextField = mock(Field.class);
		when(node.referenceType()).thenReturn(type);
		when(type.fieldByName("item")).thenReturn(itemField);
		when(type.fieldByName("next")).thenReturn(nextField);
		when(node.getValue(itemField)).thenReturn(itemMirror);
		when(node.getValue(nextField)).thenReturn(next);
		return node;
	}

	/** Mirrors a Java {@code String} as a JDI {@link StringReference}. */
	private StringReference mockString(String s) {
		StringReference sr = mock(StringReference.class);
		when(sr.value()).thenReturn(s);
		return sr;
	}

	/**
	 * Mirrors a Java value into a JDI {@link Value}: String → {@link StringReference}, Integer →
	 * {@link IntegerValue}, otherwise null.
	 */
	private Value mockValue(Object v) {
		if (v == null) return null;
		if (v instanceof String s) return mockString(s);
		if (v instanceof Integer i) {
			IntegerValue iv = mock(IntegerValue.class);
			when(iv.toString()).thenReturn(String.valueOf(i));
			return iv;
		}
		return null;
	}

	private String invokeGetCollectionView(ObjectReference obj, long objectId, String typeName)
			throws Exception {
		java.lang.reflect.Method m = JDIConnectionService.class.getDeclaredMethod(
			"getCollectionView", ObjectReference.class, long.class, String.class);
		m.setAccessible(true);
		return (String) m.invoke(service, obj, objectId, typeName);
	}
}
