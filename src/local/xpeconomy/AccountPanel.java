package local.xpeconomy;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.PanelsInterface;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The XP account as a native panel of the Vystorm client mod (Core {@code panels}): balance, level, online bonus with a
 * client-side countdown, a payment form and the leaderboard. It replaces the {@code /vmenu} entry of the dialog menu
 * under the same key; players without the mod get the existing {@link AccountMenu} dialog ({@code fallback}). With
 * Core 0.26.4 ({@code panel-extras}) the payment form asks before sending (client 0.9.0; the server checks stay the
 * same) and the settings buttons open Core's settings panel on the right section.
 */
final class AccountPanel {
    static final String NAME = AccountMenu.MENU_KEY;
    static final String RANKING = "ranking";

    /** What the panel needs from the economy (implemented by {@link XPVaultEconomy}; replaceable in tests). */
    interface Account {
        long balance(Player player);
        long maxBalance();
        String formatPoints(String language, long points);
        RewardView rewardView(java.util.UUID player, boolean online);
        int topPageSize();
        void requestLeaderboard(java.util.function.Consumer<BalanceRanking> callback);
        boolean webAvailable();
        void openWeb(Player player, String subPath);
        void openServerSettings(Player player);
        OfflinePlayer resolveKnownPlayer(String name);
        Component checkTransfer(Player payer, OfflinePlayer target, Long amount);
        Component transfer(Player payer, OfflinePlayer target, long amount);
        String errorText(String language, String key);
        String displayName(OfflinePlayer player);
        default PersonalEconomyPrefs.Goal goal(Player player) { return null; }
        default List<PersonalEconomyPrefs.Payee> favorites(Player player) { return List.of(); }
        default OfflinePlayer player(UUID uuid) { return null; }
        /** Settings key of the personal HUD section ({@code plugin:section}), or null without the client HUD. */
        default String hudSettingsKey() { return null; }
        /** Whether the feature behind a "More" button is switched on in the configuration. */
        default boolean featureEnabled(More feature) { return false; }
        /** Opens the existing dialog or list of the feature behind a "More" button. */
        default void openFeature(Player player, More feature) { }
        /** payments.enabled: the pay form. */
        default boolean paymentsEnabled() { return true; }
        /** top.enabled: the leaderboard section. */
        default boolean leaderboardEnabled() { return true; }
        /** account-menu.enabled: the panel in /vmenu and /economy. */
        default boolean accountMenuEnabled() { return true; }
        /** requests.enabled and payments.enabled: the "Request XP" form (1.28.0). */
        default boolean requestFormEnabled() { return false; }
        /** Core's player picker is there (Core 0.71.0, {@code player-picker}); without it the player is typed. */
        default boolean playerPicker() { return false; }
        /** Settings list rows on Vystorm Screens (Core 0.66.0, {@code panel-list-layout}). */
        default boolean listLayout() { return false; }
        /**
         * Opens Core's player picker for the request's player; the pick writes {@link #REQ_TARGET} and
         * {@link #REQ_TARGET_NAME} into the carried session and comes back to this panel. Null when there is no picker.
         */
        default Result pickRequestTarget(PanelAction action) { return null; }
        /** Creates the request like {@code /economy request}; the outcome carries the same text as the chat line. */
        default PaymentCommands.Outcome createRequest(Player payee, UUID target, String targetName, String amount, String purpose) {
            return new PaymentCommands.Outcome(false, "requests.error.storage");
        }
    }

    /** Session values of the "Request XP" form; they survive a pick that returns to the panel. */
    static final String REQ_OPEN = "req-open", REQ_TARGET = "req-target", REQ_TARGET_NAME = "req-target-name",
            REQ_AMOUNT = "req-amount", REQ_PURPOSE = "req-purpose";
    /** Id of the "Request XP" button in the "More" section. */
    static final String MORE_REQUEST = "more-request";

    /**
     * The buttons of the "More" section in display order: features that have their own dialog. Permissions are the ones
     * of the matching {@code /economy} sub-command and of the {@link AccountMenu} dialog buttons.
     */
    enum More {
        TRANSFER("more-transfer", "account.transfer", "xpeconomy.pay"),
        REQUESTS("more-requests", "client.panel-more-requests", "xpeconomy.pay"),
        BANKS("more-banks", "account.banks", "xpeconomy.bank"),
        FUNDS("more-funds", "account.fund", "xpeconomy.fund"),
        STANDING("more-standing", "account.standing", "xpeconomy.standing"),
        TERM("more-term", "account.term", "xpeconomy.term"),
        STATEMENT("more-statement", "statement.title", "xpeconomy.balance"),
        LEADERBOARD("more-leaderboard", "account.leaderboard", "xpeconomy.top");

