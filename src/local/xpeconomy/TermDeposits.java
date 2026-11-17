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
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /economy term}: term deposits. A player locks XP for one of the configured terms ({@code term-deposits.terms},
 * e.g. 7 days at 5 %) and gets it back with interest when the term ends – also while offline. An early withdrawal
 * returns the XP without interest. Every booking holds the economy's transaction lock across the balance change and
 * the ledger write ({@link TermDepositBook}); matured deposits are paid every 30 seconds on the server thread.
 */
final class TermDeposits {
    private final XPVaultEconomy plugin;
    private final API core;
    private final TermDepositBook book;
    private final BankTransfers.Wallet wallet;
    private final Lang lang;
    /** Deposits whose payout failed (owner's balance full); told once per run. */
    private final Set<Integer> blocked = new HashSet<>();

    TermDeposits(XPVaultEconomy plugin, API core, TermDepositBook book, BankTransfers.Wallet wallet) {
        this.plugin = plugin;
        this.core = core;
        this.book = book;
        this.wallet = wallet;
        this.lang = plugin.lang();
    }

    TermDepositBook book() { return book; }

    boolean enabled() { return plugin.getConfig().getBoolean("term-deposits.enabled", true); }
    List<TermDepositBook.Term> terms() { return TermDepositBook.parseTerms(plugin.getConfig().getStringList("term-deposits.terms")); }
    long minimum() { return Math.max(1, plugin.getConfig().getLong("term-deposits.minimum", 100)); }
    int maxPerPlayer() { return Math.max(0, Math.min(50, plugin.getConfig().getInt("term-deposits.max-per-player", 3))); }
    long interestCap() { return Math.max(0, plugin.getConfig().getLong("term-deposits.interest-cap", 0)); }
    boolean earlyWithdrawal() { return plugin.getConfig().getBoolean("term-deposits.early-withdrawal", true); }

    // ---- bookings ----

    /** Takes {@code amount} from the player and opens a deposit; returns an error key or null. */
    String open(Player player, long amount, TermDepositBook.Term term) {
        if (!enabled() || !book.readable()) return "term.error.storage";
        if (amount < minimum()) return "term.error.minimum";
        if (book.of(player.getUniqueId()).size() >= maxPerPlayer()) return "term.error.capacity";
        UUID id = player.getUniqueId();
        synchronized (plugin.transactionLock()) {
            long before = plugin.balance(player);
            if (before < amount) return "term.error.insufficient";
            TransactionResult taken = wallet.take(id, amount);
            if (!taken.success()) return "term.error.insufficient";
            try {
                book.open(id, amount, term.rateBasisPoints(), term.days() * TermDepositBook.DAY, System.currentTimeMillis(), maxPerPlayer(),
                        wallet.intent(id, before, taken.balance()));
            } catch (IOException failure) {
                TransactionResult refund = wallet.give(id, amount);
                if (!refund.success()) plugin.getLogger().severe("Term deposit of " + amount + " for " + id + " not saved and refund failed; manual balance review required.");
                return "capacity".equals(failure.getMessage()) ? "term.error.capacity" : "term.error.storage";
            }
        }
        plugin.changed();
        return null;
    }

    /**
     * Pays a deposit back: principal plus interest when {@code matured}, principal only otherwise. Returns an error key
     * or null. The deposit stays when the owner's balance cannot take the payout.
     */
    String close(UUID owner, TermDepositBook.Deposit deposit, boolean matured, boolean admin) {
        long payout = deposit.amount() + (matured ? deposit.interest(interestCap()) : 0);
        synchronized (plugin.transactionLock()) {
            if (book.get(deposit.id()).isEmpty()) return "term.error.unknown";
            OfflinePlayer account = plugin.player(deposit.owner());
            long before = plugin.balance(account);
            TransactionResult given = wallet.give(deposit.owner(), payout);
            if (!given.success()) return "term.error.full";
            try { book.close(owner, deposit.id(), admin, wallet.intent(deposit.owner(), before, given.balance())); }
            catch (IOException failure) {
                TransactionResult back = wallet.take(deposit.owner(), payout);
                if (!back.success()) {
                    plugin.getLogger().severe("Term deposit #" + deposit.id() + " paid but not removed, and taking it back failed; manual review required.");
                    throw new IllegalStateException("Term deposit payout requires manual review");
                }
                return "unknown".equals(failure.getMessage()) ? "term.error.unknown" : "term.error.storage";
            }
        }
        plugin.changed();
        return null;
    }

