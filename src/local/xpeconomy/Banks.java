package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /economy bank}: shared XP banks with dialogs. Owners add members; owner and members deposit and withdraw;
 * admins ({@code xpeconomy.admin}) can view, withdraw from and close every bank. The XP moves are in
 * {@link BankTransfers}, always under the economy's transaction lock.
 */
final class Banks {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private final XPVaultEconomy plugin;
    private final API core;
    private final BankLedger ledger;
    private final Lang lang;

    Banks(XPVaultEconomy plugin, API core, BankLedger ledger) {
        this.plugin = plugin;
        this.core = core;
        this.ledger = ledger;
        this.lang = plugin.lang();
    }

    BankLedger ledger() { return ledger; }

    boolean enabled() { return plugin.getConfig().getBoolean("banks.enabled", true); }
    int maxOwned() { return Math.max(0, Math.min(100, plugin.getConfig().getInt("banks.max-per-player", 3))); }
    int maxMembers() { return Math.max(0, Math.min(BankLedger.MAX_MEMBERS, plugin.getConfig().getInt("banks.max-members", 10))); }

    boolean command(CommandSender sender, String[] args) {
        if (!enabled()) { lang.send(sender, "bank.disabled"); return true; }
        if (!ledger.readable()) { lang.send(sender, "bank.error.storage"); return true; }
        boolean admin = sender.hasPermission("xpeconomy.admin");
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (action.isEmpty() && sender instanceof Player player) { openList(player, null); return true; }
        if (action.isEmpty() || action.equals("list") || action.equals("liste")) { list(sender); return true; }
        if (action.equals("info") && args.length == 2) {
            var bank = ledger.get(args[1]);
            if (bank.isEmpty() || !(admin || sender instanceof Player p && bank.get().member(p.getUniqueId()))) {
                lang.send(sender, "bank.error.unknown"); return true;
            }
            info(sender, bank.get());
            return true;
        }
        if (action.equals("delete") && args.length == 2 && !(sender instanceof Player)) {
            report(sender, close(sender, args[1], true), "bank.closed", args[1]);
            return true;
        }
        if (!(sender instanceof Player player)) { lang.send(sender, "common.players-only"); return true; }
        switch (action) {
            case "policy" -> {
                if (args.length != 7) { lang.send(player, "treasury.usage"); return true; }
                policy(player, args[1], args[2], args[3], args[4], args[5], args[6]);
            }
            case "history" -> {
                if (args.length < 2 || args.length > 3) { lang.send(player, "treasury.usage"); return true; }
                try { history(player, args[1], args.length == 3 ? Integer.parseInt(args[2]) : 1); }
                catch (NumberFormatException invalid) { lang.send(player, "treasury.usage"); }
            }
            case "create", "erstellen" -> {
                if (args.length != 2) { lang.send(player, "bank.usage"); return true; }
                report(player, create(player, args[1]), "bank.created", args[1]);
            }
            case "deposit", "einzahlen", "withdraw", "abheben" -> {
                if (args.length != 3) { lang.send(player, "bank.usage"); return true; }
                Long amount = plugin.parsePoints(args[2]);
                boolean in = action.equals("deposit") || action.equals("einzahlen");
                String error = amount == null || amount <= 0 ? "amount" : in ? deposit(player, args[1], amount) : withdraw(player, args[1], amount);
                if (error == null) lang.send(player, in ? "bank.deposited" : "bank.withdrawn", "amount", plugin.formatPoints(player, amount),
                        "bank", displayName(args[1]), "balance", plugin.formatPoints(player, ledger.get(args[1]).map(BankLedger.Bank::balance).orElse(0L)));
                else sendError(player, error);
            }
            case "add", "remove", "hinzufügen", "entfernen" -> {
                if (args.length != 3) { lang.send(player, "bank.usage"); return true; }
                boolean add = action.equals("add") || action.equals("hinzufügen");
                OfflinePlayer target = plugin.resolveKnownPlayer(args[2]);
                String error = target == null || !plugin.hasAccount(target.getUniqueId()) ? "account" : members(player, args[1], target, add);
                if (error == null) lang.send(player, add ? "bank.member-added" : "bank.member-removed", "player", plugin.displayName(target), "bank", displayName(args[1]));
                else sendError(player, error);
            }
            case "delete", "löschen" -> {
                if (args.length != 2) { lang.send(player, "bank.usage"); return true; }
                confirmClose(player, args[1]);
            }
            default -> lang.send(player, "bank.usage");
        }
        return true;
    }

