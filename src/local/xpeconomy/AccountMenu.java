package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Native "XP Account" dialog: balance, online-reward status and a confirmed transfer flow. */
final class AccountMenu {
    static final String MENU_KEY = "konto";
    static final String SETTINGS_KEY = "xpvaulteconomy:server";

    private final XPVaultEconomy plugin;
    private final API core;
    private final Lang lang;

    AccountMenu(XPVaultEconomy plugin, API core) {
        this.plugin = plugin;
        this.core = core;
        this.lang = plugin.lang();
    }

    void register() {
        core.gui().registerMenu(plugin, MENU_KEY, lang.ref("menu.title"), Material.EXPERIENCE_BOTTLE,
                player -> player.hasPermission("xpeconomy.balance") && XPVaultEconomy.enabled(plugin, Feature.ACCOUNT_MENU), this::open,
                MenuCategory.ECONOMY.group(), lang.ref("menu.description"));
        core.gui().menuRequiredPermission(plugin, MENU_KEY, "xpeconomy.balance");
    }

    void open(Player player) {
        open(player, null);
    }

    private void open(Player player, Component notice) {
        if (!player.hasPermission("xpeconomy.balance")) {
            lang.send(player, "common.no-permission");
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) body.add(DialogBody.plainMessage(notice));
        body.add(DialogBody.plainMessage(lang.component(player, "account.balance",
                "amount", plugin.formatPoints(player, plugin.balance(player)))));
        body.add(DialogBody.plainMessage(plugin.onlineRewardStatus(player, player)));
        body.add(DialogBody.plainMessage(lang.component(player, "account.info",
                "limit", plugin.formatPoints(player, plugin.maxBalance()))));
        if (XPVaultEconomy.enabled(plugin, Feature.SAVINGS_GOAL)) body.add(DialogBody.plainMessage(plugin.savingGoalStatus(player)));

        List<DialogButton> buttons = new ArrayList<>();
        if (player.hasPermission("xpeconomy.pay") && XPVaultEconomy.enabled(plugin, Feature.PAYMENTS))
            buttons.add(new DialogButton(lang.component(player, "account.transfer"), lang.component(player, "account.transfer-hover"),
                    (p, response) -> openTransfer(p, "", "", null)));
        if (requestFormOffered(player))
            buttons.add(new DialogButton(lang.component(player, "account.request"), lang.component(player, "account.request-hover"),
                    (p, response) -> openRequestForm(p, null, "", "", "", null)));
        if (player.hasPermission("xpeconomy.top") && XPVaultEconomy.enabled(plugin, Feature.LEADERBOARD))
            buttons.add(new DialogButton(lang.component(player, "account.leaderboard"), lang.component(player, "account.leaderboard-hover"),
                    (p, response) -> openLeaderboard(p, 1)));
        if (player.hasPermission("xpeconomy.bank") && plugin.banksEnabled())
            buttons.add(new DialogButton(lang.component(player, "account.banks"), lang.component(player, "account.banks-hover"),
                    (p, response) -> plugin.openBanks(p)));
        if (player.hasPermission("xpeconomy.standing") && plugin.standingOrdersEnabled())
            buttons.add(new DialogButton(lang.component(player, "account.standing"), lang.component(player, "account.standing-hover"),
                    (p, response) -> plugin.openStandingOrders(p)));
        if (player.hasPermission("xpeconomy.fund") && plugin.fundsEnabled())
            buttons.add(new DialogButton(lang.component(player, "account.fund"), lang.component(player, "account.fund-hover"),
                    (p, response) -> plugin.openFunds(p)));
        if (player.hasPermission("xpeconomy.term") && plugin.termDepositsEnabled())
            buttons.add(new DialogButton(lang.component(player, "account.term"), lang.component(player, "account.term-hover"),
                    (p, response) -> plugin.openTermDeposits(p)));
        if (XPVaultEconomy.enabled(plugin, Feature.STATEMENTS))
            buttons.add(new DialogButton(lang.component(player, "statement.title"), (p, response) -> plugin.openStatement(p)));
        buttons.add(new DialogButton(lang.component(player, "account.refresh"), (p, response) -> open(p)));
        if (plugin.webAvailable())
            buttons.add(new DialogButton(lang.component(player, "account.web"), lang.component(player, "account.web-hover"),
                    (p, response) -> plugin.openWeb(p, "")));
        if (player.hasPermission("xpeconomy.admin")) buttons.add(MenuButtons.settings(core, SETTINGS_KEY));
        buttons.add(MenuButtons.category(core, MenuCategory.ECONOMY));
        buttons.add(MenuButtons.home(core));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "menu.title"), body, List.of(), buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
    }

    private boolean requestFormOffered(Player player) {
        return player.hasPermission("xpeconomy.pay") && XPVaultEconomy.enabled(plugin, Feature.REQUESTS)
                && XPVaultEconomy.enabled(plugin, Feature.PAYMENTS);
    }

    /**
     * "Request XP" for players without a screen (1.28.0): player, XP points and purpose in one dialog. "Choose player"
     * opens Core's player picker (Core 0.71.0) and comes back to this dialog with the values typed so far; without the
     * picker the name is typed in the dialog. "Send request" runs the same code as {@code /economy request}.
     */
    void openRequestForm(Player player, java.util.UUID targetId, String targetName, String amount, String purpose, Component error) {
        if (!requestFormOffered(player)) {
            open(player, lang.component(player, "common.no-permission"));
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogBody.plainMessage(error.colorIfAbsent(NamedTextColor.RED)));
        body.add(DialogBody.plainMessage(lang.component(player, "client.panel-request-intro")));
        List<DialogInput> inputs = List.of(
                DialogInput.text("target", lang.component(player, "client.panel-request-player")).initial(targetName).maxLength(16).build(),
                DialogInput.text("amount", lang.component(player, "transfer.amount",
                        "currency", plugin.currencyName(lang.language(player), false))).initial(amount).maxLength(10).build(),
                DialogInput.text("purpose", lang.component(player, "requests.dialog-purpose")).initial(purpose).maxLength(80).build());
        List<DialogButton> buttons = new ArrayList<>();
        buttons.add(new DialogButton(lang.component(player, "requests.dialog-send"), (p, response) -> {
            String name = String.valueOf(response.getText("target")).trim();
            String points = String.valueOf(response.getText("amount")).trim();
            String text = String.valueOf(response.getText("purpose")).trim();
            // a picked player counts while the name field still shows that player
            java.util.UUID picked = targetId != null && name.equalsIgnoreCase(targetName) ? targetId : null;
            if (picked == null && name.isEmpty()) {
                openRequestForm(p, null, name, points, text, lang.component(p, "client.panel-request-missing-player"));
                return;
            }
            PaymentCommands.Outcome outcome = plugin.createRequest(p, picked, name, points, text);
            Component answer = lang.component(p, outcome.key(), outcome.placeholders());
            if (outcome.ok()) open(p, answer);
            else openRequestForm(p, picked, name, points, text, answer);
        }));
        if (plugin.playerPickerAvailable())
            buttons.add(new DialogButton(lang.component(player, "client.panel-request-choose"), lang.component(player, "client.panel-request-player-hint"),
                    (p, response) -> plugin.pickRequestTarget(p, String.valueOf(response.getText("target")).trim(),
                            String.valueOf(response.getText("amount")).trim(), String.valueOf(response.getText("purpose")).trim(),
                            (back, id, name, points, text) -> openRequestForm(back, id, name, points, text, null))));
        buttons.add(MenuButtons.back(core, this::open));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "client.panel-request"), body, inputs, buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
    }

    /** Where the request dialog goes on after a pick (or Back from the picker). */
    @FunctionalInterface interface RequestReturn {
        void open(Player player, java.util.UUID targetId, String targetName, String amount, String purpose);
    }

    /** The first leaderboard page (account panel "More"). */
    void openLeaderboard(Player player) {
        openLeaderboard(player, 1);
    }

    /** Leaderboard page as a dialog; the ranking is sorted off the main thread and opened when ready. */
    private void openLeaderboard(Player player, int requested) {
        if (plugin.off(player, Feature.LEADERBOARD)) return;
        if (!player.hasPermission("xpeconomy.top")) {
            open(player, lang.component(player, "common.no-permission"));
            return;
        }
        plugin.requestLeaderboard(ranking -> {
            if (!player.isOnline() || !plugin.isEnabled()) return;
            if (ranking == null) {
                open(player, lang.component(player, "top.unavailable"));
                return;
            }
            BalanceRanking.Page page = ranking.page(requested, plugin.topPageSize());
            List<DialogBody> body = new ArrayList<>();
            if (page.entries().isEmpty())
                body.add(DialogBody.plainMessage(lang.component(player, "top.empty")));
            Component lines = Component.empty();
            boolean ownOnPage = false;
            for (BalanceRanking.Entry entry : page.entries()) {
                boolean own = entry.uuid().equals(player.getUniqueId());
                ownOnPage |= own;
                if (entry != page.entries().getFirst()) lines = lines.append(Component.newline());
                lines = lines.append(plugin.leaderboardLine(player, entry, own));
            }
            if (!page.entries().isEmpty()) body.add(DialogBody.plainMessage(lines, 300));
            if (!ownOnPage) ranking.find(player.getUniqueId()).ifPresent(own -> body.add(DialogBody.plainMessage(
                    lang.component(player, "top.own", "line", plugin.leaderboardLine(player, own, true)), 300)));

            List<DialogButton> buttons = new ArrayList<>();
            if (page.page() > 1)
                buttons.add(new DialogButton(lang.component(player, "top.previous"), (p, response) -> openLeaderboard(p, page.page() - 1)));
            if (page.page() < page.pages())
                buttons.add(new DialogButton(lang.component(player, "top.next"), (p, response) -> openLeaderboard(p, page.page() + 1)));
            buttons.add(new DialogButton(lang.component(player, "account.refresh"), (p, response) -> openLeaderboard(p, page.page())));
            buttons.add(MenuButtons.back(core, this::open));
            core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "top.dialog-title",
                    "page", page.page(), "pages", page.pages()), body, List.of(), buttons,
                    p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
        });
    }

    /** The transfer dialog with the recipient already filled in (player menu). */
    void openTransferTo(Player player, String target) {
        openTransfer(player, target, "", null);
    }

    private void openTransfer(Player player, String target, String amount, Component error) {
        openTransfer(player, target, amount, error, null);
    }

    private void openTransfer(Player player, String target, String amount, Component error, java.util.UUID favoriteUuid) {
        if (plugin.off(player, Feature.PAYMENTS)) return;
        if (!player.hasPermission("xpeconomy.pay")) {
            open(player, lang.component(player, "common.no-permission"));
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogBody.plainMessage(error.colorIfAbsent(NamedTextColor.RED)));
        body.add(DialogBody.plainMessage(lang.component(player, "transfer.intro",
                "amount", plugin.formatPoints(player, plugin.balance(player)))));
        List<DialogInput> inputs = List.of(
                DialogInput.text("target", lang.component(player, "transfer.target")).initial(target).maxLength(16).build(),
                DialogInput.text("amount", lang.component(player, "transfer.amount",
                                "currency", plugin.currencyName(lang.language(player), false)))
                        .initial(amount).maxLength(16).build());
        List<DialogButton> buttons = new ArrayList<>();
        for (PersonalEconomyPrefs.Payee favorite : plugin.favorites(player)) {
            buttons.add(new DialogButton(lang.component(player, "favorite.choose", "name", favorite.name()),
                    lang.component(player, "favorite.choose-hover"),
                    (p, response) -> openTransfer(p, favorite.name(), "", null, favorite.uuid())));
        }
        buttons.add(new DialogButton(lang.component(player, "transfer.continue"), (p, response) -> {
                    String name = String.valueOf(response.getText("target")).trim();
                    String text = String.valueOf(response.getText("amount")).trim();
                    Long points = EconomySettings.parseTransferAmount(text, plugin.maxBalance());
                    boolean selectedFavorite = favoriteUuid != null && !name.isBlank()
                            && name.equalsIgnoreCase(plugin.favoriteName(p, favoriteUuid));
                    java.util.UUID confirmedFavorite = selectedFavorite ? favoriteUuid : null;
                    OfflinePlayer recipient = selectedFavorite
                            ? plugin.player(favoriteUuid) : plugin.resolveKnownPlayer(name);
                    Component problem = plugin.checkTransfer(p, recipient, points);
                    if (problem == null && plugin.balance(p) < points)
                        problem = Component.text(plugin.errorText(lang.language(p), "error.insufficient") + ".");
                    if (problem != null) openTransfer(p, name, text, problem, confirmedFavorite);
                    else confirmTransfer(p, recipient, points, confirmedFavorite);
                }));
        buttons.add(MenuButtons.back(core, this::open));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "transfer.title"), body, inputs, buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
    }

    void confirmTransfer(Player player, OfflinePlayer target, long amount) {
        confirmTransfer(player, target, amount, null);
    }

    private void confirmTransfer(Player player, OfflinePlayer target, long amount, java.util.UUID favoriteUuid) {
        String name = plugin.displayName(target);
        long previewBalance = plugin.balance(player);
        XpPaymentPreview preview = XpPaymentPreview.of(previewBalance, amount);
        List<DialogBody> body = List.of(DialogBody.plainMessage(lang.component(player, "transfer.confirm-question",
                        "amount", plugin.formatPoints(player, amount), "player", name)),
                DialogBody.plainMessage(lang.component(player, "transfer.confirm-note")),
                DialogBody.plainMessage(lang.component(player, "transfer.level-preview",
                        "current_level", preview.currentLevel(), "current_progress", preview.currentProgress(),
                        "current_need", preview.currentNeed(), "projected_level", preview.projectedLevel(),
                        "projected_progress", preview.projectedProgress(), "projected_need", preview.projectedNeed(),
                        "current_balance", plugin.formatPoints(player, preview.before()),
                        "projected_balance", plugin.formatPoints(player, preview.after()))));
        List<DialogButton> buttons = List.of(
                new DialogButton(lang.component(player, "transfer.confirm"), (p, response) -> {
                    if (!p.hasPermission("xpeconomy.pay")) {
                        open(p, lang.component(p, "common.no-permission"));
                        return;
                    }
                    // The dialog stays as opened: the online bonus may change the balance while it is open, so the
                    // amount is checked against the balance at the moment of the click instead of reopening the dialog.
                    Component problem = plugin.checkTransfer(p, target, amount);
                    if (problem == null && plugin.balance(p) < amount)
                        problem = Component.text(plugin.errorText(lang.language(p), "error.insufficient"));
                    if (problem == null) problem = plugin.transfer(p, target, amount);
                    if (problem != null) openTransfer(p, name, Long.toString(amount), problem, favoriteUuid);
                    else open(p, lang.component(p, "transfer.done", "amount", plugin.formatPoints(p, amount), "player", name));
                }),
                MenuButtons.back(core, p -> openTransfer(p, name, Long.toString(amount), null, favoriteUuid)));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "transfer.confirm-title"), body,
                List.of(), buttons, p -> p.isOnline() && plugin.isEnabled(), () -> {}, DialogRequest.DEFAULT_CANCEL, 2));
    }
}
