package com.filewatcherservice.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Loads AppConfig from services.json, located next to the application
 * executable (for jlink/jpackage deployments).
 *
 * Resolution order for the config directory:
 *   1. The directory containing the running JAR / native image executable,
 *      resolved via CodeSource. This is where jpackage places bundled files.
 *   2. The current working directory, as a fallback for IDE / test runs.
 *
 * Behaviour on failure:
 *   - Missing file  → logs a warning, returns defaults (never throws).
 *   - Malformed JSON → logs an error, returns defaults (never throws).
 *   - Partial JSON  → Gson leaves unrecognised/missing fields at their
 *                     default values, so partial configs are safe.
 *
 * A default services.json is also written next to the exe on first run if
 * none exists, so users have a template to edit.
 */
public class ConfigLoader {

    private static final Logger LOG       = Logger.getLogger(ConfigLoader.class.getName());
    private static final String FILE_NAME = "services.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigLoader() {}

    /**
     * Load and return the AppConfig. Never returns null; falls back to
     * defaults on any error.
     */
    public static AppConfig load() {
        Path configPath = resolveConfigPath();
        LOG.info("Looking for config at: " + configPath.toAbsolutePath());

        if (!Files.exists(configPath)) {
            LOG.warning("services.json not found — using defaults and writing template to: "
                    + configPath.toAbsolutePath());
            writeDefaults(configPath);
            return new AppConfig();
        }

        try (Reader reader = new InputStreamReader(
                Files.newInputStream(configPath), StandardCharsets.UTF_8)) {

            AppConfig config = GSON.fromJson(reader, AppConfig.class);
            if (config == null) {
                LOG.warning("services.json was empty — using defaults");
                return new AppConfig();
            }
            LOG.info("Loaded config: " + config);
            return config;

        } catch (JsonSyntaxException e) {
            LOG.severe("services.json is malformed (" + e.getMessage() + ") — using defaults");
            return new AppConfig();
        } catch (IOException e) {
            LOG.severe("Could not read services.json (" + e.getMessage() + ") — using defaults");
            return new AppConfig();
        }
    }

    // ── Path resolution ───────────────────────────────────────────────────────

    /**
     * Resolve the path to services.json.
     *
     * For a jpackage/jlink native image or fat-JAR deployment the config file
     * sits next to the executable. We find that directory via CodeSource so
     * the path is correct regardless of what the working directory is at
     * launch time.
     *
     * Falls back to the current working directory for IDE / test runs where
     * CodeSource points inside build output rather than a deployment directory.
     */
    private static Path resolveConfigPath() {
        try {
            Path codeLocation = Path.of(
                    ConfigLoader.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());

            // codeLocation is either the JAR file itself or a classes/ directory.
            // We want the parent directory in both cases.
            Path installDir = Files.isDirectory(codeLocation)
                    ? codeLocation
                    : codeLocation.getParent();

            if (installDir != null) {
                return installDir.resolve(FILE_NAME);
            }
        } catch (URISyntaxException | NullPointerException e) {
            LOG.fine("CodeSource resolution failed, falling back to cwd: " + e.getMessage());
        }

        // Fallback: working directory (IDE runs, unit tests)
        return Paths.get(FILE_NAME);
    }

    // ── Default template writer ───────────────────────────────────────────────

    /**
     * Write a default services.json next to the executable so the user has
     * a template to edit. Silently skips if the directory is not writable.
     */
    private static void writeDefaults(Path configPath) {
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            String json = GSON.toJson(new AppConfig());
            Files.writeString(configPath, json, StandardCharsets.UTF_8);
            LOG.info("Default services.json written to: " + configPath.toAbsolutePath());
        } catch (IOException e) {
            LOG.warning("Could not write default services.json: " + e.getMessage());
        }
    }
}