        final String id;
        final String label;
        final String permission;

        More(String id, String label, String permission) {
            this.id = id;
            this.label = label;
            this.permission = permission;
        }
    }

    /** The "More" buttons this player gets: the feature is enabled and the player has its permission. */
    static List<More> more(java.util.function.Predicate<String> hasPermission, java.util.function.Predicate<More> enabled) {
        List<More> offered = new ArrayList<>();
        for (More feature : More.values())
            if (hasPermission.test(feature.permission) && enabled.test(feature)) offered.add(feature);
        return offered;
    }

    private final org.bukkit.plugin.Plugin owner;
    /** Core's panel part, {@code core.gui().panels()}. */
    private final PanelsInterface panelsPart;
    private final Account plugin;
    private final Lang lang;

    private static final String PENDING_PAY = "pending-pay";
    private static final long PENDING_PAY_MILLIS = 90_000L;

    private record PendingPay(UUID target, String targetName, long amount, long balance, long createdAt) { }

    AccountPanel(org.bukkit.plugin.Plugin owner, PanelsInterface panelsPart, Account account, Lang lang) {
        this.panelsPart = panelsPart;
        this.owner = owner;
        this.plugin = account;
        this.lang = lang;
    }

    private Panels panels() {
        return panelsPart.of(owner);
    }

    void register(AccountMenu fallback) {
        panels().register(NAME, PanelSpec.builder(lang.ref("menu.title"))
                .access(p -> p.hasPermission("xpeconomy.balance") && plugin.accountMenuEnabled())
                .menu(MenuCategory.ECONOMY, Material.EXPERIENCE_BOTTLE, lang.ref("menu.description"))
                .fallback(fallback::open)
                .render(this::render)
                .build());
    }

    void open(Player player) {
        panels().open(player, NAME);
    }

