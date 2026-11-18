package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /economy standing}: recurring payments to a player (rent, wages, subscriptions). The payer confirms once;
 * every run is a normal player payment (personal limits, booking history, refund when the payee is full) that also
 * works while the payer is offline. Runs are checked every 30 seconds on the server thread.
 */
final class StandingOrders {
    private final XPVaultEconomy plugin;
    private final API core;
    private final StandingOrderBook book;
    private final Lang lang;

    StandingOrders(XPVaultEconomy plugin, API core, StandingOrderBook book) {
        this.plugin = plugin;
        this.core = core;
        this.book = book;
        this.lang = plugin.lang();
    }

    StandingOrderBook book() { return book; }

    boolean enabled() { return plugin.getConfig().getBoolean("standing-orders.enabled", true); }
    int maxPerPlayer() { return Math.max(0, Math.min(100, plugin.getConfig().getInt("standing-orders.max-per-player", 10))); }
    long minimumInterval() { return Math.max(1, Math.min(525_600, plugin.getConfig().getLong("standing-orders.minimum-interval-minutes", 60))) * StandingOrderBook.MINUTE; }
    int pauseAfter() { return Math.max(0, Math.min(100, plugin.getConfig().getInt("standing-orders.pause-after-failures", 3))); }

    /** Timer task: pays every due order and tells the online people involved. */
    void runDue() {
        if (!enabled() || !book.readable() || !plugin.isEnabled()) return;
        var outcomes = book.runDue(System.currentTimeMillis(), order -> {
            try { return plugin.standingPayment(order); }
            catch (RuntimeException failure) {
                // The date is already advanced, so this run is never repeated automatically.
                plugin.getLogger().severe("Standing order #" + order.id() + " needs an admin's review: " + failure);
                return "standing.error.review";
            }
        }, pauseAfter(), plugin.getLogger()::warning);
        for (StandingOrderBook.Outcome outcome : outcomes) notify(outcome);
    }

    private void notify(StandingOrderBook.Outcome outcome) {
        StandingOrderBook.Order order = outcome.order();
        Player payer = plugin.player(order.payer()).getPlayer();
        Player payee = plugin.player(order.payee()).getPlayer();
        String payeeName = plugin.displayName(plugin.player(order.payee()));
        if (outcome.error() == null) {
            if (payer != null) lang.send(payer, outcome.finished() ? "standing.paid-last" : "standing.paid", "id", order.id(),
                    "amount", plugin.formatPoints(payer, order.amount()), "player", payeeName);
            if (payee != null) core.compat().platform().sendMessage(payee, lang.component(payee, "standing.received", "amount", plugin.formatPoints(payee, order.amount()),
                    "player", plugin.displayName(plugin.player(order.payer()))).append(Component.space()).append(plugin.accountButton(payee)));
        } else if (payer != null) {
            core.compat().platform().sendMessage(payer, lang.component(payer, outcome.paused() ? "standing.failed-paused" : "standing.failed", "id", order.id(),
                    "player", payeeName, "reason", lang.component(payer, outcome.error())).colorIfAbsent(NamedTextColor.RED)
                    .append(Component.space()).append(listButton(payer)));
        }
    }

    /** On join: tells the payer about orders that were paused after failed payments while away. */
    void join(Player player) {
        if (!enabled() || !book.readable()) return;
        long paused = book.of(player.getUniqueId()).stream().filter(o -> o.paused() && o.lastError() != null).count();
        if (paused > 0) core.compat().platform().sendMessage(player, lang.component(player, "standing.join-paused", "count", paused)
                .append(Component.space()).append(listButton(player)));
    }