    /** Timer: pays every matured deposit and tells the owner (chat when online, else the Core inbox). */
    void runDue() {
        if (!book.readable() || !plugin.isEnabled()) return;
        for (TermDepositBook.Deposit deposit : book.matured(System.currentTimeMillis())) {
            String error;
            try { error = close(deposit.owner(), deposit, true, true); }
            catch (RuntimeException failure) { plugin.getLogger().severe("Term deposit #" + deposit.id() + ": " + failure); continue; }
            long interest = deposit.interest(interestCap());
            Player online = plugin.player(deposit.owner()).getPlayer();
            if (error == null) {
                blocked.remove(deposit.id());
                if (online != null) lang.send(online, "term.matured", "id", deposit.id(), "amount", plugin.formatPoints(online, deposit.amount()),
                        "interest", plugin.formatPoints(online, interest));
                else inbox(deposit.owner(), "term.matured-inbox", "id", deposit.id(), "amount", deposit.amount(), "interest", interest);
            } else if (blocked.add(deposit.id())) {
                if (online != null) lang.send(online, "term.payout-blocked", "id", deposit.id());
                else inbox(deposit.owner(), "term.payout-blocked", "id", deposit.id());
            }
        }
    }

    private void inbox(UUID player, String key, Object... placeholders) {
        core.message().inbox().send(plugin, player, key, placeholders);
    }

    // ---- commands ----