    // ---- actions (all re-check rights; dialogs and commands share them) ----

    String create(Player player, String name) {
        if (!BankLedger.validName(name)) return "name";
        try { ledger.create(name, player.getUniqueId(), System.currentTimeMillis(), maxOwned()); return null; }
        catch (IOException failure) { return failure.getMessage() != null && failure.getMessage().equals("capacity") ? "owned" : BankTransfers.code(failure); }
    }

    String deposit(Player player, String bank, long amount) {
        synchronized (plugin.transactionLock()) {
            String error = BankTransfers.deposit(ledger, plugin.bankWallet(), player.getUniqueId(), bank, amount,
                    System.currentTimeMillis(), plugin.maxBalance(), plugin.getLogger()::severe);
            if (error == null) plugin.changed();
            return error;
        }
    }

    String withdraw(Player player, String bank, long amount) {
        synchronized (plugin.transactionLock()) {
            String error = BankTransfers.withdraw(ledger, plugin.bankWallet(), player.getUniqueId(), bank, amount,
                    System.currentTimeMillis(), plugin.maxBalance(), player.hasPermission("xpeconomy.admin"), plugin.getLogger()::severe);
            if (error == null) plugin.changed();
            return error;
        }
    }

    private String members(Player player, String bank, OfflinePlayer target, boolean add) {
        var current = ledger.get(bank);
        if (current.isEmpty()) return "unknown";
        if (!current.get().owner().equals(player.getUniqueId()) && !player.hasPermission("xpeconomy.admin")) return "not-owner";
        if (add && target.getUniqueId().equals(current.get().owner())) return "member";
        try {
            if (add) ledger.addMember(bank, target.getUniqueId(), maxMembers());
            else ledger.removeMember(bank, target.getUniqueId());
            return null;
        } catch (IOException failure) { return BankTransfers.code(failure); }
    }

    private String close(CommandSender sender, String bank, boolean admin) {
        var current = ledger.get(bank);
        if (current.isEmpty()) return "unknown";
        if (!admin && !(sender instanceof Player p && current.get().owner().equals(p.getUniqueId()))) return "not-owner";
        synchronized (plugin.transactionLock()) {
            String error = BankTransfers.close(ledger, plugin.bankWallet(), bank, plugin.getLogger()::severe);
            if (error == null) plugin.changed();
            return error;
        }
    }

    private void confirmClose(Player player, String bank) {
        var current = ledger.get(bank);
        boolean admin = player.hasPermission("xpeconomy.admin");
        if (current.isEmpty()) { sendError(player, "unknown"); return; }
        if (!admin && !current.get().owner().equals(player.getUniqueId())) { sendError(player, "not-owner"); return; }
        String name = current.get().name();
        String ownerName = plugin.displayName(plugin.player(current.get().owner()));
        core.dialogs().confirm(plugin, player, lang.plain(player, "bank.close-title", "bank", name),
                lang.plain(player, "bank.close-question", "bank", name, "amount", plugin.formatPoints(player, current.get().balance()), "player", ownerName),
                () -> report(player, close(player, name, player.hasPermission("xpeconomy.admin")), "bank.closed", name),
                () -> {}, p -> p.isOnline() && plugin.isEnabled());
    }

    // ---- chat output ----

