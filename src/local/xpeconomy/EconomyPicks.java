package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPick;
import org.bukkit.entity.Player;

import java.util.function.BiConsumer;

/**
 * Everything that names a type of Vystorm Core 0.71.0 (player picker, pick return). Only reached after
 * {@code CoreApi.has("player-picker")}, so the plugin still loads on Core 0.65.0 to 0.70.x.
 */
final class EconomyPicks {
    private EconomyPicks() {}

    /** "Choose player" of the request form on a screen: the pick comes back to the account panel with its values. */
    static Result requestTarget(API core, XPVaultEconomy plugin, Lang lang, PanelAction action) {
        return core.gui().pickers().players(action, pick(plugin, lang, (session, chosen) -> {
            if (session == null) return;
            session.put(AccountPanel.REQ_TARGET, chosen.id().toString());
            session.put(AccountPanel.REQ_TARGET_NAME, chosen.name());
        }));
    }

    /** "Choose player" of the request dialog: back to the dialog with the chosen player and the values typed so far. */
    static void requestTarget(API core, XPVaultEconomy plugin, Lang lang, Player player, String name, String amount, String purpose,
                              AccountMenu.RequestReturn back) {
        PickedPlayer[] chosen = new PickedPlayer[1];
        PlayerPick pick = pick(plugin, lang, (session, picked) -> chosen[0] = picked);
        core.gui().pickers().players(plugin, player, pick, PickReturn.to(p -> {
            if (chosen[0] == null) back.open(p, null, name, amount, purpose);
            else back.open(p, chosen[0].id(), chosen[0].name(), amount, purpose);
        }));
    }

    private static PlayerPick pick(XPVaultEconomy plugin, Lang lang, BiConsumer<Vystorm.vystorm_core.panel.PanelSession, PickedPlayer> chosen) {
        return PlayerPick.builder(lang.ref("client.panel-request-pick-title"))
                .subtitle(lang.ref("client.panel-request-pick-subtitle"))
                .includeSelf(false)
                .filter((viewer, candidate) -> plugin.hasAccount(candidate.id()))
                .single()
                .onPick((viewer, picks) -> {
                    if (picks.chosen().isEmpty()) return Result.ok();
                    chosen.accept(picks.session(), picks.chosen().getFirst());
                    return Result.ok();
                })
                .build();
    }
}
