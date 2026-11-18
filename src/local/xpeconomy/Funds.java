package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /economy fund}: public donation goals. The owner sets a title, a target and a deadline; anyone donates XP.
 * When the target is reached the XP goes to the owner at once; when the deadline passes first, or the owner cancels,
 * every donor gets their XP back. All bookings hold the economy's transaction lock across the balance change and the
 * {@link FundBook} write. Expired goals are checked every 30 seconds on the server thread.
 */
final class Funds {
    private final XPVaultEconomy plugin;
    private final API core;
    private final FundBook book;
    private final BankTransfers.Wallet wallet;
    private final Lang lang;

    Funds(XPVaultEconomy plugin, API core, FundBook book, BankTransfers.Wallet wallet) {
        this.plugin = plugin; this.core = core; this.book = book; this.wallet = wallet; this.lang = plugin.lang();
    }

    FundBook book() { return book; }
    boolean enabled() { return plugin.getConfig().getBoolean("funds.enabled", true); }
    int maxPerPlayer() { return Math.clamp(plugin.getConfig().getInt("funds.max-per-player", 2), 0, 20); }
    int maxDays() { return Math.clamp(plugin.getConfig().getInt("funds.max-days", 30), 1, 365); }
    long minTarget() { return Math.max(1, plugin.getConfig().getLong("funds.min-target", 100)); }

    // ---- bookings ----

    String create(Player player, String title, long target, int days) {
        if (!enabled() || !book.readable()) return "fund.error.storage";
        String clean = title == null ? "" : title.replace('§', '&').strip();
        if (clean.isEmpty() || clean.length() > 48) return "fund.error.title";
        if (target < minTarget()) return "fund.error.target";
        if (days < 1 || days > maxDays()) return "fund.error.days";
        try { book.open(player.getUniqueId(), clean, target, System.currentTimeMillis() + days * FundBook.DAY, maxPerPlayer()); }
        catch (IOException failure) { return "capacity".equals(failure.getMessage()) ? "fund.error.capacity" : "fund.error.storage"; }
        return null;
    }

    String donate(Player player, int id, long amount) {
        if (!enabled() || !book.readable()) return "fund.error.storage";
        var fund = book.get(id).orElse(null);
        if (fund == null) return "fund.error.unknown";
        if (amount <= 0) return "fund.error.amount";
        long take = Math.min(amount, fund.missing());
        if (take <= 0) return "fund.error.unknown";
        UUID donor = player.getUniqueId();
        FundBook.Fund updated;
        synchronized (plugin.transactionLock()) {
            long before = plugin.balance(player);
            if (before < take) return "fund.error.insufficient";
            TransactionResult taken = wallet.take(donor, take);
            if (!taken.success()) return "fund.error.insufficient";
            try { updated = book.donate(id, donor, take, System.currentTimeMillis(), wallet.intent(donor, before, taken.balance())); }
            catch (IOException failure) {
                TransactionResult refund = wallet.give(donor, take);
                if (!refund.success()) plugin.getLogger().severe("Donation of " + take + " by " + donor + " not saved and refund failed; manual review required.");
                return "closed".equals(failure.getMessage()) ? "fund.error.closed" : "invalid".equals(failure.getMessage()) ? "fund.error.unknown" : "fund.error.storage";
            }
        }
        plugin.changed();
        lang.send(player, "fund.donated", "amount", plugin.formatPoints(player, take), "title", fund.title());
        Player owner = Bukkit.getPlayer(fund.owner());
        if (owner != null && !owner.equals(player)) lang.send(owner, "fund.donation-received", "player", player.getName(), "amount", plugin.formatPoints(owner, take), "title", fund.title());
        if (updated.missing() == 0) payout(updated);
        return null;
    }

    /** Pays a reached goal to its owner; returns false when it has to wait (owner's balance full, storage). */
    boolean payout(FundBook.Fund fund) {
        long raised = fund.raised();
        synchronized (plugin.transactionLock()) {
            if (book.get(fund.id()).isEmpty()) return true;
            long before = plugin.balance(plugin.player(fund.owner()));
            TransactionResult given = wallet.give(fund.owner(), raised);
            if (!given.success()) return false;
            try { book.close(fund.id(), List.of(wallet.intent(fund.owner(), before, given.balance()))); }
            catch (IOException failure) {
                if (!wallet.take(fund.owner(), raised).success()) plugin.getLogger().severe("Fund #" + fund.id() + " paid but not closed, and taking it back failed; manual review required.");
                return false;
            }
        }
        plugin.changed();
        tell(fund.owner(), "fund.reached", "fund.reached-inbox", "title", fund.title(), "amount", raised);
        return true;
    }

