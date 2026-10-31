package one.edee.jdwp.sandbox.config;

/**
 * Application configuration: the application name and a timeout.
 */
public class Configuration {

	private final String appName;
	private int timeout;

	public Configuration(String appName) {
		this.appName = appName;
		// timeout defaults to 0 — set by init()
	}

	/**
	 * Initializes the timeout value. Called once after construction.
	 */
	public void init(int timeout) {
		this.timeout = timeout;
	}

	/**
	 * Clears mutable configuration back to defaults (timeout = 0).
	 */
	public void resetToDefaults() {
		this.timeout = 0;
	}

	public String getAppName() {
		return appName;
	}

	public int getTimeout() {
		return timeout;
	}
}
