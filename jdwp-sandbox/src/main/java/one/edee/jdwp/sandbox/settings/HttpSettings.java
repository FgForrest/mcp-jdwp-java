package one.edee.jdwp.sandbox.settings;

import java.util.Properties;

/** The HTTP settings shared by application components. */
public final class HttpSettings {

	private int timeout;

	/** Reads HTTP options from the supplied properties. */
	void configure(final Properties properties) {
		timeout = Integer.parseInt(properties.getProperty("http.timeout", "0"));
	}

	/** Returns the HTTP timeout in milliseconds. */
	public int getTimeout() {
		return timeout;
	}
}