    /** Gives every donor their XP back and removes the goal; all or nothing. */
    boolean refundAll(FundBook.Fund fund, String reasonKey) {
        synchronized (plugin.transactionLock()) {
            if (book.get(fund.id()).isEmpty()) return true;
            List<PendingBookings.Intent> intents = new ArrayList<>();
            List<Map.Entry<UUID, Long>> given = new ArrayList<>();
            for (var e : fund.donations().entrySet()) {
                UUID donor = UUID.fromString(e.getKey());
                long before = plugin.balance(plugin.player(donor));
                TransactionResult r = wallet.give(donor, e.getValue());
                if (!r.success()) { rollback(given); return false; }
                given.add(Map.entry(donor, e.getValue()));
                intents.add(wallet.intent(donor, before, r.balance()));
            }
            try { book.close(fund.id(), intents); }
            catch (IOException failure) { rollback(given); return false; }
        }
        plugin.changed();
        for (String donor : fund.donations().keySet())
            tell(UUID.fromString(donor), reasonKey, reasonKey + "-inbox", "title", fund.title(), "amount", fund.donations().get(donor));
        return true;
    }

    private void rollback(List<Map.Entry<UUID, Long>> given) {
        for (var g : given) if (!wallet.take(g.getKey(), g.getValue()).success())
            plugin.getLogger().severe("Fund refund rollback for " + g.getKey() + " failed; manual review required.");
    }