    private void list(CommandSender sender) {
        List<BankLedger.Bank> banks;
        if (!(sender instanceof Player player)) banks = ledger.all();
        else banks = player.hasPermission("xpeconomy.admin") ? merged(player) : ledger.of(player.getUniqueId());
        if (banks.isEmpty()) { lang.send(sender, "bank.none"); return; }
        lang.send(sender, "bank.list-header", "count", banks.size());
        for (BankLedger.Bank bank : banks) {
            Component line = lang.component(sender, "bank.list-row", "bank", bank.name(),
                    "amount", plugin.formatPoints(sender, bank.balance()),
                    "owner", plugin.displayName(plugin.player(bank.owner())), "members", bank.members().size());
            if (sender instanceof Player)
                line = line.append(Component.space()).append(core.message().chat().command(lang.plain(sender, "bank.button-open"),
                        lang.plain(sender, "bank.button-open-hover"), "/economy bank info " + bank.name()));
            core.compat().platform().sendMessage(sender, line);
        }
    }

    /** Admins see their own banks first, then all others. */
    private List<BankLedger.Bank> merged(Player admin) {
        List<BankLedger.Bank> result = new ArrayList<>(ledger.of(admin.getUniqueId()));
        for (BankLedger.Bank bank : ledger.all()) if (!result.contains(bank)) result.add(bank);
        return result;
    }

    private void info(CommandSender sender, BankLedger.Bank bank) {
        for (Component line : details(sender, bank)) core.compat().platform().sendMessage(sender, line);
        if (sender instanceof Player player) {
            Component buttons = core.message().chat().button(lang.plain(player, "bank.button-manage"), lang.plain(player, "bank.button-manage-hover"),
                    p -> openBank(p, bank.name(), null));
            core.compat().platform().sendMessage(player, buttons);
        }
    }

    private List<Component> details(CommandSender viewer, BankLedger.Bank bank) {
        List<Component> lines = new ArrayList<>();
        lines.add(lang.component(viewer, "bank.info-balance", "bank", bank.name(), "amount", plugin.formatPoints(viewer, bank.balance())));
        lines.add(lang.component(viewer, "bank.info-owner", "player", plugin.displayName(plugin.player(bank.owner()))));
        lines.add(lang.component(viewer, "bank.info-members", "players", bank.members().isEmpty() ? lang.plain(viewer, "bank.info-no-members")
                : String.join(", ", bank.members().stream().map(id -> plugin.displayName(plugin.player(id))).toList())));
        if (bank.log().isEmpty()) lines.add(lang.component(viewer, "bank.log-empty"));
        else {
            lines.add(lang.component(viewer, "bank.log-header"));
            List<BankLedger.Entry> log = new ArrayList<>(bank.log());
            Collections.reverse(log);
            for (BankLedger.Entry entry : log.subList(0, Math.min(8, log.size())))
                lines.add(lang.component(viewer, "bank.log-row", "time", TIME.format(Instant.ofEpochMilli(entry.time())),
                        "player", entry.actor() == null ? "Vault" : plugin.displayName(plugin.player(entry.actor())),
                        "delta", (entry.delta() > 0 ? "+" : "") + entry.delta(), "balance", entry.balance()));
        }
        return lines;
    }

    // ---- dialogs ----

    void policy(Player player, String bank, String member, String deposit, String withdraw, String single, String daily) {
        if (!enabled() || !player.hasPermission("xpeconomy.bank")) { lang.send(player, "common.no-permission"); return; }
        if (!(deposit.equalsIgnoreCase("on") || deposit.equalsIgnoreCase("off"))
                || !(withdraw.equalsIgnoreCase("on") || withdraw.equalsIgnoreCase("off"))) { lang.send(player, "treasury.usage"); return; }
        try {
            long perMove = Long.parseLong(single), perDay = Long.parseLong(daily);
            OfflinePlayer target = plugin.resolveKnownPlayer(member);
            if (target == null) { sendError(player, "account"); return; }
            synchronized (plugin.transactionLock()) {
                ledger.allowance(bank, player.getUniqueId(), target.getUniqueId(), new BankLedger.Allowance(
                        deposit.equalsIgnoreCase("on"), withdraw.equalsIgnoreCase("on"), perMove, perDay),
                        System.currentTimeMillis(), player.hasPermission("xpeconomy.admin"));
            }
            lang.send(player, "treasury.saved", "bank", bank, "player", member);
        } catch (NumberFormatException invalid) { lang.send(player, "treasury.usage"); }
        catch (IOException failure) { sendError(player, BankTransfers.code(failure)); }
    }

