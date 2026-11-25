package one.edee.jdwp.sandbox.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Loads configuration resources for application components. */
final class PropertyFiles {

	private PropertyFiles() {
	}

	static Properties load(final String resource) {
		final Properties properties = new Properties();
		try (final InputStream source = PropertyFiles.class.getResourceAsStream(resource)) {
			if (source == null) {
				throw new IllegalStateException("Configuration is missing: " + resource);
			}
			properties.load(source);
		} catch (final IOException e) {
			throw new UncheckedIOException("Could not read configuration: " + resource, e);
		}
		return properties;
	}
}
