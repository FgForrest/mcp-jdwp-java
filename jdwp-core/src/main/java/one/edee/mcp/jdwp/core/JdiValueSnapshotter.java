package one.edee.mcp.jdwp.core;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts JDI values into JSON-friendly, size-bounded maps and lists without invoking target code.
 */
public final class JdiValueSnapshotter {
    private final SnapshotLimits limits;

    public JdiValueSnapshotter(SnapshotLimits limits) {
        this.limits = limits;
    }

    public Object snapshot(Value value) {
        return snapshot(value, 0, new IdentityHashMap<>());
    }

    private Object snapshot(Value value, int depth, IdentityHashMap<ObjectReference, Boolean> seen) {
        if (value == null) {
            return null;
        }
        if (value instanceof StringReference stringReference) {
            return truncate(stringReference.value());
        }
        if (value instanceof PrimitiveValue primitiveValue) {
            return primitiveValue.toString();
        }
        if (!(value instanceof ObjectReference objectReference)) {
            return truncate(value.toString());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("$type", objectReference.referenceType().name());
            result.put("$id", objectReference.uniqueID());
            if (seen.put(objectReference, Boolean.TRUE) != null) {
                result.put("$cycle", true);
                return result;
            }
            if (depth >= limits.maxDepth()) {
                result.put("$truncated", "maxDepth");
                return result;
            }
            if (objectReference instanceof ArrayReference arrayReference) {
                snapshotArray(arrayReference, depth, seen, result);
            } else {
                snapshotFields(objectReference, depth, seen, result);
            }
        } catch (ObjectCollectedException collected) {
            result.put("$collected", true);
        } catch (RuntimeException inaccessible) {
            result.put("$error", inaccessible.getClass().getSimpleName() + ": " + inaccessible.getMessage());
        }
        return result;
    }

    private void snapshotArray(
        ArrayReference array, int depth, IdentityHashMap<ObjectReference, Boolean> seen,
        Map<String, Object> result
    ) {
        int length = array.length();
        int count = Math.min(length, limits.maxItems());
        List<Object> elements = new ArrayList<>(count);
        for (Value element : array.getValues(0, count)) {
            elements.add(snapshot(element, depth + 1, seen));
        }
        result.put("$length", length);
        result.put("elements", elements);
        if (count < length) {
            result.put("$remaining", length - count);
        }
    }

    private void snapshotFields(
        ObjectReference object, int depth, IdentityHashMap<ObjectReference, Boolean> seen,
        Map<String, Object> result
    ) {
        List<Field> fields = object.referenceType().allFields();
        int count = Math.min(fields.size(), limits.maxItems());
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            Field field = fields.get(index);
            values.put(field.name(), snapshot(object.getValue(field), depth + 1, seen));
        }
        result.put("fields", values);
        if (count < fields.size()) {
            result.put("$remainingFields", fields.size() - count);
        }
    }

    private String truncate(String value) {
        if (value.length() <= limits.maxStringLength()) {
            return value;
        }
        return value.substring(0, limits.maxStringLength()) + "…";
    }
}
