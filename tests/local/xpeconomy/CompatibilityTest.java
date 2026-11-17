package local.xpeconomy;

import Vystorm.vystorm_core.compat.ServerCompat;
import Vystorm.vystorm_core.singeltons.DialogSingelton;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityTest {
    @Test void currentPaperSelectsNativeDialogs() {
        assertTrue(ServerCompat.hasDialogs());
        assertTrue(java.util.Set.of("DefaultDialogHandler", "ScreenDialogs").contains(DialogSingelton.getInstance().getClass().getSimpleName()));
    }

    @Test void paper1206SelectsChatAndLoadsPluginClasses() throws Exception {
        URL[] urls = Arrays.stream(System.getProperty("compat1206.classpath").split(File.pathSeparator))
                .map(File::new).map(file -> {
                    try { return file.toURI().toURL(); }
                    catch (Exception failure) { throw new IllegalArgumentException(failure); }
                }).toArray(URL[]::new);
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            var compat = loader.loadClass("Vystorm.vystorm_core.compat.ServerCompat");
            assertEquals(false, compat.getMethod("hasDialogs").invoke(null));
            var singleton = loader.loadClass("Vystorm.vystorm_core.singeltons.DialogSingelton");
            assertTrue(java.util.Set.of("LegacyDialogs", "ScreenDialogs").contains(singleton.getMethod("getInstance").invoke(null).getClass().getSimpleName()));
            for (String name : new String[] {"XPVaultEconomy", "AccountMenu", "Banks", "StandingOrders",
                    "PaymentCommands", "StatementCommands", "MenuButtons"}) {
                var type = loader.loadClass("local.xpeconomy." + name);
                assertDoesNotThrow(type::getDeclaredMethods, name);
                assertDoesNotThrow(type::getDeclaredConstructors, name);
            }
        }
    }
}