    boolean command(CommandSender sender, String[] args) {
        if (!enabled()) { lang.send(sender, "feature-off"); return true; }
        if (!book.readable()) { lang.send(sender, "standing.error.storage"); return true; }
        if (!(sender instanceof Player player)) { lang.send(sender, "common.players-only"); return true; }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "" -> openList(player, null);
            case "list", "liste" -> list(player);
            case "add", "neu" -> {
                if (args.length < 4 || args.length > 5) { lang.send(player, "standing.usage"); return true; }
                String error = prepare(player, args[1], args[2], args[3], args.length == 5 ? args[4] : "");
                if (error != null) core.compat().platform().sendMessage(player, lang.component(player, error, "min", StandingOrderBook.formatInterval(minimumInterval()),
                        "max", maxPerPlayer()).colorIfAbsent(NamedTextColor.RED));
            }
            case "cancel", "pause", "resume", "löschen", "pausieren", "fortsetzen" -> {
                Integer id = args.length == 2 && args[1].replace("#", "").matches("\\d{1,9}") ? Integer.valueOf(args[1].replace("#", "")) : null;
                if (id == null) { lang.send(player, "standing.usage"); return true; }
                String error = change(player, id, action);
                if (error != null) lang.send(player, error);
            }
            default -> lang.send(player, "standing.usage");
        }
        return true;
    }

    /** Validates a new order and asks for confirmation; returns an error key or null. */
    private String prepare(Player player, String targetName, String amountText, String intervalText, String runsText) {
        OfflinePlayer target = plugin.resolveKnownPlayer(targetName == null ? "" : targetName.trim());
        if (target == null || !plugin.hasAccount(target.getUniqueId())) return "common.unknown-account";
        if (target.getUniqueId().equals(player.getUniqueId())) return "pay.self";
        Long amount = plugin.parsePoints(amountText == null ? "" : amountText.trim());
        if (amount == null || amount <= 0) return "standing.error.amount";
        Long interval = StandingOrderBook.parseInterval(intervalText, minimumInterval());
        if (interval == null) return "standing.error.interval";
        Integer runs = StandingOrderBook.parseRuns(runsText);
        if (runs == null) return "standing.error.runs";
        if (book.of(player.getUniqueId()).size() >= maxPerPlayer()) return "standing.error.capacity";
        if (plugin.balance(player) < amount) return "standing.error.insufficient";
        String name = plugin.displayName(target);
        String every = StandingOrderBook.formatInterval(interval);
        String count = runs < 0 ? lang.plain(player, "standing.runs-unlimited") : lang.plain(player, "standing.runs-count", "runs", runs);
        core.dialogs().confirm(plugin, player, lang.plain(player, "standing.confirm-title"),
                lang.plain(player, "standing.confirm", "amount", plugin.formatPoints(player, amount), "player", name, "interval", every, "runs", count),
                () -> create(player, target, amount, interval, runs), () -> {}, p -> p.isOnline() && plugin.isEnabled());
        return null;
    }

    /** Saves the confirmed order and pays its first run right away. */
    private void create(Player player, OfflinePlayer target, long amount, long interval, int runs) {
        if (!player.hasPermission("xpeconomy.standing")) { lang.send(player, "common.no-permission"); return; }
        StandingOrderBook.Order order;
        long now = System.currentTimeMillis();
        try { order = book.create(player.getUniqueId(), target.getUniqueId(), amount, interval, runs, now, now, maxPerPlayer()); }
        catch (IOException failure) {
            String code = failure.getMessage();
            lang.send(player, "capacity".equals(code) ? "standing.error.capacity" : "self".equals(code) ? "pay.self" : "standing.error.storage", "max", maxPerPlayer());
            return;
        }
        lang.send(player, "standing.created", "id", order.id(), "amount", plugin.formatPoints(player, amount),
                "player", plugin.displayName(target), "interval", StandingOrderBook.formatInterval(interval));
        runDue();
        // The first run moved XP outside the timer, which saves the accounts itself: save them soon here too.
        plugin.changed();
    }

    private String change(Player player, int id, String action) {
        boolean admin = player.hasPermission("xpeconomy.admin");
        try {
            switch (action) {
                case "cancel", "löschen" -> { book.cancel(player.getUniqueId(), id, admin); lang.send(player, "standing.cancelled", "id", id); }
                case "pause", "pausieren" -> { book.pause(player.getUniqueId(), id, true, admin); lang.send(player, "standing.paused", "id", id); }
                default -> { book.pause(player.getUniqueId(), id, false, admin); lang.send(player, "standing.resumed", "id", id); }
            }
            return null;
        } catch (IOException failure) {
            return "unknown".equals(failure.getMessage()) ? "standing.error.unknown" : "standing.error.storage";
        }
    }

    // ---- output ----

    private Component describe(CommandSender viewer, StandingOrderBook.Order order) {
        long now = System.currentTimeMillis();
        String state = order.paused() ? lang.plain(viewer, "standing.state-paused")
                : lang.plain(viewer, "standing.state-next", "time", remaining(Math.max(0, order.nextDue() - now)));
        String runs = order.runsLeft() < 0 ? lang.plain(viewer, "standing.runs-unlimited") : lang.plain(viewer, "standing.runs-count", "runs", order.runsLeft());
        Component line = lang.component(viewer, "standing.row", "id", order.id(), "player", plugin.displayName(plugin.player(order.payee())),
                "amount", plugin.formatPoints(viewer, order.amount()), "interval", StandingOrderBook.formatInterval(order.intervalMillis()),
                "runs", runs, "state", state);
        if (order.lastError() != null) line = line.append(Component.space()).append(lang.component(viewer, "standing.last-error",
                "reason", lang.component(viewer, order.lastError())));
        return line;
    }

    /** "2d 3h", "4h 10m", "5m"; under a minute "<1m". */
    static String remaining(long millis) {
        long minutes = millis / StandingOrderBook.MINUTE;
        if (minutes < 1) return "<1m";
        long days = minutes / 1440, hours = minutes % 1440 / 60, rest = minutes % 60;
        if (days > 0) return days + "d" + (hours > 0 ? " " + hours + "h" : "");
        if (hours > 0) return hours + "h" + (rest > 0 ? " " + rest + "m" : "");
        return rest + "m";
    }

    private void list(Player player) {
        var orders = book.of(player.getUniqueId());
        var incoming = book.toPayee(player.getUniqueId());
        if (orders.isEmpty() && incoming.isEmpty()) { lang.send(player, "standing.none"); return; }
        if (!orders.isEmpty()) lang.send(player, "standing.list-header", "count", orders.size(), "max", maxPerPlayer());
        for (var order : orders) {
            Component line = describe(player, order).append(Component.space())
                    .append(core.message().chat().command(lang.plain(player, order.paused() ? "standing.button-resume" : "standing.button-pause"),
                            lang.plain(player, "standing.button-hover"), "/economy standing " + (order.paused() ? "resume " : "pause ") + order.id()))
                    .append(Component.space())
                    .append(core.message().chat().command(lang.plain(player, "standing.button-cancel"), lang.plain(player, "standing.button-hover"),
                            "/economy standing cancel " + order.id()));
            core.compat().platform().sendMessage(player, line);
        }
        if (!incoming.isEmpty()) {
            lang.send(player, "standing.incoming-header", "count", incoming.size());
            for (var order : incoming) lang.send(player, "standing.incoming-row", "player", plugin.displayName(plugin.player(order.payer())),
                    "amount", plugin.formatPoints(player, order.amount()), "interval", StandingOrderBook.formatInterval(order.intervalMillis()),
                    "state", order.paused() ? lang.plain(player, "standing.state-paused") : lang.plain(player, "standing.state-active"));
        }
    }

    private Component listButton(Player player) {
        return core.message().chat().button(lang.plain(player, "standing.button-list"), lang.plain(player, "standing.button-list-hover"), p -> openList(p, null));
    }

    // ---- dialogs ----

    /** Your orders with pause/resume/cancel buttons, plus "new standing order". */
    void openList(Player player, Component notice) {
        if (!enabled()) { lang.send(player, "feature-off"); return; }
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) body.add(DialogBody.plainMessage(notice));
        body.add(DialogBody.plainMessage(lang.component(player, "standing.dialog-intro")));
        var orders = book.of(player.getUniqueId());
        if (orders.isEmpty()) body.add(DialogBody.plainMessage(lang.component(player, "standing.none")));
        Component lines = Component.empty();
        for (int i = 0; i < orders.size(); i++) lines = lines.append(i == 0 ? Component.empty() : Component.newline()).append(describe(player, orders.get(i)));
        if (!orders.isEmpty()) body.add(DialogBody.plainMessage(lines, 300));
        long incoming = book.toPayee(player.getUniqueId()).stream().filter(o -> !o.paused()).count();
        if (incoming > 0) body.add(DialogBody.plainMessage(lang.component(player, "standing.dialog-incoming", "count", incoming)));
        List<DialogButton> buttons = new ArrayList<>();
        if (orders.size() < maxPerPlayer())
            buttons.add(new DialogButton(lang.component(player, "standing.dialog-new"), (p, response) -> openCreate(p, "", null)));
        for (var order : orders) {
            buttons.add(new DialogButton(lang.component(player, order.paused() ? "standing.dialog-resume" : "standing.dialog-pause", "id", order.id()),
                    (p, response) -> { String error = change(p, order.id(), order.paused() ? "resume" : "pause");
                        openList(p, error == null ? null : lang.component(p, error)); }));
            buttons.add(new DialogButton(lang.component(player, "standing.dialog-cancel", "id", order.id()),
                    (p, response) -> { String error = change(p, order.id(), "cancel");
                        openList(p, error == null ? null : lang.component(p, error)); }));
        }
        buttons.add(MenuButtons.back(core, plugin::openAccount));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "standing.dialog-title"), body, List.of(), buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
    }

    /** Form for a new order (also the player menu entry with the recipient filled in). */
    void openCreate(Player player, String target, Component error) {
        if (!enabled()) { lang.send(player, "feature-off"); return; }
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogBody.plainMessage(error.colorIfAbsent(NamedTextColor.RED)));
        body.add(DialogBody.plainMessage(lang.component(player, "standing.dialog-create-intro", "min", StandingOrderBook.formatInterval(minimumInterval()))));
        List<DialogInput> inputs = List.of(
                DialogInput.text("target", lang.component(player, "transfer.target")).initial(target == null ? "" : target).maxLength(16).build(),
                DialogInput.text("amount", lang.component(player, "transfer.amount", "currency", plugin.currencyName(lang.language(player), false))).maxLength(16).build(),
                DialogInput.text("interval", lang.component(player, "standing.dialog-interval")).initial("1d").maxLength(6).build(),
                DialogInput.text("runs", lang.component(player, "standing.dialog-runs")).initial("0").maxLength(4).build());
        var next = new DialogButton(lang.component(player, "transfer.continue"), (p, response) -> {
            String name = String.valueOf(response.getText("target")).trim();
            String problem = prepare(p, name, response.getText("amount"), response.getText("interval"), response.getText("runs"));
            if (problem != null) openCreate(p, name, lang.component(p, problem, "min", StandingOrderBook.formatInterval(minimumInterval()), "max", maxPerPlayer()));
        });
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "standing.dialog-create-title"), body, inputs,
                List.of(next, MenuButtons.back(core, p -> openList(p, null))), p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
    }
}