    boolean command(CommandSender sender, String[] args) {
        if (!enabled()) { lang.send(sender, "term.disabled"); return true; }
        if (!book.readable()) { lang.send(sender, "term.error.storage"); return true; }
        if (!(sender instanceof Player player)) { lang.send(sender, "common.players-only"); return true; }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "" , "list" -> openList(player, null);
            case "open", "new" -> openCreate(player, null);
            case "withdraw", "close" -> {
                Integer id = args.length == 2 && args[1].replace("#", "").matches("\\d{1,9}") ? Integer.valueOf(args[1].replace("#", "")) : null;
                if (id == null) { lang.send(player, "term.usage"); return true; }
                confirmEarly(player, id);
            }
            default -> lang.send(player, "term.usage");
        }
        return true;
    }

    // ---- dialogs ----

    private Component describe(Player viewer, TermDepositBook.Deposit d) {
        long left = Math.max(0, d.matures() - System.currentTimeMillis());
        return lang.component(viewer, "term.row", "id", d.id(), "amount", plugin.formatPoints(viewer, d.amount()),
                "interest", plugin.formatPoints(viewer, d.interest(interestCap())), "rate", TermDepositBook.percent(d.rateBasisPoints()),
                "time", StandingOrders.remaining(left));
    }

    void openList(Player player, Component notice) {
        if (!enabled()) { lang.send(player, "term.disabled"); return; }
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) body.add(DialogBody.plainMessage(notice));
        body.add(DialogBody.plainMessage(lang.component(player, "term.intro")));
        Component offers = Component.empty();
        List<TermDepositBook.Term> terms = terms();
        for (int i = 0; i < terms.size(); i++) offers = offers.append(i == 0 ? Component.empty() : Component.newline())
                .append(lang.component(player, "term.offer", "days", terms.get(i).days(), "rate", TermDepositBook.percent(terms.get(i).rateBasisPoints())));
        if (!terms.isEmpty()) body.add(DialogBody.plainMessage(offers));
        var deposits = book.of(player.getUniqueId());
        if (deposits.isEmpty()) body.add(DialogBody.plainMessage(lang.component(player, "term.none")));
        else {
            Component lines = Component.empty();
            for (int i = 0; i < deposits.size(); i++) lines = lines.append(i == 0 ? Component.empty() : Component.newline()).append(describe(player, deposits.get(i)));
            body.add(DialogBody.plainMessage(lines, 300));
        }
        List<DialogButton> buttons = new ArrayList<>();
        if (deposits.size() < maxPerPlayer() && !terms.isEmpty())
            buttons.add(new DialogButton(lang.component(player, "term.button-new"), (p, r) -> openCreate(p, null)));
        if (earlyWithdrawal()) for (var d : deposits)
            buttons.add(new DialogButton(lang.component(player, "term.button-early", "id", d.id()), (p, r) -> confirmEarly(p, d.id())));
        buttons.add(MenuButtons.back(core, plugin::openAccount));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "term.title"), body, List.of(), buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, lang.plain(player, "term.close"), 2));
    }

    void openCreate(Player player, Component error) {
        List<TermDepositBook.Term> terms = terms();
        if (terms.isEmpty()) { lang.send(player, "term.disabled"); return; }
        List<DialogBody> body = new ArrayList<>();
        if (error != null) body.add(DialogBody.plainMessage(error.colorIfAbsent(NamedTextColor.RED)));
        body.add(DialogBody.plainMessage(lang.component(player, "term.create-intro", "min", plugin.formatPoints(player, minimum()),
                "balance", plugin.formatPoints(player, plugin.balance(player)))));
        List<DialogInput> inputs = List.of(DialogInput.text("amount", lang.component(player, "transfer.amount",
                "currency", plugin.currencyName(lang.language(player), false))).maxLength(12).build());
        List<DialogButton> buttons = new ArrayList<>();
        for (TermDepositBook.Term term : terms)
            buttons.add(new DialogButton(lang.component(player, "term.button-term", "days", term.days(), "rate", TermDepositBook.percent(term.rateBasisPoints())),
                    (p, r) -> {
                        Long amount = plugin.parsePoints(String.valueOf(r.getText("amount")).trim());
                        if (amount == null || amount <= 0) { openCreate(p, lang.component(p, "term.error.amount")); return; }
                        long interest = new TermDepositBook.Deposit(0, p.getUniqueId(), amount, term.rateBasisPoints(), 0, 0).interest(interestCap());
                        core.dialogs().confirm(plugin, p, lang.plain(p, "term.confirm-title"),
                                lang.plain(p, "term.confirm", "amount", plugin.formatPoints(p, amount), "days", term.days(),
                                        "interest", plugin.formatPoints(p, interest), "rate", TermDepositBook.percent(term.rateBasisPoints())),
                                () -> {
                                    String problem = open(p, amount, term);
                                    if (problem != null) openCreate(p, lang.component(p, problem, "min", plugin.formatPoints(p, minimum()), "max", maxPerPlayer()));
                                    else openList(p, lang.component(p, "term.opened", "amount", plugin.formatPoints(p, amount), "days", term.days()));
                                }, () -> openCreate(p, null), q -> q.isOnline() && plugin.isEnabled());
                    }));
        buttons.add(MenuButtons.back(core, p -> openList(p, null)));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "term.create-title"), body, inputs, buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, lang.plain(player, "term.close"), 2));
    }

    private void confirmEarly(Player player, int id) {
        if (!earlyWithdrawal()) { lang.send(player, "term.error.early"); return; }
        var deposit = book.get(id).filter(d -> d.owner().equals(player.getUniqueId()));
        if (deposit.isEmpty()) { lang.send(player, "term.error.unknown"); return; }
        core.dialogs().confirm(plugin, player, lang.plain(player, "term.early-title"),
                lang.plain(player, "term.early-question", "id", id, "amount", plugin.formatPoints(player, deposit.get().amount()),
                        "interest", plugin.formatPoints(player, deposit.get().interest(interestCap()))),
                () -> {
                    boolean matured = deposit.get().matures() <= System.currentTimeMillis();
                    String error = close(player.getUniqueId(), deposit.get(), matured, false);
                    openList(player, error == null ? lang.component(player, "term.withdrawn", "id", id, "amount", plugin.formatPoints(player, deposit.get().amount()))
                            : lang.component(player, error));
                }, () -> openList(player, null), p -> p.isOnline() && plugin.isEnabled());
    }
}
