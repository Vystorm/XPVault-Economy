package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.io.IOException;
import java.util.*;

/** Explicit payer-confirmed requests. Request IDs are durably claimed before any transfer. */
final class PaymentCommands {
    private final XPVaultEconomy plugin;
    private final API core;
    private final PaymentDesk desk;
    private final Lang lang;
    PaymentCommands(XPVaultEconomy plugin, API core, PaymentDesk desk) {
        this.plugin = plugin; this.core = core; this.desk = desk; this.lang = plugin.lang();
    }
    boolean command(CommandSender sender, String action, String[] args) {
        if (!(sender instanceof Player p)) { lang.send(sender, "common.players-only"); return true; }
        if (!desk.readable()) { lang.send(p, "requests.error.storage"); return true; }
        try {
            switch (action) {
                case "request", "split" -> {
                    if (args.length < 3) throw new IOException("usage");
                    boolean split = action.equals("split");
                    long total = amount(args[split ? 0 : 1]);
                    String[] names = args[split ? 1 : 0].split(",", -1);
                    if (names.length > 16 || (!split && names.length != 1)) throw new IOException("invalid");
                    List<UUID> ids = new ArrayList<>();
                    for (String name : names) {
                        OfflinePlayer target = plugin.resolveKnownPlayer(name);
                        if (target == null || !plugin.hasAccount(target.getUniqueId())) throw new IOException("account");
                        ids.add(target.getUniqueId());
                    }
                    var rows = desk.split(p.getUniqueId(), ids, total, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                    lang.send(p, "requests.created", "count", rows.size(), "amount", plugin.formatPoints(p, total));
                    for (var row : rows) {
                        Player recipient = plugin.player(row.payer()).getPlayer();
                        if (recipient != null) lang.send(recipient, "requests.received", "player", p.getName(),
                                "amount", plugin.formatPoints(recipient, row.amount()), "purpose", row.purpose());
                    }
                }
                case "requests" -> {
                    if (listsRequests(args)) list(p);
                    else if (args.length == 2 && args[0].equalsIgnoreCase("accept")) confirm(p, UUID.fromString(args[1]));
                    else if (args.length == 2 && args[0].equalsIgnoreCase("decline")) {
                        desk.decline(p.getUniqueId(), UUID.fromString(args[1])); lang.send(p, "requests.declined");
                    } else if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
                        desk.cancel(p.getUniqueId(), UUID.fromString(args[1])); lang.send(p, "requests.cancelled");
                    } else throw new IOException("usage");
                }
                case "requestblock" -> {
                    if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) throw new IOException("usage");
                    OfflinePlayer target = plugin.resolveKnownPlayer(args[0]);
                    if (target == null) throw new IOException("account");
                    desk.block(p.getUniqueId(), target.getUniqueId(), args[1].equalsIgnoreCase("on")); lang.send(p, "requests.saved");
                }
                case "limits" -> {
                    if (args.length == 2) desk.limits(p.getUniqueId(), amount(args[0]), amount(args[1]));
                    else if (args.length != 0) throw new IOException("usage");
                    var limit = desk.limit(p.getUniqueId());
                    lang.send(p, "requests.limits", "single", limit.single(), "daily", limit.daily(), "spent", limit.spent());
                }
                default -> { return false; }
            }
        } catch (IOException ex) { error(p, ex); }
        catch (IllegalArgumentException ex) { lang.send(p, "requests.error.invalid"); }
        return true;
    }
    /** Player menu: asks for amount and purpose, then runs the same logic as /economy request &lt;target&gt; &lt;XP&gt; &lt;purpose&gt;. */
    void openRequest(Player player, String target) {
        List<DialogBody> body = List.of(DialogBody.plainMessage(lang.component(player, "requests.dialog-intro", "player", target)));
        List<DialogInput> inputs = List.of(
                DialogInput.text("amount", lang.component(player, "transfer.amount",
                        "currency", plugin.currencyName(lang.language(player), false))).maxLength(16).build(),
                DialogInput.text("purpose", lang.component(player, "requests.dialog-purpose")).maxLength(80).build());
        var send = new DialogButton(lang.component(player, "requests.dialog-send"), (p, response) -> {
            if (!p.hasPermission("xpeconomy.pay")) { lang.send(p, "common.no-permission"); return; }
            String amount = String.valueOf(response.getText("amount")).trim();
            String purpose = String.valueOf(response.getText("purpose")).trim();
            command(p, "request", new String[] {target, amount, purpose});
        });
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "requests.dialog-title", "player", target),
                body, inputs, List.of(send), p -> p.isOnline() && plugin.isEnabled(), () -> {}));
    }
    /** "/economy requests" and "/economy requests list" (any case) both show the request list. */
    static boolean listsRequests(String[] args) {
        return args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"));
    }
    private static long amount(String raw) throws IOException {
        Long value = EconomySettings.parseCommandAmount(raw, Integer.MAX_VALUE);
        if (value == null) throw new IOException("invalid");
        return value;
    }
    private void list(Player player) {
        var rows = desk.requests(player.getUniqueId());
        if (rows.isEmpty()) { lang.send(player, "requests.empty"); return; }
        for (var row : rows) {
            String state = row.status() == PaymentDesk.Status.OPEN && System.currentTimeMillis() >= row.expires()
                    ? "expired" : row.status().name().toLowerCase(Locale.ROOT);
            Component line = lang.component(player, "requests.row", "payer", plugin.displayName(plugin.player(row.payer())),
                    "payee", plugin.displayName(plugin.player(row.payee())), "amount", plugin.formatPoints(player, row.amount()),
                    "purpose", row.purpose(), "state", lang.component(player, stateKey(state)));
            if (row.payer().equals(player.getUniqueId()) && state.equals("open")) {
                line = line.append(Component.space()).append(lang.component(player, "requests.accept")
                        .clickEvent(ClickEvent.runCommand("/economy requests accept " + row.id())))
                        .append(Component.space()).append(lang.component(player, "requests.decline")
                                .clickEvent(ClickEvent.runCommand("/economy requests decline " + row.id())));
            }
            if (row.payee().equals(player.getUniqueId()) && state.equals("open")) {
                line = line.append(Component.space()).append(lang.component(player, "requests.cancel")
                        .clickEvent(ClickEvent.runCommand("/economy requests cancel " + row.id())));
            }
            core.compat().platform().sendMessage(player, line);
        }
    }
    private void confirm(Player player, UUID id) throws IOException {
        var row = desk.incoming(player.getUniqueId(), id).orElseThrow(() -> new IOException("not-open"));
        if (row.status() != PaymentDesk.Status.OPEN || System.currentTimeMillis() >= row.expires()) throw new IOException("not-open");
        long before = plugin.balance(player);
        OfflinePlayer target = plugin.player(row.payee());
        Component problem = plugin.checkTransfer(player, target, row.amount());
        if (problem != null) { core.compat().platform().sendMessage(player, problem); return; }
        if (before < row.amount()) { lang.send(player, "error.insufficient"); return; }
        var preview = XpPaymentPreview.of(before, row.amount());
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(lang.component(player, "requests.confirm", "player", plugin.displayName(target),
                        "amount", plugin.formatPoints(player, row.amount()), "purpose", row.purpose())),
                DialogBody.plainMessage(lang.component(player, "requests.preview", "before", before, "after", preview.after(),
                        "levelBefore", preview.currentLevel(), "levelAfter", preview.projectedLevel())));
        var button = new DialogButton(lang.component(player, "transfer.confirm"), (payer, response) -> {
            if (!payer.hasPermission("xpeconomy.pay")) { lang.send(payer, "common.no-permission"); return; }
            try {
                if (plugin.balance(payer) != before) { confirm(payer, id); return; }
                desk.claim(payer.getUniqueId(), id);
                Component failure = plugin.transfer(payer, target, row.amount());
                try { desk.finish(id, failure == null); }
                catch (IOException saveFailure) {
                    plugin.getLogger().warning("Payment request " + id + " remains PROCESSING; verify the transfer before any manual resolution.");
                    lang.send(payer, "requests.review"); return;
                }
                if (failure != null) core.compat().platform().sendMessage(payer, failure);
                else lang.send(payer, "requests.paid");
            } catch (IOException ex) { error(payer, ex); }
            catch (RuntimeException failure) {
                plugin.getLogger().warning("Payment request " + id + " requires manual review after " + failure.getClass().getSimpleName());
                lang.send(payer, "requests.review");
            }
        });
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "transfer.confirm-title"), body,
                List.of(), List.of(button), p -> p.isOnline() && plugin.isEnabled(), () -> {}));
    }
    static String errorKey(String code) {
        return switch (code) {
            case "usage" -> "requests.error.usage";
            case "invalid" -> "requests.error.invalid";
            case "account" -> "requests.error.account";
            case "purpose" -> "requests.error.purpose";
            case "capacity" -> "requests.error.capacity";
            case "rate" -> "requests.error.rate";
            case "blocked" -> "requests.error.blocked";
            case "not-open" -> "requests.error.not-open";
            case "single" -> "requests.error.single";
            case "daily" -> "requests.error.daily";
            default -> "requests.error.storage";
        };
    }
    private static String stateKey(String state) {
        return switch (state) {
            case "open" -> "requests.state.open";
            case "paid" -> "requests.state.paid";
            case "declined" -> "requests.state.declined";
            case "cancelled" -> "requests.state.cancelled";
            case "failed" -> "requests.state.failed";
            case "expired" -> "requests.state.expired";
            default -> "requests.state.processing";
        };
    }
    private void error(Player p, IOException ex) {
        String code = ex.getMessage();
        if (!Set.of("usage", "invalid", "account", "purpose", "capacity", "rate", "blocked", "not-open", "single", "daily").contains(code == null ? "" : code)) code = "storage";
        lang.send(p, errorKey(code));
    }
}
