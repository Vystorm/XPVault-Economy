package localization;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Checks packaged translations using the same YAML loader as the server. */
class LanguageBundleContractTest {
    private static final List<String> LANGUAGES = List.of("fr", "pt_br", "it", "ru");
    private static final Pattern TOKENS = Pattern.compile("\\{[^{}\\n]+}|%[A-Za-z_][A-Za-z0-9_:.]*%|(?<![0-9])%(?:\\d+\\$)?[-+0#]*(?:\\d+)?(?:\\.\\d+)?[dsf](?!\\p{L})|<[^>\\n]+>|[&§][0-9a-fk-orA-FK-OR]");

    private static Map<String, String> load(String path) throws Exception {
        try (InputStream input = LanguageBundleContractTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "Missing packaged resource: " + path);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            Map<String, String> values = new TreeMap<>();
            flatten(yaml, "", values);
            return values;
        }
    }
    private static void flatten(Object value, String path, Map<String, String> out) {
        if (value instanceof ConfigurationSection section) {
            for (String key : section.getKeys(false)) flatten(section.get(key), path + "/" + key, out);
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flatten(v, path + "/" + k, out));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) flatten(list.get(i), path + "/" + i, out);
        } else out.put(path, String.valueOf(value));
    }
    private static Map<String, Long> tokens(String text) {
        Map<String, Long> result = new TreeMap<>();
        TOKENS.matcher(text).results().forEach(m -> result.merge(m.group(), 1L, Long::sum));
        return result;
    }
    private static void compare(String englishPath, String translatedPath) throws Exception {
        Map<String, String> english = load(englishPath), translated = load(translatedPath);
        assertEquals(english.keySet(), translated.keySet(), translatedPath + " keys/list lengths");
        for (var entry : english.entrySet()) {
            String key = entry.getKey(), a = entry.getValue(), b = translated.get(key);
            if (!a.isBlank()) assertFalse(b.isBlank(), translatedPath + key);
            assertEquals(tokens(a), tokens(b), translatedPath + key + " runtime tokens");
            assertFalse(b.contains("\u2581") || b.contains("\ufffd") || b.matches("(?s).*9876\\d{3}.*"), translatedPath + key);
            if (key.contains("/commands/") && (key.endsWith("/name") || key.contains("/aliases/")))
                assertEquals(a, b, translatedPath + key + " canonical command");
        }
    }
    @Test void givenPackagedLanguages_whenLoaded_thenCompleteAndRuntimeTokensPreserved() throws Exception {
        for (String code : LANGUAGES) compare("lang/en.yml", "lang/" + code + ".yml");
    }
    @Test void givenEnglishTutorial_whenPackaged_thenAllFourTranslationsArePresent() throws Exception {
        if (getClass().getClassLoader().getResource("tutorial_en.yml") != null)
            for (String code : LANGUAGES) compare("tutorial_en.yml", "tutorial_" + code + ".yml");
    }
}