    private void tell(UUID player, String chatKey, String inboxKey, Object... placeholders) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) { lang.send(online, chatKey, placeholders); return; }
        core.message().inbox().send(plugin, player, inboxKey, placeholders);
    }

    /**
     * Timer: refunds goals whose deadline passed and retries waiting payouts. Runs also while funds.enabled is off, so
     * no donated XP waits for the switch: an expired goal is refunded and a reached goal is paid as before.
     */
    void runDue() {
        if (!book.readable() || !plugin.isEnabled()) return;
        for (FundBook.Fund fund : book.all()) {
            try {
                if (fund.missing() == 0) payout(fund);
                else if (fund.deadline() <= System.currentTimeMillis()) refundAll(fund, "fund.expired");
            } catch (RuntimeException failure) { plugin.getLogger().severe("Fund #" + fund.id() + ": " + failure); }
        }
    }

    // ---- commands and dialogs ----

    boolean command(CommandSender sender, String[] args) {
        if (!enabled()) { lang.send(sender, "feature-off"); return true; }
        if (!book.readable()) { lang.send(sender, "fund.error.storage"); return true; }
        if (!(sender instanceof Player player)) { lang.send(sender, "common.players-only"); return true; }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "", "list" -> openList(player, null);
            case "create" -> openCreate(player, null);
            case "cancel" -> {
                Integer id = args.length == 2 && args[1].replace("#", "").matches("\\d{1,9}") ? Integer.valueOf(args[1].replace("#", "")) : null;
                var fund = id == null ? null : book.get(id).orElse(null);
                if (fund == null || !fund.owner().equals(player.getUniqueId()) && !player.hasPermission("xpeconomy.admin")) { lang.send(player, "fund.error.unknown"); return true; }
                lang.send(player, refundAll(fund, "fund.cancelled") ? "fund.cancel-done" : "fund.error.storage", "title", fund.title());
            }
            default -> lang.send(player, "fund.usage");
        }
        return true;
    }

    private Component describe(Player viewer, FundBook.Fund f) {
        long left = Math.max(0, f.deadline() - System.currentTimeMillis());
        int percent = (int) Math.min(100, f.raised() * 100 / Math.max(1, f.target()));
        return lang.component(viewer, "fund.row", "id", f.id(), "title", f.title(), "owner", plugin.displayName(plugin.player(f.owner())),
                "raised", plugin.formatPoints(viewer, f.raised()), "target", plugin.formatPoints(viewer, f.target()), "percent", percent,
                "time", StandingOrders.remaining(left));
    }

    void openList(Player player, Component notice) {
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) body.add(DialogBody.plainMessage(notice));
        body.add(DialogBody.plainMessage(lang.component(player, "fund.intro")));
        List<FundBook.Fund> funds = book.all();
        if (funds.isEmpty()) body.add(DialogBody.plainMessage(lang.component(player, "fund.none")));
        else {
            Component lines = Component.empty();
            for (int i = 0; i < funds.size() && i < 12; i++) lines = lines.append(i == 0 ? Component.empty() : Component.newline()).append(describe(player, funds.get(i)));
            body.add(DialogBody.plainMessage(lines, 320));
        }
        List<DialogButton> buttons = new ArrayList<>();
        if (book.of(player.getUniqueId()).size() < maxPerPlayer())
            buttons.add(new DialogButton(lang.component(player, "fund.button-create"), (p, r) -> openCreate(p, null)));
        for (int i = 0; i < funds.size() && i < 8; i++) {
            FundBook.Fund f = funds.get(i);
            buttons.add(new DialogButton(lang.component(player, "fund.button-donate", "id", f.id(), "title", f.title()), (p, r) -> openDonate(p, f.id(), null)));
            if (f.owner().equals(player.getUniqueId()))
                buttons.add(new DialogButton(lang.component(player, "fund.button-cancel", "id", f.id()), (p, r) -> command(p, new String[] {"cancel", Integer.toString(f.id())})));
        }
        buttons.add(MenuButtons.back(core, plugin::openAccount));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "fund.title"), body, List.of(), buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, lang.plain(player, "fund.close"), 2));
    }

    void openCreate(Player player, Component error) {
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogBody.plainMessage(error.colorIfAbsent(NamedTextColor.RED)));
        body.add(DialogBody.plainMessage(lang.component(player, "fund.create-intro", "min", plugin.formatPoints(player, minTarget()), "days", maxDays())));
        List<DialogInput> inputs = List.of(
                DialogInput.text("title", lang.component(player, "fund.input-title")).maxLength(48).build(),
                DialogInput.text("target", lang.component(player, "fund.input-target")).maxLength(12).build(),
                DialogInput.text("days", lang.component(player, "fund.input-days")).initial("7").maxLength(3).build());
        var save = new DialogButton(lang.component(player, "fund.button-open"), (p, r) -> {
            Long target = plugin.parsePoints(String.valueOf(r.getText("target")).trim());
            int days;
            try { days = Integer.parseInt(String.valueOf(r.getText("days")).trim()); } catch (NumberFormatException bad) { days = -1; }
            String problem = target == null ? "fund.error.target" : create(p, r.getText("title"), target, days);
            if (problem != null) openCreate(p, lang.component(p, problem, "min", plugin.formatPoints(p, minTarget()), "days", maxDays(), "max", maxPerPlayer()));
            else openList(p, lang.component(p, "fund.opened"));
        });
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "fund.create-title"), body, inputs,
                List.of(save, MenuButtons.back(core, p -> openList(p, null))), p -> p.isOnline() && plugin.isEnabled(), () -> {}, lang.plain(player, "fund.close"), 2));
    }

    void openDonate(Player player, int id, Component error) {
        var fund = book.get(id).orElse(null);
        if (fund == null) { openList(player, lang.component(player, "fund.error.unknown")); return; }
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogBody.plainMessage(error.colorIfAbsent(NamedTextColor.RED)));
        body.add(DialogBody.plainMessage(describe(player, fund)));
        List<DialogInput> inputs = List.of(DialogInput.text("amount", lang.component(player, "transfer.amount",
                "currency", plugin.currencyName(lang.language(player), false))).maxLength(12).build());
        var give = new DialogButton(lang.component(player, "fund.button-give"), (p, r) -> {
            Long amount = plugin.parsePoints(String.valueOf(r.getText("amount")).trim());
            String problem = amount == null ? "fund.error.amount" : donate(p, id, amount);
            if (problem != null) openDonate(p, id, lang.component(p, problem));
            else openList(p, null);
        });
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "fund.donate-title", "title", fund.title()), body, inputs,
                List.of(give, MenuButtons.back(core, p -> openList(p, null))), p -> p.isOnline() && plugin.isEnabled(), () -> {}, lang.plain(player, "fund.close"), 2));
    }
}
