package local.xpeconomy;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuInterface;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Entries in Vystorm Core's player menu (Core 0.43.0+, sneak + right-click a player). Every click reuses the
 * existing command flows: the /pay dialog, the /economy request logic and /economy admin history.
 */
final class EconomyMenuEntries {
    private EconomyMenuEntries() {}

    static void register(PlayerMenuInterface menu, XPVaultEconomy plugin, Lang lang) {
        menu.register(plugin, PlayerMenuEntry.builder("pay")
                .priority(20)
                .visibleWhen((viewer, target) -> canPay(viewer, target, plugin))
                .icon((viewer, target) -> lang.lore(lang.name(new ItemStack(Material.EXPERIENCE_BOTTLE), viewer,
                                "playermenu.pay", "player", target.getName()),
                        viewer, "playermenu.pay-lore", "amount", plugin.formatPoints(viewer, plugin.balance(viewer))))
                .onClick((viewer, target, click) -> {
                    plugin.openTransferTo(viewer, target.getName());
                    return PlayerMenu.Result.CLOSE;
                })
                .build());
        menu.register(plugin, PlayerMenuEntry.builder("request")
                .priority(25)
                .visibleWhen((viewer, target) -> canPay(viewer, target, plugin))
                .icon((viewer, target) -> lang.lore(lang.name(new ItemStack(Material.WRITABLE_BOOK), viewer,
                                "playermenu.request", "player", target.getName()),
                        viewer, "playermenu.request-lore"))
                .onClick((viewer, target, click) -> {
                    plugin.openRequestTo(viewer, target.getName());
                    return PlayerMenu.Result.CLOSE;
                })
                .build());
        menu.register(plugin, PlayerMenuEntry.builder("standing")
                .priority(30)
                .visibleWhen((viewer, target) -> viewer.hasPermission("xpeconomy.standing") && plugin.standingOrdersEnabled()
                        && !viewer.getUniqueId().equals(target.getUniqueId()) && plugin.hasAccount(target.getUniqueId()))
                .icon((viewer, target) -> lang.lore(lang.name(new ItemStack(Material.CLOCK), viewer,
                                "playermenu.standing", "player", target.getName()),
                        viewer, "playermenu.standing-lore"))
                .onClick((viewer, target, click) -> {
                    plugin.openStandingOrderTo(viewer, target.getName());
                    return PlayerMenu.Result.CLOSE;
                })
                .build());
        menu.register(plugin, PlayerMenuEntry.builder("bank-member")
                .priority(35)
                .visibleWhen((viewer, target) -> plugin.canAddBankMember(viewer, target))
                .icon((viewer, target) -> lang.lore(lang.name(new ItemStack(Material.ENDER_CHEST), viewer,
                                "playermenu.bank", "player", target.getName()),
                        viewer, "playermenu.bank-lore"))
                .onClick((viewer, target, click) -> {
                    plugin.openAddBankMember(viewer, target);
                    return PlayerMenu.Result.CLOSE;
                })
                .build());
        menu.register(plugin, PlayerMenuEntry.builder("admin-history")
                .priority(900)
                .visibleWhen((viewer, target) -> viewer.hasPermission("xpeconomy.admin")
                        && plugin.hasAccount(target.getUniqueId()))
                .icon((viewer, target) -> lang.lore(lang.name(new ItemStack(Material.BOOK), viewer,
                                "playermenu.history", "player", target.getName()),
                        viewer, "playermenu.history-lore", "amount", plugin.formatPoints(viewer, plugin.balance(target))))
                .onClick((viewer, target, click) -> {
                    plugin.showAdminHistory(viewer, target.getName());
                    return PlayerMenu.Result.CLOSE;
                })
                .build());
    }

    /** Pay and request need the /pay permission, two different players and an account for the target. */
    static boolean canPay(Player viewer, Player target, XPVaultEconomy plugin) {
        return viewer.hasPermission("xpeconomy.pay") && !viewer.getUniqueId().equals(target.getUniqueId())
                && plugin.hasAccount(target.getUniqueId());
    }
}
