package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.function.Consumer;

/** Portable equivalents of Core's navigation buttons, whose old callbacks reference Paper dialogs. */
final class MenuButtons {
    private MenuButtons() {}

    static DialogButton home(API core) {
        return new DialogButton(core.lang().core().translatable("menu.home"),
                (player, response) -> core.gui().openDirectory(player, 0));
    }

    static DialogButton category(API core, MenuCategory category) {
        return new DialogButton(Component.text("↑").append(core.lang().core().translatable(category.langKey() + ".title")),
                (player, response) -> core.gui().openDirectory(player, 0, category.group()));
    }

    static DialogButton back(API core, Consumer<Player> action) {
        return new DialogButton(core.lang().core().translatable("menu.back"), (player, response) -> action.accept(player));
    }

    static DialogButton settings(API core, String key) {
        return new DialogButton(core.lang().core().translatable("menu.settings-button"), (player, response) -> {
            if (!core.settings().openSection(player, key))
                core.lang().core().send(player, "common.settings-unavailable");
        });
    }
}