    void history(Player player, String name, int requested) {
        var bank = ledger.get(name);
        if (!enabled() || !player.hasPermission("xpeconomy.bank") || bank.isEmpty()
                || !bank.get().member(player.getUniqueId()) && !player.hasPermission("xpeconomy.admin")) { sendError(player, "not-member"); return; }
        List<BankLedger.Entry> entries = new ArrayList<>(bank.get().log()); Collections.reverse(entries);
        int pages = Math.max(1, (entries.size() + 9) / 10), page = Math.clamp(requested, 1, pages);
        lang.send(player, "treasury.history", "bank", name, "page", page, "pages", pages);
        for (BankLedger.Entry row : entries.subList((page - 1) * 10, Math.min(page * 10, entries.size()))) {
            String kind = row.kind().startsWith("policy:") ? lang.plain(player, "treasury.policy-change") : row.kind();
            lang.send(player, "treasury.entry", "time", TIME.format(Instant.ofEpochMilli(row.time())),
                    "player", row.actor() == null ? "Vault" : plugin.displayName(plugin.player(row.actor())),
                    "kind", kind, "delta", row.delta(), "balance", row.balance());
            if (row.kind().startsWith("policy:")) {
                String[] detail = row.kind().split(":");
                if (detail.length == 6) lang.send(player, "treasury.policy-detail", "player", detail[1], "deposit", lang.plain(player, Boolean.parseBoolean(detail[2]) ? "treasury.yes" : "treasury.no"),
                        "withdraw", lang.plain(player, Boolean.parseBoolean(detail[3]) ? "treasury.yes" : "treasury.no"), "single", detail[4], "daily", detail[5]);
            }
        }
    }

