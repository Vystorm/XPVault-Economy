package local.xpeconomy;

import Vystorm.vystorm_core.lang.Lang;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdminModuleTest {
    @Test void dashboardActionsRouteToExistingCommandsWithMatchingPermissions() throws Exception {
        Lang lang = Lang.create("xpvaulteconomy", Map.of("en", Files.readString(Path.of("lang/en.yml")),
                "de", Files.readString(Path.of("lang/de.yml"))));
        var module = XPVaultEconomy.adminModule("XPVaultEconomy", lang);
        assertEquals("economy", module.id(), "replaces Core's built-in catalog entry");
        assertEquals("economy", module.command());
        assertEquals("xpeconomy.admin", module.permission());
        Set<String> adminActions = Set.of("give", "take", "set", "info", "reload");
        for (var action : module.actions()) {
            String[] fixed = action.fixedArguments().split(" ");
            var route = EconomyCommandRoute.parse(fixed);
            assertFalse(route.permission().isEmpty(), action.id());
            if (route.action().equals("admin")) {
                assertTrue(adminActions.contains(fixed[1]), action.id());
                assertEquals("xpeconomy.admin", action.permission(), action.id());
            } else {
                assertEquals(route.permission().equals("xpeconomy.balance") ? "xpeconomy.balance.others" : route.permission(),
                        action.permission(), "dashboard permission matches the command check: " + action.id());
            }
            assertEquals(Set.of("give", "take", "set", "reload").contains(action.id()), action.mutating(), action.id());
            for (String language : new String[]{"en", "de"})
                assertTrue(lang.has("admin.dashboard." + action.id()) && !lang.raw(language, "admin.dashboard." + action.id()).isBlank(),
                        "label for " + action.id() + " in " + language);
        }
    }
}
