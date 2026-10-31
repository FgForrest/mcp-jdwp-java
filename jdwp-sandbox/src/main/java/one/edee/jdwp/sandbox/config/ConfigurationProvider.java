package one.edee.jdwp.sandbox.config;

/**
 * Builds and holds the application Configuration, and runs the maintenance sweep that clears
 * stale configurations.
 */
public class ConfigurationProvider {

	private final Configuration instance;

	public ConfigurationProvider() {
		this.instance = new Configuration("MyApp");
		this.instance.init(5000);
	}

	public Configuration getConfig() {
		return instance;
	}

	/**
	 * Runs the stale-configuration maintenance sweep on a background thread and waits for it.
	 */
	public void runMaintenanceSweep() throws InterruptedException {
		final Thread reaper = new Thread(instance::resetToDefaults, "config-reaper");
		reaper.start();
		reaper.join();
	}
}