    private void policyMenu(Player p, String name) {
        var bank = ledger.get(name);
        if (bank.isEmpty() || !bank.get().owner().equals(p.getUniqueId()) && !p.hasPermission("xpeconomy.admin")) { sendError(p, "not-owner"); return; }
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(lang.component(p, "treasury.intro")));
        for (UUID member : bank.get().members()) {
            var rule = bank.get().allowance(member);
            body.add(DialogBody.plainMessage(lang.component(p, "treasury.policy-detail", "player", plugin.displayName(plugin.player(member)),
                    "deposit", lang.plain(p, rule.deposit() ? "treasury.yes" : "treasury.no"), "withdraw", lang.plain(p, rule.withdraw() ? "treasury.yes" : "treasury.no"), "single", rule.single(), "daily", rule.daily())));
        }
        List<DialogInput> inputs = List.of(DialogInput.text("player", lang.component(p, "bank.dialog-member")).maxLength(16).build(),
                DialogInput.bool("deposit", lang.component(p, "bank.dialog-deposit")).initial(true).build(),
                DialogInput.bool("withdraw", lang.component(p, "bank.dialog-withdraw")).initial(true).build(),
                DialogInput.text("single", lang.component(p, "treasury.single")).initial("100").maxLength(10).build(),
                DialogInput.text("daily", lang.component(p, "treasury.daily")).initial("1000").maxLength(10).build());
        core.dialogs().open(plugin, p, new DialogRequest(lang.component(p, "treasury.title"), body, inputs,
                List.of(new DialogButton(lang.component(p, "treasury.save"), (v, r) -> {
                    policy(v, name, r.getText("player"), Boolean.TRUE.equals(r.getBoolean("deposit")) ? "on" : "off",
                            Boolean.TRUE.equals(r.getBoolean("withdraw")) ? "on" : "off", r.getText("single"), r.getText("daily"));
                })), v -> v.isOnline() && plugin.isEnabled(), () -> {}));
    }

    /** Your banks as buttons, plus "create bank". */
    void openList(Player player, Component notice) {
        if (!enabled()) { lang.send(player, "bank.disabled"); return; }
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) body.add(DialogBody.plainMessage(notice));
        body.add(DialogBody.plainMessage(lang.component(player, "bank.dialog-intro")));
        List<BankLedger.Bank> banks = ledger.of(player.getUniqueId());
        if (banks.isEmpty()) body.add(DialogBody.plainMessage(lang.component(player, "bank.none")));
        List<DialogButton> buttons = new ArrayList<>();
        for (BankLedger.Bank bank : banks)
            buttons.add(new DialogButton(lang.component(player, "bank.dialog-bank", "bank", bank.name(), "amount", plugin.formatPoints(player, bank.balance())),
                    (p, response) -> openBank(p, bank.name(), null)));
        List<DialogInput> inputs = new ArrayList<>();
        if (ledger.ownedBy(player.getUniqueId()) < maxOwned()) {
            inputs.add(DialogInput.text("name", lang.component(player, "bank.dialog-name")).maxLength(16).build());
            buttons.add(new DialogButton(lang.component(player, "bank.dialog-create"), (p, response) -> {
                String name = String.valueOf(response.getText("name")).trim();
                String error = create(p, name);
                if (error == null) openBank(p, name, lang.component(p, "bank.created", "bank", name));
                else openList(p, errorComponent(p, error));
            }));
        }
        buttons.add(MenuButtons.back(core, plugin::openAccount));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "bank.dialog-title"), body, inputs, buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
    }

    /** One bank: balance, members, log, amount field with deposit/withdraw; owners also manage members and close it. */
    void openBank(Player player, String name, Component notice) {
        var found = ledger.get(name);
        boolean admin = player.hasPermission("xpeconomy.admin");
        if (found.isEmpty() || !(admin || found.get().member(player.getUniqueId()))) { openList(player, errorComponent(player, "unknown")); return; }
        BankLedger.Bank bank = found.get();
        boolean owner = bank.owner().equals(player.getUniqueId()) || admin;
        List<DialogBody> body = new ArrayList<>();
        if (notice != null) body.add(DialogBody.plainMessage(notice));
        Component text = Component.empty();
        List<Component> lines = details(player, bank);
        for (int i = 0; i < lines.size(); i++) text = text.append(i == 0 ? Component.empty() : Component.newline()).append(lines.get(i));
        body.add(DialogBody.plainMessage(text, 300));
        body.add(DialogBody.plainMessage(lang.component(player, "bank.dialog-yours", "amount", plugin.formatPoints(player, plugin.balance(player)))));
        List<DialogInput> inputs = new ArrayList<>(List.of(DialogInput.text("amount", lang.component(player, "transfer.amount",
                "currency", plugin.currencyName(lang.language(player), false))).maxLength(16).build()));
        if (owner) inputs.add(DialogInput.text("player", lang.component(player, "bank.dialog-member")).maxLength(16).build());
        List<DialogButton> buttons = new ArrayList<>();
        buttons.add(new DialogButton(lang.component(player, "bank.dialog-deposit"), (p, response) -> money(p, bank.name(), response.getText("amount"), true)));
        buttons.add(new DialogButton(lang.component(player, "bank.dialog-withdraw"), (p, response) -> money(p, bank.name(), response.getText("amount"), false)));
        buttons.add(new DialogButton(lang.component(player, "treasury.history-button"), (p, response) -> history(p, bank.name(), 1)));
        if (owner) {
            buttons.add(new DialogButton(lang.component(player, "treasury.title"), (p, response) -> policyMenu(p, bank.name())));
            buttons.add(new DialogButton(lang.component(player, "bank.dialog-add"), (p, response) -> member(p, bank.name(), response.getText("player"), true)));
            buttons.add(new DialogButton(lang.component(player, "bank.dialog-remove"), (p, response) -> member(p, bank.name(), response.getText("player"), false)));
            buttons.add(new DialogButton(lang.component(player, "bank.dialog-close"), (p, response) -> confirmClose(p, bank.name())));
        }
        buttons.add(new DialogButton(lang.component(player, "account.refresh"), (p, response) -> openBank(p, bank.name(), null)));
        buttons.add(MenuButtons.back(core, p -> openList(p, null)));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "bank.dialog-bank-title", "bank", bank.name()), body, inputs, buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}, "Schließen", 2));
    }

    private void money(Player player, String bank, String text, boolean in) {
        Long amount = plugin.parsePoints(text == null ? "" : text.trim());
        String error = amount == null || amount <= 0 ? "amount" : in ? deposit(player, bank, amount) : withdraw(player, bank, amount);
        openBank(player, bank, error != null ? errorComponent(player, error)
                : lang.component(player, in ? "bank.deposited" : "bank.withdrawn", "amount", plugin.formatPoints(player, amount),
                        "bank", displayName(bank), "balance", plugin.formatPoints(player, ledger.get(bank).map(BankLedger.Bank::balance).orElse(0L))));
    }

    private void member(Player player, String bank, String name, boolean add) {
        OfflinePlayer target = plugin.resolveKnownPlayer(name == null ? "" : name.trim());
        String error = target == null || !plugin.hasAccount(target.getUniqueId()) ? "account" : members(player, bank, target, add);
        openBank(player, bank, error != null ? errorComponent(player, error)
                : lang.component(player, add ? "bank.member-added" : "bank.member-removed", "player", plugin.displayName(target), "bank", displayName(bank)));
    }

    /** Player menu: add the clicked player to one of the viewer's banks (one button per owned bank). */
    void openAddMember(Player player, Player target) {
        List<DialogButton> buttons = new ArrayList<>();
        for (BankLedger.Bank bank : ownedWithout(player.getUniqueId(), target.getUniqueId()))
            buttons.add(new DialogButton(lang.component(player, "bank.dialog-bank", "bank", bank.name(), "amount", plugin.formatPoints(player, bank.balance())),
                    (p, response) -> member(p, bank.name(), target.getName(), true)));
        if (buttons.isEmpty()) { sendError(player, "unknown"); return; }
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "bank.dialog-add-title", "player", target.getName()),
                List.of(DialogBody.plainMessage(lang.component(player, "bank.dialog-add-intro", "player", target.getName()))), List.of(), buttons,
                p -> p.isOnline() && plugin.isEnabled(), () -> {}));
    }

    /** Banks the player owns that {@code target} is not part of yet. */
    List<BankLedger.Bank> ownedWithout(UUID owner, UUID target) {
        return ledger.of(owner).stream().filter(b -> b.owner().equals(owner) && !b.member(target)).toList();
    }

    // ---- helpers ----

    private String displayName(String bank) { return ledger.get(bank).map(BankLedger.Bank::name).orElse(bank); }

    private void report(CommandSender sender, String error, String successKey, String bank) {
        if (error == null) lang.send(sender, successKey, "bank", displayName(bank));
        else sendError(sender, error);
    }

    private void sendError(CommandSender sender, String code) { core.compat().platform().sendMessage(sender, errorComponent(sender, code)); }

    private Component errorComponent(CommandSender sender, String code) {
        return lang.component(sender, String.join(".", "bank", "error", code), "max", code.equals("owned") ? maxOwned() : maxMembers(),
                "currency", plugin.currencyName(lang.language(sender), false)).colorIfAbsent(NamedTextColor.RED);
    }

    /** Every {@code bank.error.<code>} key a result of this feature can produce. */
    static final List<String> ERRORS = List.of("amount", "unknown", "not-member", "not-owner", "full", "insufficient", "player-full",
            "owner-full", "name", "taken", "owned", "member", "members", "account", "invalid", "capacity", "storage",
            "deposit-denied", "spend-denied", "single-limit", "daily-limit");
}
