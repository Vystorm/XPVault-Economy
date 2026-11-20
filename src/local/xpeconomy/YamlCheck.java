package local.xpeconomy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A YAML file with a syntax error must never be saved over. Bukkit's {@code reloadConfig()} logs the error and leaves
 * an EMPTY config, so anything saved afterwards replaces the admin's file. This loads the file strictly first.
 */
final class YamlCheck {
    private static final Pattern MARK = Pattern.compile("line (\\d+), column (\\d+)");

    private YamlCheck() {}

    /** Null for a missing or valid file, else "line L, column C: problem" (SnakeYAML's last mark is the problem). */
    static String problem(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            new YamlConfiguration().load(file);
            return null;
        } catch (InvalidConfigurationException invalid) {
            return describe(invalid);
        } catch (IOException | RuntimeException unreadable) {
            return String.valueOf(unreadable.getMessage());
        }
    }

    static String describe(Throwable failure) {
        String message = String.valueOf(failure.getMessage());
        String[] lines = message.split("\\R");
        String at = null, problem = null, previous = null;
        for (String line : lines) {
            Matcher mark = MARK.matcher(line);
            if (mark.find()) { at = "line " + mark.group(1) + ", column " + mark.group(2); problem = previous; continue; }
            String text = line.strip();
            if (!text.isEmpty() && !text.equals("^") && !line.startsWith("    ")) previous = text.replaceFirst("^[\\w.$]+(Exception|Error):\\s*", "");
        }
        if (at == null) return message.strip().replaceFirst("^[\\w.$]+(Exception|Error):\\s*", "");
        return problem == null || problem.isBlank() ? at : at + ": " + problem;
    }

    /**
     * The first step of start AND reload: reads {@code config.yml} unless it has a YAML error. A broken file is left
     * untouched and reported (SEVERE, once per call); on a reload nothing is read, so the last good settings stay
     * active; at start Bukkit's defaults from the jar are used. Returns the problem, or null when the file was read.
     */
    static String readConfig(JavaPlugin plugin, boolean reload) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        String problem = problem(file);
        if (problem == null) {
            plugin.reloadConfig();
            return null;
        }
        plugin.getLogger().severe("config.yml has a YAML error at " + problem + ". The file was not changed; "
                + (reload ? "the last good settings stay active." : "the plugin runs with its default settings.")
                + " Fix the file and reload.");
        return problem;
    }

    /**
     * {@code saveConfig()} for the settings menu and migrations: a config.yml with a YAML error is never saved over
     * (the in-memory config then holds only the jar defaults); the change stays active until the next restart.
     * Returns true when the file was written.
     */
    static boolean saveConfig(JavaPlugin plugin) {
        String problem = problem(new File(plugin.getDataFolder(), "config.yml"));
        if (problem == null) { plugin.saveConfig(); return true; }
        plugin.getLogger().severe("config.yml has a YAML error at " + problem + "; the change was not saved to the file. Fix the file and reload.");
        return false;
    }

    /**
     * Loads a data file the plugin writes again later. A file with a YAML error is first copied aside
     * ({@link #backup}), so the next save cannot lose it, and an empty configuration is returned.
     */
    static YamlConfiguration loadData(Path file, Logger log) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file == null || !Files.isRegularFile(file)) return yaml;
        if (backup(file, log) != null) return yaml;
        try { yaml.load(file.toFile()); }
        catch (IOException | InvalidConfigurationException | RuntimeException unreadable) {
            log.severe(file.getFileName() + " could not be read (" + unreadable.getMessage() + ").");
        }
        return yaml;
    }

    /**
     * Before a data file that failed to parse is written again: copies it to {@code <name>.broken-<yyyyMMdd-HHmmss>}
     * and logs that. Returns the copy, or null when the file is missing or valid.
     */
    static Path backup(Path file, Logger log) {
        String problem = problem(file.toFile());
        return problem == null ? null : copyAside(file, log, "has a YAML error at " + problem);
    }

    /** Copies an unreadable data file to {@code <name>.broken-<yyyyMMdd-HHmmss>} and logs why; null when the copy failed. */
    static Path copyAside(Path file, Logger log, String why) {
        if (file == null || !Files.isRegularFile(file)) return null;
        Path copy = file.resolveSibling(file.getFileName() + ".broken-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        try {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            log.severe(file.getFileName() + " " + why + "; it was copied to " + copy.getFileName() + " before it is written again.");
            return copy;
        } catch (IOException failure) {
            log.severe(file.getFileName() + " " + why + " and could not be copied (" + failure.getMessage() + ").");
            return null;
        }
    }
}