    Panel render(PanelContext ctx) {
        Player player = ctx.player();
        long balance = plugin.balance(player);
        String language = lang.language(player);
        Panel page = Panel.page(ctx.text("menu.title")).icon(Ui.item(Material.EXPERIENCE_BOTTLE)).size(Panel.Size.MEDIUM);
        long[] level = BalanceRanking.levelProgress(balance);
        page.child(Ui.section(ctx.text("client.panel-balance")).children(
                Ui.kv().keyWidth(90)
                        .row(ctx.text("client.panel-balance"), plugin.formatPoints(language, balance))
                        .row(ctx.text("client.panel-level"), Long.toString(level[0]))
                        .row(ctx.text("client.panel-limit"), plugin.formatPoints(language, plugin.maxBalance())),
                Ui.progress(level[1], Math.max(1, level[2])).id("level-progress")
                        .label(level[1] + " / " + level[2])));
        PersonalEconomyPrefs.Goal goal = plugin.goal(player);
        if (goal != null) {
            long progress = Math.min(balance, goal.target());
            Ui.Section goalSection = Ui.section(ctx.text("client.panel-goal"))
                    .child(Ui.text(ctx.text("client.panel-goal-progress", "label", goal.label().isBlank()
                            ? ctx.text("client.panel-goal-default") : goal.label(),
                            "current", plugin.formatPoints(language, balance),
                            "target", plugin.formatPoints(language, goal.target()))))
                    .child(Ui.progress(progress, goal.target()).id("goal-progress")
                            .label(plugin.formatPoints(language, progress) + " / " + plugin.formatPoints(language, goal.target())));
            if (goal.notifyOnReach()) goalSection.child(Ui.text(ctx.text("client.panel-goal-notify")));
            page.child(goalSection);
        }
        page.child(rewardSection(ctx, plugin.rewardView(player.getUniqueId(), true), language));
        if (player.hasPermission("xpeconomy.pay") && plugin.paymentsEnabled()) {
            PendingPay pending = ctx.session().get(PENDING_PAY);
            if (pending != null && System.currentTimeMillis() - pending.createdAt() > PENDING_PAY_MILLIS) {
                ctx.session().remove(PENDING_PAY);
                pending = null;
            }
            if (pending != null) {
                XpPaymentPreview preview = XpPaymentPreview.of(pending.balance(), pending.amount());
                Ui.Section previewSection = Ui.section(ctx.text("client.panel-pay-preview-title"))
                        .child(Ui.text(ctx.text("client.panel-pay-preview", "amount", plugin.formatPoints(language, pending.amount()),
                                "player", pending.targetName(), "current_level", preview.currentLevel(),
                                "projected_level", preview.projectedLevel(), "current_balance", plugin.formatPoints(language, preview.before()),
                                "projected_balance", plugin.formatPoints(language, preview.after()))))
                        .child(Ui.progress(preview.projectedProgress(), Math.max(1L, preview.projectedNeed()))
                                .id("pay-projected-level")
                                .label(preview.projectedProgress() + " / " + preview.projectedNeed()));
                Ui.Button confirm = Ui.button("pay-confirm").label(ctx.text("client.panel-pay-confirm"))
                        .primary().onClick(this::confirmPay);
                Ui.Button cancel = Ui.button("pay-cancel").label(ctx.text("client.panel-pay-cancel"))
                        .ghost().onClick(action -> {
                            action.session().remove(PENDING_PAY);
                            action.refresh();
                            return Result.ok();
                        });
                page.child(previewSection.child(Ui.buttons(confirm, cancel)));
            } else {
                Ui.Form form = Ui.form("pay", ctx.text("client.panel-pay-submit")).children(
                            Ui.input("target", ctx.text("client.panel-pay-target")).chars(Ui.Chars.NAME).maxLength(16),
                            // Integer field, not a number range: the client allows at most 100,000 steps per range.
                            Ui.input("amount", ctx.text("client.panel-pay-amount")).chars(Ui.Chars.INTEGER)
                                    .maxLength(Long.toString(plugin.maxBalance()).length()).value("1"));
                List<PersonalEconomyPrefs.Payee> favorites = plugin.favorites(player);
                if (!favorites.isEmpty()) {
                    Ui.Select select = Ui.select("favorite", ctx.text("client.panel-pay-favorite"))
                            .option("", ctx.text("client.panel-pay-favorite-none")).value("");
                    for (PersonalEconomyPrefs.Payee favorite : favorites)
                        select.option(favorite.uuid().toString(), favorite.name());
                    form.children(select);
                }
                form.onSubmit(this::pay);
                page.child(Ui.section(ctx.text("client.panel-pay")).children(form));
            }
        }
        List<More> more = more(player::hasPermission, plugin::featureEnabled);
        boolean requestForm = requestOffered(player);
        if (!more.isEmpty() || requestForm) {
            List<Ui.Button> buttons = new ArrayList<>();
            for (More feature : more) {
                buttons.add(Ui.button(feature.id).label(ctx.text(feature.label)).onClick(a -> openMore(a, feature)));
                if (feature == More.REQUESTS && requestForm) buttons.add(requestButton(ctx));
            }
            if (requestForm && !more.contains(More.REQUESTS)) buttons.add(requestButton(ctx));
            page.child(Ui.section(ctx.text("client.panel-more")).children(Ui.buttons(buttons.toArray(Ui.Button[]::new)).wrap()));
        }
        if (requestForm && Boolean.TRUE.equals(ctx.session().get(REQ_OPEN))) page.child(requestSection(ctx));
        if (player.hasPermission("xpeconomy.top") && plugin.leaderboardEnabled()) page.child(leaderboard(ctx, language));
        List<Ui.Button> footer = new ArrayList<>();
        footer.add(Ui.button("refresh").label(ctx.text("client.panel-refresh")).onClick(a -> {
            a.session().remove(RANKING);
            a.refresh();
            return Result.ok();
        }));
        if (plugin.webAvailable()) footer.add(Ui.button("web").label(ctx.text("client.panel-web")).onClick(a -> {
            plugin.openWeb(a.player(), "");
            return Result.ok();
        }));
        String hudKey = plugin.hudSettingsKey();
        if (hudKey != null) footer.add(Ui.button("hud-settings").label(ctx.text("client.panel-hud-settings")).openSettings(hudKey));
        if (player.hasPermission("xpeconomy.admin")) footer.add(Ui.button("settings").label(ctx.text("client.panel-settings")).openSettings(AccountMenu.SETTINGS_KEY));
        return page.footer(footer.toArray(Ui.Button[]::new));
    }

