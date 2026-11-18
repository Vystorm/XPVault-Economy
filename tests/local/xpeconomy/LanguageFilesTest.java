package local.xpeconomy;

import Vystorm.vystorm_core.lang.Lang;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LanguageFilesTest {
    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static Set<String> keys(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        Set<String> keys = new TreeSet<>();
        for (String key : config.getKeys(true)) if (!config.isConfigurationSection(key)) keys.add(key);
        return keys;
    }

    @Test void englishAndGermanHaveTheSameKeys() throws Exception {
        Set<String> en = keys(read("lang/en.yml"));
        Set<String> de = keys(read("lang/de.yml"));
        assertFalse(en.isEmpty());
        Set<String> missingInDe = new TreeSet<>(en); missingInDe.removeAll(de);
        Set<String> missingInEn = new TreeSet<>(de); missingInEn.removeAll(en);
        assertTrue(missingInDe.isEmpty(), "missing in de.yml: " + missingInDe);
        assertTrue(missingInEn.isEmpty(), "missing in en.yml: " + missingInEn);
    }

    /** zh_cn: same keys as en.yml, the same {placeholders} and the same angle-bracket tags (MiniMessage and literal command arguments). */
    @Test void chineseMatchesEnglish() throws Exception {
        YamlConfiguration en = new YamlConfiguration(); en.loadFromString(read("lang/en.yml"));
        YamlConfiguration zh = new YamlConfiguration(); zh.loadFromString(read("lang/zh_cn.yml"));
        assertEquals(keys(read("lang/en.yml")), keys(read("lang/zh_cn.yml")));
        Pattern placeholder = Pattern.compile("\\{[A-Za-z_]+}|<[^<>]+>");
        for (String key : keys(read("lang/en.yml"))) {
            Set<String> expected = new TreeSet<>(), actual = new TreeSet<>();
            placeholder.matcher(en.getString(key, "")).results().forEach(m -> expected.add(m.group()));
            placeholder.matcher(zh.getString(key, "")).results().forEach(m -> actual.add(m.group()));
            assertEquals(expected, actual, "zh_cn " + key);
        }
        Lang lang = Lang.create("XPVaultEconomy", Map.of("en", read("lang/en.yml"), "zh_cn", read("lang/zh_cn.yml")));
        var plain = PlainTextComponentSerializer.plainText();
        assertEquals("控制台：/economy balance <player>", plain.serialize(lang.component("zh_cn", "balance.console-usage")));
        assertEquals("经验值不足", lang.plain("zh_cn", "error.insufficient", "currency", "经验值"));
    }

    /** Every "section.key" literal in the sources whose section exists in en.yml must be defined there. */
    @Test void everyKeyUsedInTheCodeExists() throws Exception {
        Set<String> en = keys(read("lang/en.yml"));
        Set<String> configPaths = keys(read("config.yml"));
        Set<String> sections = new TreeSet<>();
        for (String key : en) if (key.indexOf('.') > 0) sections.add(key.substring(0, key.indexOf('.'))); // feature-off and feature-needs are top-level
        Pattern literal = Pattern.compile("\"([a-z]+)\\.([a-z0-9.-]+)\"");
        Set<String> missing = new TreeSet<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                // Navigation buttons use Core's language bundle exclusively.
                if (file.getFileName().toString().equals("MenuButtons.java")) continue;
                Matcher m = literal.matcher(Files.readString(file));
                while (m.find()) {
                    if (!sections.contains(m.group(1)) || m.group(2).endsWith("yml")) continue;
                    String key = m.group(1) + "." + m.group(2);
                    if (configPaths.contains(key)) continue;
                    // lang.ref(key + "-description") in currencyName()
                    if (key.startsWith("settings.currency-")) { checked++; if (!en.contains(key + "-description")) missing.add(key + "-description"); }
                    if (!en.contains(key)) missing.add(key);
                    checked++;
                }
            }
        }
        assertTrue(checked > 60, "too few keys found: " + checked);
        assertTrue(missing.isEmpty(), "keys used in code but missing in en.yml: " + missing);
    }

    @Test void textsRenderWithPlaceholdersAndLiteralAngleBrackets() throws Exception {
        Lang lang = Lang.create("XPVaultEconomy", Map.of("en", read("lang/en.yml"), "de", read("lang/de.yml")));
        var plain = PlainTextComponentSerializer.plainText();
        assertEquals("Console: /economy balance <player>", plain.serialize(lang.component("en", "balance.console-usage")));
        assertEquals("Konsole: /economy balance <Spieler>", plain.serialize(lang.component("de", "balance.console-usage")));
        assertEquals("Alex has 1,234 XP points.", plain.serialize(lang.component("en", "balance.line",
                "player", "Alex", "amount", "1,234 XP points")));
        assertEquals("Zeitbonus: +2 XP | Serie: 1 | Nächste Rate: 2,04 XP", plain.serialize(lang.component("de",
                "reward.notification", "xp", 2, "streak", 1, "next_xp", "2,04")));
        assertEquals("Nicht genug XP-Punkte", lang.plain("de", "error.insufficient", "currency", "XP-Punkte"));
        assertEquals("Click: prepare /economy admin <give|take|set>", lang.plain("en", "button.admin-edit-hover"));
    }

    @Test void numbersFollowTheLanguage() {
        assertEquals("1,234", java.text.NumberFormat.getIntegerInstance(XPVaultEconomy.numberLocale("en")).format(1234));
        assertEquals("1.234", java.text.NumberFormat.getIntegerInstance(XPVaultEconomy.numberLocale("de")).format(1234));
        assertEquals("1,234", java.text.NumberFormat.getIntegerInstance(XPVaultEconomy.numberLocale("")).format(1234));
    }

    @Test void tutorialsExistInBothLanguages() throws Exception {
        YamlConfiguration de = new YamlConfiguration(); de.loadFromString(read("tutorial.yml"));
        YamlConfiguration en = new YamlConfiguration(); en.loadFromString(read("tutorial_en.yml"));
        assertEquals(de.getList("pages").size(), en.getList("pages").size());
    }
}
