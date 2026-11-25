package one.edee.jdwp.sandbox.audit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Prepares entity attributes for audit entries. */
final class PiiMasker {

	private static final Set<String> PERSONAL_FIELDS = Set.of("email", "phone");

	/** Collects the entity's declared instance fields with personal values masked. */
	Map<String, Object> fieldsFor(final Object entity) {
		final Map<String, Object> values = new LinkedHashMap<>();
		try {
			for (final Field field : entity.getClass().getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
					continue;
				}
				field.setAccessible(true);
				final String name = field.getName();
				final Object value = field.get(entity);
				if (PERSONAL_FIELDS.contains(name) && value instanceof String text && !text.isEmpty()) {
					field.set(entity, mask(name, text));
				}
				values.put(name, field.get(entity));
			}
		} catch (final IllegalAccessException e) {
			throw new IllegalStateException("Could not prepare audit attributes", e);
		}
		return values;
	}

	private String mask(final String name, final String text) {
		if ("email".equals(name)) {
			final int separator = text.indexOf('@');
			return separator > 0 ? text.substring(0, 1) + "***" + text.substring(separator) : "***";
		}
		return text.length() > 4 ? "***" + text.substring(text.length() - 4) : "***";
	}
}
