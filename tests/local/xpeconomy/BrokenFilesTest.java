package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml or data file with an error is never saved over, neither at start nor on /economy admin reload. */
class BrokenFilesTest {
    static final String BROKEN = "economy:\n  starting-balance: 0\n  currency-singular: XP\n broken here\nonline-rewards:\n  enabled: true\n";

    @TempDir Path folder;
    final List<LogRecord> logged = new ArrayList<>();

    XPVaultEconomy plugin(Lang lang) throws Exception {
        XPVaultEconomy plugin = mock(XPVaultEconomy.class, CALLS_REAL_METHODS);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(logger).when(plugin).getLogger();
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        doNothing().when(plugin).reloadConfig();
        doNothing().when(plugin).saveConfig();
        Field field = XPVaultEconomy.class.getDeclaredField("lang");
        field.setAccessible(true);
        field.set(plugin, lang);
        return plugin;
    }

    @Test void givenBrokenConfig_whenAdminReloads_thenTheFileAndSettingsStayAndTheAnswerNamesLineAndColumn() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        Lang lang = mock(Lang.class);
        XPVaultEconomy plugin = plugin(lang);
        CommandSender admin = mock(CommandSender.class);
        Method command = XPVaultEconomy.class.getDeclaredMethod("adminCommand", CommandSender.class, String[].class);
        command.setAccessible(true);
        command.invoke(plugin, admin, new String[]{"reload"});
        verify(lang).send(eq(admin), eq("config-error"), eq("problem"), argThat(p -> p.toString().startsWith("line 4, column ")));
        verify(lang, never()).send(admin, "admin.reloaded");
        verify(plugin, never()).reloadConfig();
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(1, logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count());
        // start: the same check, Bukkit then serves the jar defaults; nothing is written
        assertNotNull(YamlCheck.readConfig(plugin, false));
        assertArrayEquals(before, Files.readAllBytes(file));
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            assertTrue(YamlConfiguration.loadConfiguration(new File("lang/" + code + ".yml")).getString("config-error", "").contains("{problem}"), code);
    }

    @Test void givenBrokenConfig_whenTheSettingsMenuSaves_thenTheFileIsNotOverwritten() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        XPVaultEconomy plugin = plugin(mock(Lang.class));
        assertFalse(YamlCheck.saveConfig(plugin));
        verify(plugin, never()).saveConfig();
        assertEquals(BROKEN, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test void givenUnreadableAwayIncome_whenAPaymentIsRecorded_thenTheOldFileWasCopiedAsideFirst() throws Exception {
        Path file = folder.resolve("away-income.json");
        Files.writeString(file, "{\"schema\":1,\"players\":{", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        AwayIncome income = new AwayIncome(file, AtomicFilesInterface.DEFAULT);
        assertTrue(income.record(UUID.randomUUID(), "Alex", 5));
        try (Stream<Path> files = Files.list(folder)) {
            Path copy = files.filter(p -> p.getFileName().toString().startsWith("away-income.json.broken-")).findFirst().orElseThrow(() -> new AssertionError("no copy"));
            assertArrayEquals(before, Files.readAllBytes(copy));
        }
    }

    @Test void givenBrokenYaml_whenDescribed_thenLineColumnAndProblemAreNamed() throws Exception {
        Path file = folder.resolve("x.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        assertTrue(YamlCheck.problem(file.toFile()).matches("line 4, column \\d+: .+"), YamlCheck.problem(file.toFile()));
    }
}
