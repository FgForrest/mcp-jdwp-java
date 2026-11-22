package one.edee.mcp.jdwp.transport;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The directory the MCP client works in, when the client reports one.
 *
 * <p>Claude Code starts the server in the user's project, so {@code user.dir} already is that
 * directory and nothing is reported. Codex starts a plugin's MCP server in the plugin's install
 * directory instead. When the server declares the experimental capability
 * {@value #CODEX_SANDBOX_STATE_META} (see {@link ClientWorkspaceConfig}), Codex puts the session's
 * directory into every {@code tools/call} request as
 * {@code _meta["codex/sandbox-state"].sandboxCwd}, a {@code file://} URI. Codex does not support MCP
 * {@code roots}.
 *
 * <p>{@link ClientWorkspaceConfig} feeds the {@code _meta} of every tool call to {@link #observe}
 * before the tool runs, so a tool that needs the directory sees the value reported with its own
 * call. The last valid report wins.
 */
@Component
public class ClientWorkspace {

    /** Experimental server capability that makes Codex attach its sandbox state to tool calls. */
    public static final String CODEX_SANDBOX_STATE_META = "codex/sandbox-state-meta";
    /** {@code _meta} key under which Codex sends its sandbox state. */
    static final String CODEX_SANDBOX_STATE = "codex/sandbox-state";
    /** Key of the session directory inside the sandbox state. */
    static final String SANDBOX_CWD = "sandboxCwd";

    private static final Logger log = LoggerFactory.getLogger(ClientWorkspace.class);

    /** Last directory a client reported; {@code null} until a client reports one. */
    private volatile @Nullable Path reportedDirectory;

    /**
     * Records the session directory from a tool call's {@code _meta}, if it carries one. Missing,
     * malformed or non-local values are ignored and keep the previous report. Never throws: it runs
     * in front of every tool call.
     *
     * @param meta the {@code _meta} map of a {@code tools/call} request; {@code null} when absent
     */
    public void observe(@Nullable Map<String, Object> meta) {
        try {
            final Path directory = sessionDirectory(meta);
            if (directory == null) {
                return;
            }
            final Path previous;
            synchronized (this) {
                previous = reportedDirectory;
                reportedDirectory = directory;
            }
            if (!directory.equals(previous)) {
                log.info("[ClientWorkspace] Client reports session directory {}{}", directory,
                    previous == null ? "" : " (was " + previous + ")");
            }
        } catch (RuntimeException e) {
            log.debug("[ClientWorkspace] Ignoring unreadable sandbox state in tool-call _meta", e);
        }
    }

    /**
     * Returns the last directory a client reported.
     *
     * @return the session directory, or {@code null} when no client reported one
     */
    public @Nullable Path reportedDirectory() {
        return this.reportedDirectory;
    }

    /**
     * Publishes a result for an observed directory atomically with respect to new reports.
     * The action must be short and must not perform discovery or invoke the target VM.
     */
    public synchronized boolean publishIfCurrent(@Nullable Path reported, Runnable publish) {
        if (!Objects.equals(reportedDirectory, reported)) {
            return false;
        }
        publish.run();
        return true;
    }

    /**
     * Extracts {@code _meta["codex/sandbox-state"].sandboxCwd} as an existing local directory.
     *
     * @return the directory, or {@code null} when the value is absent, not a local path, or not an
     *         existing directory
     */
    private static @Nullable Path sessionDirectory(@Nullable Map<String, Object> meta) {
        if (meta == null || !(meta.get(CODEX_SANDBOX_STATE) instanceof Map<?, ?> state)
            || !(state.get(SANDBOX_CWD) instanceof String value) || value.isBlank()) {
            return null;
        }
        // Codex sends a file:// URI. A plain absolute path is accepted too; anything else (another
        // scheme, a relative path) cannot name a directory on this host.
        final Path path = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
        if (!path.isAbsolute()) {
            return null;
        }
        final Path normalized = path.normalize();
        // A Codex session running in a remote environment reports a directory of that environment;
        // only a directory that exists here is usable as the local project.
        return Files.isDirectory(normalized) ? normalized : null;
    }
}