    /** A "More" button: re-checks feature and permission, closes the panel and hands over to the feature's dialog. */
    Result openMore(PanelAction action, More feature) {
        Player player = action.player();
        if (!player.hasPermission(feature.permission) || !plugin.featureEnabled(feature))
            return Result.error(lang.plain(player, "common.no-permission"));
        action.closeAndThen(next -> plugin.openFeature(next, feature));
        return Result.ok();
    }

    // ------------------------------------------------------------------ "Request XP" (1.28.0)

    /** The form is offered: permission of /economy request, requests.enabled and payments.enabled. */
    boolean requestOffered(Player player) {
        return player.hasPermission("xpeconomy.pay") && plugin.requestFormEnabled();
    }

    private Ui.Button requestButton(PanelContext ctx) {
        return Ui.button(MORE_REQUEST).label(ctx.text("client.panel-more-request")).tooltip(ctx.text("client.panel-request-intro"))
                .onClick(this::openRequest);
    }

    /** "Request XP" in "More": shows the form in this panel (values already typed stay). */
    Result openRequest(PanelAction action) {
        if (!requestOffered(action.player())) return Result.error(lang.plain(action.player(), "common.no-permission"));
        action.session().put(REQ_OPEN, Boolean.TRUE);
        action.refresh();
        return Result.ok();
    }

    /**
     * Player, XP points and purpose as one form. Each row keeps its value in the panel session, so a trip to Core's
     * player picker comes back with everything typed so far; "Send request" runs the same code as
     * {@code /economy request PLAYER XP PURPOSE}.
     */
    private Ui.Section requestSection(PanelContext ctx) {
        PanelSession session = ctx.session();
        Ui.Section section = Ui.section(ctx.text("client.panel-request"));
        if (plugin.listLayout()) section.list();
        section.child(Ui.text(ctx.text("client.panel-request-intro")).style(Ui.TextStyle.MUTED));
        String targetName = session.get(REQ_TARGET_NAME);
        if (plugin.playerPicker()) {
            section.child(Ui.button("request-player")
                    .label(targetName == null ? ctx.text("client.panel-request-choose") : ctx.text("client.panel-request-player-chosen", "player", targetName))
                    .icon(Ui.item(Material.PLAYER_HEAD)).tooltip(ctx.text("client.panel-request-player-hint"))
                    .onClick(this::pickRequestTarget));
        } else {
            // Core before 0.71.0: the name is typed (a text window on Vystorm Screens)
            section.child(Ui.input("request-target", ctx.text("client.panel-request-player")).chars(Ui.Chars.NAME).maxLength(16)
                    .value(targetName == null ? "" : targetName).placeholder(ctx.text("client.panel-request-player-type"))
                    .onChange((a, value) -> keep(a, REQ_TARGET_NAME, value)));
        }
        String amount = session.get(REQ_AMOUNT);
        section.child(Ui.input("request-amount", ctx.text("client.panel-request-amount")).chars(Ui.Chars.INTEGER)
                .maxLength(10).value(amount == null ? "" : amount)
                .onChange((a, value) -> keep(a, REQ_AMOUNT, value)));
        String purpose = session.get(REQ_PURPOSE);
        section.child(Ui.input("request-purpose", ctx.text("client.panel-request-purpose")).maxLength(80)
                .value(purpose == null ? "" : purpose).placeholder(ctx.text("client.panel-request-purpose-hint"))
                .tooltip(ctx.text("client.panel-request-purpose-hint"))
                .onChange((a, value) -> keep(a, REQ_PURPOSE, value)));
        section.child(Ui.button("request-send").label(ctx.text("client.panel-request-send")).primary().onClick(this::sendRequest));
        section.child(Ui.button("request-close").label(ctx.text("client.panel-request-close")).ghost().onClick(a -> {
            clearRequest(a.session());
            a.refresh();
            return Result.ok();
        }));
        return section;
    }

