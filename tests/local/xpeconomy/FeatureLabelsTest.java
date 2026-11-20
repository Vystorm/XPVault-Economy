package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

/**
 * {@code /vadmin features} lists every boolean {@code enabled} key and every top-level boolean of config.yml, plus the
 * declared ones. This runs the plugin's real declaration code against a mocked Core config editor and checks what Core
 * actually receives: every listed switch needs a language ref for its name and its description, translated in all
 * seven languages, or the screen falls back to the key path ("Market › Enter button").
 */
class FeatureLabelsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    /** Core's housekeeping names that are never listed as features (ConfigEditorService.NOT_FEATURES). */
    private static final Set<String> NOT_FEATURES = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");
    private static final Pattern REF = Pattern.compile("\\{lang:([^:}]+):([^}]+)}");

    private static boolean listedByCore(String path) {
        if (path.equals("enabled") || path.endsWith(".enabled")) return true;
        return path.indexOf('.') < 0 && !NOT_FEATURES.contains(path.toLowerCase(Locale.ROOT));
    }

    @Test void givenTheRealDeclarationCode_whenCoreListsTheSwitches_thenEachGotATranslatedNameAndDescription() throws Exception {
        ConfigEditorInterface config = mock(ConfigEditorInterface.class);
        Lang lang = mock(Lang.class);
        when(lang.ref(anyString())).thenAnswer(call -> "{lang:xpvaulteconomy:" + call.getArgument(0) + "}");
        Plugin plugin = mock(Plugin.class);
        EconomyConfigEditor.register(config, plugin, lang, () -> { }); EconomyConfigEditor.declareSwitches(config, plugin, lang);
        assertReceived(config, new File("config.yml"), "lang/");
    }

    /**
     * Replays the calls in order with Core 0.66.0 semantics: {@code label(...)} and {@code feature(o, p, l, d)} with
     * any non-null argument set both texts (a null label overwrites an earlier one), {@code feature(o, p)} only
     * declares, {@code notFeature} removes, {@code hidden} hides. Then every switch Core would list must carry
     * {@code {lang:<ns>:<key>}} refs whose keys are non-blank in all seven languages.
     */
    static void assertReceived(ConfigEditorInterface config, File configFile, String langDir) {
        Map<String, String> labels = new HashMap<>(), descriptions = new HashMap<>();
        Map<String, Boolean> declared = new HashMap<>();
        Set<String> hidden = new HashSet<>();
        List<Invocation> calls = new ArrayList<>(mockingDetails(config).getInvocations());
        calls.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        for (Invocation call : calls) {
            Object[] a = call.getArguments();
            switch (call.getMethod().getName()) {
                case "label" -> { labels.put((String) a[1], (String) a[2]); descriptions.put((String) a[1], (String) a[3]); }
                case "feature" -> {
                    declared.put((String) a[1], true);
                    if (a.length == 4 && (a[2] != null || a[3] != null)) { labels.put((String) a[1], (String) a[2]); descriptions.put((String) a[1], (String) a[3]); }
                }
                case "notFeature" -> declared.put((String) a[1], false);
                case "hidden" -> hidden.add((String) a[1]);
                default -> { }
            }
        }
        Map<String, YamlConfiguration> languages = new LinkedHashMap<>();
        for (String code : LANGUAGES) languages.put(code, YamlConfiguration.loadConfiguration(new File(langDir + code + ".yml")));
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(configFile);
        List<String> problems = new ArrayList<>();
        int listed = 0;
        for (String path : defaults.getKeys(true)) {
            if (!defaults.isBoolean(path) || hidden.contains(path)) continue;
            Boolean feature = declared.get(path);
            if (feature == null ? !listedByCore(path) : !feature) continue;
            listed++;
            translated(problems, languages, path, "name", labels.get(path));
            translated(problems, languages, path, "description", descriptions.get(path));
        }
        assertTrue(listed > 0, "no switch listed");
        assertEquals(List.of(), problems);
    }

    private static void translated(List<String> problems, Map<String, YamlConfiguration> languages, String path, String what, String ref) {
        if (ref == null) { problems.add("Core got no " + what + " for " + path + " (it shows the key path)"); return; }
        Matcher m = REF.matcher(ref);
        if (!m.matches()) { problems.add("the " + what + " of " + path + " is no language ref: " + ref); return; }
        for (var language : languages.entrySet())
            if (language.getValue().getString(m.group(2), "").isBlank()) problems.add(language.getKey() + " has no " + what + " " + m.group(2) + " for " + path);
    }
}
