package com.filewatcher.ui.services;

import com.filewatcher.model.WatchJobConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.List;

/**
 * Export/import job configs as JSON — additional feature suggested for
 * backing up or migrating job definitions between environments.
 *
 * IMPORTANT: exported files never contain passwords. {@link WatchJobConfig}
 * instances built from a SNAPSHOT's nested config (contract §1.1) always
 * have blank sourcePassword/destPassword — the backend never sends them
 * back (§2.4) — so this isn't something export/import could work around;
 * it's the same constraint the Edit form already lives with. Imported jobs
 * will need credentials filled in via Edit (or picked from a saved
 * Credential — see JobFormDialog's credential picker) before they can
 * actually connect.
 */
public final class JobConfigIo {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type CONFIG_LIST_TYPE = new TypeToken<List<WatchJobConfig>>() {}.getType();

    private JobConfigIo() {
    }

    /** Opens a Save dialog and writes {@code configs} as a JSON array. Returns false if the user cancelled or the write failed. */
    public static boolean exportTo(Window owner, List<WatchJobConfig> configs) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Jobs");
        chooser.setInitialFileName("jobs-export.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
        java.io.File file = chooser.showSaveDialog(owner);
        if (file == null) return false;

        try {
            Files.writeString(file.toPath(), GSON.toJson(configs));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Opens an Open dialog and parses the selected file as a JSON array of job configs. Returns null if cancelled or unreadable. */
    public static List<WatchJobConfig> importFrom(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Jobs");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
        java.io.File file = chooser.showOpenDialog(owner);
        if (file == null) return null;

        try {
            String json = Files.readString(file.toPath());
            List<WatchJobConfig> parsed = GSON.fromJson(json, CONFIG_LIST_TYPE);
            return parsed != null ? parsed : List.of();
        } catch (Exception e) {
            return null;
        }
    }
}