    private static Result keep(PanelAction action, String key, String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) action.session().remove(key);
        else action.session().put(key, text);
        // a typed name replaces a picked player
        if (key.equals(REQ_TARGET_NAME)) action.session().remove(REQ_TARGET);
        action.refresh();
        return Result.ok();
    }

    private static void clearRequest(PanelSession session) {
        for (String key : List.of(REQ_OPEN, REQ_TARGET, REQ_TARGET_NAME, REQ_AMOUNT, REQ_PURPOSE)) session.remove(key);
    }

    /** "Choose player": Core's picker, back to this panel with the form's values. */
    Result pickRequestTarget(PanelAction action) {
        if (!requestOffered(action.player())) return Result.error(lang.plain(action.player(), "common.no-permission"));
        Result picking = plugin.pickRequestTarget(action);
        return picking != null ? picking : Result.error(lang.plain(action.player(), "client.panel-request-no-picker"));
    }

    /** "Send request": the same code as /economy request; on success the form closes. */
    Result sendRequest(PanelAction action) {
        Player payee = action.player();
        if (!requestOffered(payee)) return Result.error(lang.plain(payee, "common.no-permission"));
        PanelSession session = action.session();
        String targetName = session.get(REQ_TARGET_NAME);
        String targetId = session.get(REQ_TARGET);
        if (targetName == null && targetId == null) return Result.error(lang.plain(payee, "client.panel-request-missing-player"));
        UUID id = null;
        if (targetId != null) {
            try { id = UUID.fromString(targetId); } catch (IllegalArgumentException ignored) { }
        }
        PaymentCommands.Outcome outcome = plugin.createRequest(payee, id, targetName, session.get(REQ_AMOUNT), session.get(REQ_PURPOSE));
        String text = lang.plain(payee, outcome.key(), outcome.placeholders());
        if (!outcome.ok()) return Result.error(text);
        clearRequest(session);
        action.refresh();
        return Result.ok(text);
    }

    private Ui.Section rewardSection(PanelContext ctx, RewardView reward, String language) {
        Ui.Section section = Ui.section(ctx.text("client.panel-reward"));
        switch (reward.state()) {
            case "active" -> section.children(
                    Ui.text(ctx.text("client.panel-reward-next", "next_xp", XPVaultEconomy.formatReward(language, reward.nextXp()),
                            "streak", reward.streak())),
                    Ui.timer(reward.remainingSeconds() * 1000L, reward.intervalSeconds() * 1000L).id("reward-timer").bar());
            case "afk" -> section.children(Ui.text(ctx.text("reward.status-afk", "streak", reward.streak())));
            case "disabled" -> section.children(Ui.text(ctx.text("reward.status-disabled")));
            default -> section.children(Ui.text(ctx.text("reward.status-none")));
        }
        return section;
    }

    private Ui.Section leaderboard(PanelContext ctx, String language) {
        Ui.Section section = Ui.section(ctx.text("client.panel-top")).collapsible(false);
        BalanceRanking ranking = ctx.session().get(RANKING);
        if (ranking == null) {
            requestRanking(ctx);
            return section.children(Ui.text(ctx.text("client.panel-top-loading")));
        }
        BalanceRanking.Page top = ranking.page(1, plugin.topPageSize());
        java.util.UUID own = ctx.player().getUniqueId();
        section.child(Ui.table("top")
                .column("rank", ctx.text("client.panel-top-rank")).columnWidth(28).alignEnd()
                .column("name", ctx.text("client.panel-top-name"))
                .column("amount", ctx.text("client.panel-top-amount")).alignEnd()
                .rows(top.entries(), entry -> Ui.row("r" + entry.rank())
                        .cell(Integer.toString(entry.rank()))
                        .cell(entry.uuid().equals(own) ? Component.text(entry.name()).color(
                                net.kyori.adventure.text.format.NamedTextColor.GREEN) : entry.name())
                        .cell(Ui.cell(plugin.formatPoints(language, entry.balance())).sort(entry.balance())))
                .empty(ctx.text("top.empty")));
        boolean ownOnPage = top.entries().stream().anyMatch(entry -> entry.uuid().equals(own));
        if (!ownOnPage) ranking.find(own).ifPresent(entry -> section.child(Ui.text(ctx.text("client.panel-top-own",
                "rank", entry.rank(), "amount", plugin.formatPoints(language, entry.balance())))));
        return section;
    }

    /** Sorting runs off the server thread; the result is kept in the panel session and the panel re-rendered. */
    private void requestRanking(PanelContext ctx) {
        if (ctx.session().get("ranking-requested") != null) return;
        ctx.session().put("ranking-requested", Boolean.TRUE);
        Player player = ctx.player();
        var session = ctx.session();
        plugin.requestLeaderboard(ranking -> {
            session.remove("ranking-requested");
            if (ranking == null || !player.isOnline()) return;
            session.put(RANKING, ranking);
            panels().refresh(player, NAME);
        });
    }

    /** Whole XP points 1..max from the form (text field, or a number from older documents); else null. */
    static Long amount(Object raw, long max) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return value == Math.rint(value) && value >= 1 && value <= max ? number.longValue() : null;
        }
        if (raw instanceof String text) {
            try {
                long value = Long.parseLong(text.trim());
                return value >= 1 && value <= max ? value : null;
            } catch (NumberFormatException notAWholeNumber) {
                return null;
            }
        }
        return null;
    }

    /** First step only validates and stores an expiring preview; no balance moves before the explicit button. */
    Result pay(PanelAction action, Map<String, Object> values) {
        Player payer = action.player();
        if (!payer.hasPermission("xpeconomy.pay")) return Result.error(lang.plain(payer, "common.no-permission"));
        String name = String.valueOf(values.getOrDefault("target", "")).trim();
        Long amount = amount(values.get("amount"), plugin.maxBalance());
        OfflinePlayer target = null;
        String favoriteId = String.valueOf(values.getOrDefault("favorite", "")).trim();
        if (!favoriteId.isEmpty()) {
            try {
                UUID uuid = UUID.fromString(favoriteId);
                if (plugin.favorites(payer).stream().anyMatch(favorite -> favorite.uuid().equals(uuid)))
                    target = plugin.player(uuid);
            } catch (IllegalArgumentException ignored) { }
        }
        if (target == null && !name.isEmpty()) target = plugin.resolveKnownPlayer(name);
        Component problem = plugin.checkTransfer(payer, target, amount);
        if (problem == null && plugin.balance(payer) < amount)
            problem = Component.text(plugin.errorText(lang.language(payer), "error.insufficient"));
        if (problem != null) {
            String text = PlainTextComponentSerializer.plainText().serialize(problem);
            return target == null ? Result.fieldErrors(Map.of("target", text)) : Result.error(text);
        }
        long currentBalance = plugin.balance(payer);
        action.session().put(PENDING_PAY, new PendingPay(target.getUniqueId(), plugin.displayName(target), amount,
                currentBalance, System.currentTimeMillis()));
        action.refresh();
        XpPaymentPreview preview = XpPaymentPreview.of(currentBalance, amount);
        return Result.ok(PlainTextComponentSerializer.plainText().serialize(lang.component(payer, "transfer.preview",
                "amount", plugin.formatPoints(lang.language(payer), amount), "player", plugin.displayName(target),
                "current_level", preview.currentLevel(), "projected_level", preview.projectedLevel(),
                "current_progress", preview.currentProgress(), "current_need", preview.currentNeed(),
                "projected_progress", preview.projectedProgress(), "projected_need", preview.projectedNeed())));
    }

    Result confirmPay(PanelAction action) {
        Player payer = action.player();
        if (!payer.hasPermission("xpeconomy.pay")) return Result.error(lang.plain(payer, "common.no-permission"));
        PendingPay pending = action.session().get(PENDING_PAY);
        if (pending == null || System.currentTimeMillis() - pending.createdAt() > PENDING_PAY_MILLIS) {
            action.session().remove(PENDING_PAY);
            action.refresh();
            return Result.error(lang.plain(payer, "transfer.preview-expired"));
        }
        OfflinePlayer target = plugin.player(pending.target());
        Component problem = plugin.checkTransfer(payer, target, pending.amount());
        long balance = plugin.balance(payer);
        if (problem == null && balance < pending.amount())
            problem = Component.text(plugin.errorText(lang.language(payer), "error.insufficient"));
        if (problem != null) {
            action.session().remove(PENDING_PAY);
            action.refresh();
            return Result.error(PlainTextComponentSerializer.plainText().serialize(problem));
        }
        if (balance != pending.balance()) {
            action.session().put(PENDING_PAY, new PendingPay(pending.target(), plugin.displayName(target),
                    pending.amount(), balance, System.currentTimeMillis()));
            action.refresh();
            return Result.error(lang.plain(lang.language(payer), "transfer.preview-changed"));
        }
        problem = plugin.transfer(payer, target, pending.amount());
        action.session().remove(PENDING_PAY);
        action.refresh();
        if (problem != null) return Result.error(PlainTextComponentSerializer.plainText().serialize(problem));
        return Result.ok(PlainTextComponentSerializer.plainText().serialize(lang.component(payer, "transfer.done",
                "amount", plugin.formatPoints(lang.language(payer), pending.amount()), "player", plugin.displayName(target))));
    }
}
